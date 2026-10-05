(() => {
  'use strict';
  if(location.hostname!=='www.youtube.com' || !location.pathname.startsWith('/embed/')) return;
  const style=document.createElement('style');
  style.textContent=`html,body{margin:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important}
    body *{visibility:hidden!important;pointer-events:none!important}
    body *::before,body *::after{content:none!important}
    video.html5-main-video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;max-width:none!important;max-height:none!important;object-fit:cover!important;object-position:50% 50%!important;transform:scale(var(--spitify-crop,1))!important;transform-origin:center!important}
    html[data-spitify-frame="ready"] video.html5-main-video{visibility:visible!important}
    #spitify-held-picture{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;pointer-events:none!important;z-index:2147483647!important;object-fit:cover!important}`;
  let current,displayed=false,framePending=false,initialTarget=null,frames=0,lastPresented=-1;
  let cropCandidate=1,cropMatches=0,cleanFrames=0,lastCropCheck=-Infinity;
  let requested={playing:false,visible:false,reduceMotion:false,resumeID:0};
  let hold,holding=false,cut=null,fadeRequest=null,lastReport=0,lastPlayAttempt=0,lastProgress=Date.now(),lastTime=-1;
  let qualityTarget='',qualityLevels=[],qualityCurrent='',lastQualityRequest=0;
  function requestHD() {
    if(!canRun()) return;
    const player=document.getElementById('movie_player');
    if(!player || typeof player.getAvailableQualityLevels!=='function') return;
    try {
      qualityLevels=player.getAvailableQualityLevels() || [];
      qualityCurrent=typeof player.getPlaybackQuality==='function'?player.getPlaybackQuality():'';
      const target=['highres','hd2880','hd2160','hd1440','hd1080','hd720'].find(level=>qualityLevels.includes(level));
      if(!target) return;
      const now=Date.now();
      if(target===qualityTarget && (qualityCurrent===target || now-lastQualityRequest<5000)) return;
      qualityTarget=target;lastQualityRequest=now;
      // These belong to the page's own player. The public iframe API no longer changes quality.
      // Keep checking decoded dimensions separately: a request alone does not prove HD arrived.
      if(typeof player.setPlaybackQualityRange==='function') player.setPlaybackQualityRange(target,target);
      if(typeof player.setPlaybackQuality==='function') player.setPlaybackQuality(target);
    } catch(_) { /* Keep playback working when the provider changes its page API. */ }
  }
  const root=()=>document.documentElement;
  const canRun=()=>requested.playing && requested.visible && !document.hidden;
  const send=(force=false)=>{
    const now=Date.now();
    if(!force && now-lastReport<250) return;
    lastReport=now;
    window.parent.postMessage({spitifyVideoSurface:true,frameReady:displayed || holding,
      time:current?current.currentTime:0,paused:current?current.paused:true,seeking:current?current.seeking:false,frames,width:current?current.videoWidth:0,height:current?current.videoHeight:0,
      qualityTarget,qualityLevels,qualityCurrent},'*');
  };
  const cutState=(phase,extra={})=>{
    if(cut) window.parent.postMessage({spitifyCutState:{id:cut.id,phase,...extra}},'*');
  };
  function hide(element) {
    if(!element || element.nodeType!==1) return;
    const visible=(element===current && displayed) || (element===hold && holding);
    const wanted=visible?'visible':'hidden';
    if(element.style.getPropertyValue('visibility')!==wanted || element.style.getPropertyPriority('visibility')!=='important') element.style.setProperty('visibility',wanted,'important');
    if(element!==current && element!==hold && element.shadowRoot) element.shadowRoot.querySelectorAll('*').forEach(hide);
  }
  function lockTree(node) {
    if(!node) return;
    hide(node);if(node.querySelectorAll) node.querySelectorAll('*').forEach(hide);
  }
  function fill(video) {
    for(let parent=video.parentElement;parent && parent!==document.body;parent=parent.parentElement) {
      for(const [name,value] of [['transform','none'],['filter','none'],['perspective','none'],['contain','none'],['overflow','visible']]) {
        if(parent.style.getPropertyValue(name)!==value) parent.style.setProperty(name,value,'important');
      }
    }
    for(const [name,value] of [['position','fixed'],['inset','0px'],['width','100vw'],['height','100vh'],['min-width','100vw'],['min-height','100vh'],['max-width','none'],['max-height','none'],['object-fit','cover'],['transform','scale(var(--spitify-crop,1))']]) {
      if(video.style.getPropertyValue(name)!==value || video.style.getPropertyPriority(name)!=='important') video.style.setProperty(name,value,'important');
    }
    if(video.controls) video.controls=false;
    if(!video.muted) video.muted=true;
    if(video.volume!==0) video.volume=0;
    if(!video.hasAttribute('playsinline')) video.setAttribute('playsinline','');
  }
  function reconcile(force=false) {
    const video=current;if(!video) return;
    fill(video);
    if(!canRun()) {if(!video.paused) video.pause();return;}
    const now=Date.now();
    if((video.paused || (force && now-lastProgress>1800)) && (force || now-lastPlayAttempt>900)) {
      lastPlayAttempt=now;
      const result=video.play();
      if(result && typeof result.catch==='function') result.catch(()=>{});
    }
    if(cut && cut.phase==='fading' && fadeRequest===null) fadeRequest=requestAnimationFrame(fade);
  }
  function inspectBars(video) {
    const now=Date.now();
    if(now-lastCropCheck<700 || video.readyState<2 || !video.videoWidth) return cropMatches>0 && cropMatches<3;
    lastCropCheck=now;
    try {
      const canvas=document.createElement('canvas');canvas.width=96;canvas.height=54;
      const context=canvas.getContext('2d',{willReadFrequently:true});context.drawImage(video,0,0,96,54);
      const pixels=context.getImageData(0,0,96,54).data;
      const row=y=>{
        let min=255,max=0,sum=0;
        for(let x=8;x<88;x++){const p=(y*96+x)*4,luma=(pixels[p]+pixels[p+1]+pixels[p+2])/3;min=Math.min(min,luma);max=Math.max(max,luma);sum+=luma;}
        return {mean:sum/80,spread:max-min};
      };
      const dark=y=>{const value=row(y);return value.mean<10 && value.spread<9;};
      let top=0,bottom=0;
      while(top<12 && dark(top)) top++;while(bottom<12 && dark(53-bottom)) bottom++;
      if(top<2 || bottom<2 || top>=12 || bottom>=12 || Math.abs(top-bottom)>2 || row(27).mean<20) {
        cleanFrames=row(27).mean>=20 && (top<2 || bottom<2)?cleanFrames+1:0;
        if(cleanFrames>=3) root().style.setProperty('--spitify-crop','1');
        cropMatches=0;return false;
      }
      cleanFrames=0;
      const next=Math.min(1.75,54/(54-top-bottom)*1.025);
      cropMatches=Math.abs(next-cropCandidate)<.04?cropMatches+1:1;cropCandidate=next;
      if(cropMatches>=3) root().style.setProperty('--spitify-crop',next.toFixed(4));
      return cropMatches<3;
    } catch(_) {return false;}
  }
  function capture() {
    if(!current || !displayed || current.readyState<2 || document.querySelector('.ad-showing')) return false;
    try {
      if(!hold) {hold=document.createElement('canvas');hold.id='spitify-held-picture';root().appendChild(hold);}
      const width=Math.max(1,window.innerWidth),height=Math.max(1,window.innerHeight);
      const resolution=Math.min(2,window.devicePixelRatio||1,1920/Math.max(width,height));
      hold.width=Math.round(width*resolution);hold.height=Math.round(height*resolution);
      const crop=Math.max(1,parseFloat(root().style.getPropertyValue('--spitify-crop'))||1);
      const scale=Math.max(width/current.videoWidth,height/current.videoHeight)*crop;
      const sourceWidth=width/scale,sourceHeight=height/scale;
      // Drawing and DISPLAYING cross-origin video is allowed. Reading or exporting its pixels
      // is not needed for the held picture, so a tainted canvas still gives us a clean dissolve.
      hold.getContext('2d').drawImage(current,(current.videoWidth-sourceWidth)/2,(current.videoHeight-sourceHeight)/2,
        sourceWidth,sourceHeight,0,0,hold.width,hold.height);
      holding=true;hold.style.opacity='1';hide(hold);send(true);return true;
    } catch(_) {holding=false;hide(hold);return false;}
  }
  function clearCut() {
    if(fadeRequest!==null) cancelAnimationFrame(fadeRequest);
    fadeRequest=null;cut=null;holding=false;
    if(hold) {hold.style.opacity='0';hide(hold);}
  }
  function fade(timestamp) {
    fadeRequest=null;
    if(!cut || cut.phase!=='fading') return;
    if(!canRun()) {cut.lastFade=null;return;}
    if(cut.lastFade!==null) cut.fadeElapsed+=Math.max(0,Math.min(100,timestamp-cut.lastFade));
    cut.lastFade=timestamp;
    const length=requested.reduceMotion?0:cut.duration;
    const progress=length===0?1:Math.min(1,cut.fadeElapsed/length);
    const ease=progress*progress*(3-2*progress);
    hold.style.opacity=String(1-ease);
    if(progress>=1) {
      cutState('complete',{time:current.currentTime});clearCut();send(true);
    } else fadeRequest=requestAnimationFrame(fade);
  }
  function revealCut(video,mediaTime,afterSeek=false) {
    if(!cut || cut.phase!=='seeking' || video.seeking || video.readyState<2) return;
    const inPassage=mediaTime>=cut.target-.15 && mediaTime<cut.target+5;
    const fresh=frames>cut.capturedFrames || afterSeek;
    // A new frame at the old time is not enough. Keep the old picture until the destination
    // is decoded. If a host ignores its seek, recover to moving video instead of freezing forever.
    if(!fresh || (!inPassage && cut.elapsed<8500)) return;
    displayed=true;root().setAttribute('data-spitify-frame','ready');hide(video);
    cut.phase='fading';cut.fadeElapsed=0;cut.lastFade=null;cutState('revealing');
    if(canRun()) fadeRequest=requestAnimationFrame(fade);
  }
  function markReady(video,afterSeek=false) {
    framePending=false;
    if(document.querySelector('.ad-showing') || video!==current || video.readyState<2 || !video.videoWidth || !video.videoHeight) return;
    fill(video);
    if(initialTarget!==null) {
      if(video.seeking || video.currentTime<initialTarget-.15 || video.currentTime>initialTarget+5) return;
      initialTarget=null;
    }
    if(cut && cut.phase==='seeking') {revealCut(video,lastPresented>=0?lastPresented:video.currentTime,afterSeek);return;}
    if(inspectBars(video)) return;
    displayed=true;root().setAttribute('data-spitify-frame','ready');hide(video);send(true);
  }
  function hideFrame() {
    cutState('cancelled');
    clearCut();displayed=false;root().removeAttribute('data-spitify-frame');hide(current);send(true);
  }
  function watchFrames(video) {
    if(typeof video.requestVideoFrameCallback!=='function') return;
    video.requestVideoFrameCallback((_now,metadata)=>{
      if(video!==current) return;
      frames++;lastPresented=metadata.mediaTime;lastProgress=Date.now();
      if(!displayed || cut) markReady(video);
      send();watchFrames(video);
    });
  }
  function checkFrame() {
    if(document.querySelector('.ad-showing')) {hideFrame();return;}
    const video=document.querySelector('video.html5-main-video');
    if(video!==current) {
      if(current) hideFrame();
      if(current){const previous=current;current=null;hide(previous);}
      current=video;displayed=false;framePending=false;frames=0;lastPresented=-1;
      cropMatches=0;cleanFrames=0;cropCandidate=1;lastCropCheck=-Infinity;
      root().style.setProperty('--spitify-crop','1');root().removeAttribute('data-spitify-frame');send(true);
      if(video) {
        fill(video);hide(video);watchFrames(video);
        video.addEventListener('loadeddata',checkFrame);
        video.addEventListener('playing',()=>{checkFrame();send(true);});
        video.addEventListener('pause',()=>{send(true);});
        video.addEventListener('seeked',()=>{
          // Reset the frame-time guard: the old presented timestamp must not approve the new seek.
          lastPresented=-1;
          requestAnimationFrame(()=>requestAnimationFrame(()=>{if(current===video && !video.seeking) markReady(video,true);}));
        });
        video.addEventListener('timeupdate',()=>{
          if(current!==video) return;
          if(Math.abs(video.currentTime-lastTime)>.025){lastTime=video.currentTime;lastProgress=Date.now();}
          inspectBars(video);if(!displayed || cut) checkFrame();send();
        });
        video.addEventListener('emptied',()=>{if(current===video && !holding) hideFrame();});
        video.addEventListener('error',()=>{if(current===video) hideFrame();});
        reconcile(true);
      }
    }
    if(!video) return;
    fill(video);
    if(cut && cut.phase==='seeking') revealCut(video,lastPresented>=0?lastPresented:video.currentTime);
    if(displayed || framePending || video.readyState<2 || !video.videoWidth) return;
    framePending=true;
    // A hidden video may not submit a compositor callback. A decoded current frame still works
    // for initial display; seek transitions additionally check their destination and generation.
    requestAnimationFrame(()=>requestAnimationFrame(()=>{if(video===current) markReady(video);}));
  }
  function attach() {
    if(!root()) return false;
    root().appendChild(style);lockTree(document.body);send(true);checkFrame();
    new MutationObserver(records=>{
      for(const change of records) {
        if(change.type==='attributes') hide(change.target);else change.addedNodes.forEach(lockTree);
      }
      checkFrame();
    }).observe(root(),{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','controls']});
    return true;
  }
  if(!attach()) {
    const observer=new MutationObserver(()=>{if(attach()) observer.disconnect();});
    observer.observe(document,{childList:true,subtree:true});
  }
  let lastTick=Date.now();
  setInterval(()=>{
    const now=Date.now(),delta=Math.max(0,Math.min(1000,now-lastTick));lastTick=now;
    if(cut && canRun()) cut.elapsed+=delta;
    reconcile();checkFrame();requestHD();send();
  },250);
  window.addEventListener('message',event=>{
    if(event.source!==window.parent || !event.data) return;
    const data=event.data;
    if(data.spitifyRequestFrame===true){checkFrame();send(true);}
    if(data.spitifyInitialFrame) {
      initialTarget=Number(data.spitifyInitialFrame.target);
      if(!holding) {displayed=false;root().removeAttribute('data-spitify-frame');hide(current);send(true);}
    }
    if(data.spitifyCanvas) {
      const state=data.spitifyCanvas;
      const resumed=state.resumeID!==requested.resumeID || (state.playing===true && !requested.playing);
      requested={playing:state.playing===true,visible:state.visible===true,reduceMotion:state.reduceMotion===true,resumeID:state.resumeID};
      root().setAttribute('data-spitify-paused',String(state.paused===true));
      root().setAttribute('data-spitify-reduce-motion',String(requested.reduceMotion));
      root().setAttribute('data-spitify-visible',String(requested.visible));
      reconcile(resumed);send(true);
    }
    if(data.spitifyPrepareCut) {
      const next=data.spitifyPrepareCut;
      if(cut && next.id<=cut.id) return;
      clearCut();
      cut={id:next.id,target:Number(next.target),duration:Math.max(0,Math.min(1200,Number(next.duration)||850)),phase:'prepared',
        capturedFrames:frames,elapsed:0,fadeElapsed:0,lastFade:null};
      cutState('prepared',{captured:capture()});
      if(!holding) cut=null;
    }
    if(data.spitifySeekCut && cut && data.spitifySeekCut.id===cut.id) {
      cut.phase='seeking';cut.target=Number(data.spitifySeekCut.target);cut.capturedFrames=frames;cut.elapsed=0;lastPresented=-1;
    }
    if(data.spitifyCancelCut && cut && data.spitifyCancelCut.id===cut.id) {clearCut();send(true);}
  });
  document.addEventListener('visibilitychange',()=>{reconcile(true);send(true);});
})();
