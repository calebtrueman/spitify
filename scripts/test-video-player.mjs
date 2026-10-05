import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html=fs.readFileSync(new URL('../shared/video/music-video.html',import.meta.url),'utf8');
const source=html.match(/<script>([\s\S]*?)<\/script>/)[1];
function harness() {
  const calls=[],reports=[],messages=[],events={};
  let config,position=0,state=-1,rate=1,now=10000,visibility,tick;
  const stage={clientWidth:400,clientHeight:800,style:{}};
  const frame={style:{},contentWindow:{postMessage:(message,origin)=>messages.push([message,origin])}};
  const player={
    unloadModule:()=>{},setOption:()=>{},setSize:(w,h)=>calls.push(['size',w,h]),
    mute:()=>calls.push('mute'),setVolume:v=>calls.push(['volume',v]),
    getCurrentTime:()=>position,getDuration:()=>240,getPlaybackRate:()=>rate,
    setPlaybackRate:v=>{rate=v;calls.push(['rate',v]);},getPlayerState:()=>state,
    playVideo:()=>{state=1;calls.push('play');},pauseVideo:()=>{state=2;calls.push('pause');},
    seekTo:v=>{position=v;calls.push(['seek',v]);},stopVideo:()=>calls.push('stop'),destroy:()=>calls.push('destroy')
  };
  const context=vm.createContext({console:{log:v=>reports.push(v)},Date:{now:()=>now},
    setInterval:fn=>{tick=fn;return 1;},clearInterval:()=>{tick=null;},location:{origin:'https://com.calebtrueman.spitify'},
    document:{hidden:false,getElementById:id=>id==='stage'?stage:frame,addEventListener:(_,fn)=>visibility=fn},
    window:{addEventListener:(name,fn)=>events[name]=fn},YT:{Player:function(_,options){config=options;return player;}}
  });
  vm.runInContext(source,context);
  return {context,calls,reports,messages,stage,frame,
    start(){context.onYouTubeIframeAPIReady();config.events.onReady();},
    state(value){if(value!==undefined) state=value;return state;},
    position(value){if(value!==undefined) position=value;return position;},
    advance(ms){now+=ms;if(tick) tick();},
    message(data,origin='https://www.youtube.com',sender=frame.contentWindow){events.message({origin,source:sender,data});},
    decoded(){this.message({spitifyVideoSurface:true,frameReady:true,time:position,paused:state===2,seeking:false,frames:12});},
    hidden(value){context.document.hidden=value;visibility();},
    count(name){return calls.filter(c=>c===name || (Array.isArray(c)&&c[0]===name)).length;},
    cut(){return messages.findLast(m=>m[0].spitifyPrepareCut)?.[0].spitifyPrepareCut;},
    cutState(phase,id=this.cut().id,extra={}){this.message({spitifyCutState:{id,phase,...extra}});}
  };
}

// An unattached preload must stay still. Display decodes one frame, even while music is paused.
const h=harness();h.start();
assert.equal(h.count('play'),0);assert.equal(h.count('seek'),0);
assert.equal(h.reports.at(-1),'SPITIFY_VIDEO_WAITING');
h.context.spitifySetVisible(true);
assert.ok(h.count('play')>0);assert.ok(h.count('seek')>0);
assert.equal(h.calls[0],'mute');
assert.ok(h.calls.indexOf('play')>h.calls.findIndex(c=>Array.isArray(c)&&c[0]==='volume'&&c[1]===0));
h.message({spitifyVideoSurface:true,frameReady:true},'https://wrong.example');
assert.notEqual(h.frame.style.opacity,'1');
h.decoded();assert.equal(h.frame.style.opacity,'1');assert.equal(h.state(),2);
assert.equal(h.stage.style.animationPlayState,'running');
const pauseCalls=h.count('pause');h.decoded();h.decoded();
assert.equal(h.count('pause'),pauseCalls,'Repeated surface updates must not create a pause-message feedback loop');

// Keep many substantial passages, avoid intro/credits, and never replace the music seek position.
const plan=h.context.spitifyMakeLoop(240,'song');
assert.ok(plan.length>=6 && plan.length<=10);
assert.ok(plan.reduce((sum,clip)=>sum+clip.length,0)>140,'A normal music video must offer minutes of footage, not a repeated seven seconds');
assert.equal(new Set(plan.map(p=>p.start)).size,plan.length);
const sorted=[...plan].sort((a,b)=>a.start-b.start);
assert.ok(sorted.every((p,i)=>!i || p.start>=sorted[i-1].start+sorted[i-1].length));
assert.ok(sorted[0].start>=18 && sorted.at(-1).start+sorted.at(-1).length<=224);
assert.equal(JSON.stringify(plan),JSON.stringify(h.context.spitifyMakeLoop(240,'song')));
assert.notEqual(JSON.stringify(plan),JSON.stringify(h.context.spitifyMakeLoop(240,'another-song')));
for(const duration of [.3,1,8,16,20,120,600]) {
  const clips=h.context.spitifyMakeLoop(duration,'short');
  assert.ok(clips.length>0 && clips.every(c=>c.start>=0 && c.length>0 && c.start+c.length<=duration),`Out-of-bounds clip for ${duration}`);
}
assert.equal(h.context.spitifyMakeLoop(0,'x').length,0);
const original=h.position(),seekCalls=h.count('seek');
h.context.spitifySync(190,true,2,false);
assert.equal(h.position(),original);assert.equal(h.count('seek'),seekCalls);
assert.equal(h.stage.style.animationPlayState,'paused');

// A paused seek may leave the provider in BUFFERING. Resume must still request real playback.
h.context.spitifySync(190,false,1,false);h.state(3);
const beforeResume=h.count('play');h.context.spitifySync(190,true,1,false);
assert.equal(h.count('play'),beforeResume+1);assert.equal(h.state(),1);
h.state(3);h.advance(1500);assert.equal(h.state(),1,'The watchdog recovers a stale buffering state');

// No jump happens until an actual old frame is held. A delayed/wrong acknowledgement cannot seek.
h.position(original+24);h.advance(2000);
const cut=h.cut();assert.ok(cut);const beforeCut=h.count('seek');
h.cutState('prepared',cut.id-1,{captured:true});assert.equal(h.count('seek'),beforeCut);
h.context.spitifySync(190,false,1,false);
h.cutState('prepared',cut.id,{captured:true});assert.equal(h.count('seek'),beforeCut,'Pausing during capture must defer the seek');
h.advance(10000);assert.equal(h.context.spitifyVideoState().transition.phase,'armed');
h.context.spitifySync(190,true,1,false);assert.equal(h.count('seek'),beforeCut+1);
assert.equal(h.position(),cut.target);
h.cutState('revealing');
h.context.spitifySync(190,false,1,false);h.advance(10000);
assert.equal(h.context.spitifyVideoState().transition.phase,'revealing','A paused dissolve must retain its current composition');
h.context.spitifySync(190,true,1,false);h.cutState('complete',cut.id,{time:cut.target+.8});
assert.equal(h.context.spitifyVideoState().transition,null);assert.equal(h.context.spitifyVideoState().shot,1);
assert.equal(h.frame.style.opacity,'1','The native surface stays visible during the entire cut');

// A failed snapshot extends the current passage instead of making a blind jump or black flash.
h.position(cut.target+30);h.advance(2000);const failed=h.cut(),seeks=h.count('seek');
h.cutState('prepared',failed.id,{captured:false});assert.equal(h.count('seek'),seeks);
assert.equal(h.context.spitifyVideoState().transition,null);

// An ad or replaced video element can discard the child's held picture during a cut.
// Clear that exact cut so later passages continue; ignore cancellations from older cuts.
h.advance(3500);const interrupted=h.cut();
h.cutState('prepared',interrupted.id,{captured:true});h.cutState('revealing',interrupted.id);
h.cutState('cancelled',interrupted.id-1);
assert.equal(h.context.spitifyVideoState().transition.id,interrupted.id);
h.cutState('cancelled',interrupted.id);
assert.equal(h.context.spitifyVideoState().transition,null);
h.message({spitifyVideoSurface:true,frameReady:false,time:h.position()});
h.advance(3500);h.decoded();h.advance(250);
assert.ok(h.cut().id>interrupted.id,'A discarded held picture must not stop later passage changes');
h.cutState('prepared',h.cut().id,{captured:false});

// Offscreen and background are separate from pause; foreground restores the requested play state.
h.context.spitifySetVisible(false);assert.equal(h.state(),2);assert.equal(h.context.spitifyVideoState().playing,true);
h.advance(10000);assert.equal(h.state(),2);
h.context.spitifySetVisible(true);assert.equal(h.state(),1);
h.hidden(true);assert.equal(h.state(),2);h.hidden(false);assert.equal(h.state(),1);
h.context.spitifySync(0,false,1,true);assert.equal(h.stage.style.animationName,'none');
h.context.spitifySetVisible(false);h.context.spitifySetVisible(true);assert.equal(h.state(),2,'A user pause survives leaving the screen');
h.stage.clientWidth=1200;h.stage.clientHeight=600;h.context.spitifyResize();assert.equal(h.frame.style.width,'1200px');assert.equal(h.frame.style.height,'675px');
h.context.spitifyStop();assert.equal(h.calls.at(-1),'destroy');
console.log('Video behavior passed: varied long passages, capture-before-seek, guarded dissolve, pause/resume from buffering, native visibility, background, reduced motion and teardown.');

const helper=fs.readFileSync(new URL('../shared/video/video-controls.js',import.meta.url),'utf8');
assert.match(helper,/body \*\{visibility:hidden!important/);
assert.match(helper,/visibility',wanted,'important'/);
assert.match(helper,/requestVideoFrameCallback/);
assert.match(helper,/cropMatches>=3/);
assert.match(helper,/\.ad-showing/);
assert.doesNotMatch(helper,/toDataURL|toBlob|captureStream/,'The held picture must not require exporting cross-origin video');
console.log('Video overlay, frame gate and cross-origin snapshot constraints passed; real pixel checks run natively.');

// Preserve enough source pixels when a wide clip fills a tall screen, and adapt to portrait clips.
const quality=harness();quality.start();
assert.equal(quality.frame.style.width,'1423px');assert.equal(quality.frame.style.height,'800px');
quality.message({spitifyVideoSurface:true,frameReady:true,time:1,width:1080,height:1920});
assert.equal(quality.frame.style.width,'450px');assert.equal(quality.frame.style.height,'800px');
assert.ok(quality.reports.includes('SPITIFY_VIDEO_QUALITY:1080x1920'));
assert.equal(quality.context.spitifyVideoState().surface.height,1920);
