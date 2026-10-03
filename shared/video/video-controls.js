(() => {
  'use strict';
  if (location.hostname !== 'www.youtube.com' || !location.pathname.startsWith('/embed/')) return;
  const style = document.createElement('style');
  style.textContent = `html,body{margin:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important}
    body *{visibility:hidden!important;pointer-events:none!important}
    body *::before,body *::after{content:none!important}
    video.html5-main-video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;max-width:none!important;max-height:none!important;object-fit:cover!important;object-position:var(--spitify-focus,50% 50%)!important;transform:scale(var(--spitify-crop,1))!important;transform-origin:center!important}
    html[data-spitify-frame="ready"] video.html5-main-video{visibility:visible!important}
    html[data-spitify-paused="true"]:not([data-spitify-reduce-motion="true"]) video.html5-main-video{animation:spitify-held-frame 20s ease-in-out infinite alternate!important}
    @keyframes spitify-held-frame{from{translate:-.5% .25%;scale:1.035}to{translate:.5% -.25%;scale:1.065}}
    @media(prefers-reduced-motion:reduce){video.html5-main-video{animation:none!important}}`;
  let current, displayed = false, framePending = false, cropCandidate = 1, cropMatches = 0, cleanFrames = 0, lastCropCheck = -Infinity;
  let paused = true, reducedMotion = false;
  const send = ready => window.parent.postMessage({spitifyVideoSurface:true, frameReady:ready}, '*');
  const root = () => document.documentElement;
  function hide(element) {
    if (!element || element.nodeType !== 1) return;
    const visible = element === current && displayed;
    const wanted = visible ? 'visible' : 'hidden';
    if (element.style.getPropertyValue('visibility') !== wanted || element.style.getPropertyPriority('visibility') !== 'important') element.style.setProperty('visibility', wanted, 'important');
    if (element !== current && element.shadowRoot) element.shadowRoot.querySelectorAll('*').forEach(hide);
  }
  function lockTree(node) {
    if (!node) return;
    hide(node);
    if (node.querySelectorAll) node.querySelectorAll('*').forEach(hide);
  }
  function fill(video) {
    // An embed often gives its video container a short, transformed rectangle. The video must
    // use the whole WebView, not that rectangle, including after YouTube changes its layout.
    for (let parent = video.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
      for (const [name, value] of [['transform','none'],['filter','none'],['perspective','none'],['contain','none'],['overflow','visible']]) {
        if (parent.style.getPropertyValue(name) !== value) parent.style.setProperty(name, value, 'important');
      }
    }
    for (const [name,value] of [['position','fixed'],['inset','0px'],['width','100vw'],['height','100vh'],['min-width','100vw'],['min-height','100vh'],['max-width','none'],['max-height','none'],['object-fit','cover'],['transform','scale(var(--spitify-crop,1))']]) {
      if (video.style.getPropertyValue(name) !== value || video.style.getPropertyPriority(name) !== 'important') video.style.setProperty(name,value,'important');
    }
    video.controls = false;
    video.muted = true;
    video.volume = 0;
    video.setAttribute('playsinline', '');
  }
  function inspectBars(video) {
    const now = Date.now();
    if (now - lastCropCheck < 700 || video.readyState < 2 || !video.videoWidth) return cropMatches > 0 && cropMatches < 3;
    lastCropCheck = now;
    try {
      const canvas = document.createElement('canvas'); canvas.width = 96; canvas.height = 54;
      const context = canvas.getContext('2d', {willReadFrequently:true});
      context.drawImage(video, 0, 0, 96, 54);
      const pixels = context.getImageData(0, 0, 96, 54).data;
      const row = y => {
        let min = 255, max = 0, sum = 0;
        for (let x = 8; x < 88; x++) { const p = (y*96+x)*4; const luma = (pixels[p]+pixels[p+1]+pixels[p+2])/3; min = Math.min(min,luma); max = Math.max(max,luma); sum += luma; }
        return {mean:sum/80, spread:max-min};
      };
      const dark = y => { const value = row(y); return value.mean < 10 && value.spread < 9; };
      let top = 0, bottom = 0;
      while (top < 12 && dark(top)) top++;
      while (bottom < 12 && dark(53-bottom)) bottom++;
      // Require narrow, matching, uniformly dark edges and a visibly brighter picture inside.
      // Never mistake a fade-to-black or a dark scene for a letterbox.
      if (top < 2 || bottom < 2 || top >= 12 || bottom >= 12 || Math.abs(top-bottom)>2 || row(27).mean < 20) {
        cleanFrames = row(27).mean >= 20 && (top < 2 || bottom < 2) ? cleanFrames+1 : 0;
        if (cleanFrames >= 3) root().style.setProperty('--spitify-crop', '1');
        cropMatches = 0; return false;
      }
      cleanFrames = 0;
      // Sampling and video scaling soften the border by a pixel or two. Leave a small
      // margin so those dark seams cannot survive at the very edge of the screen.
      const next = Math.min(1.75, 54/(54-top-bottom)*1.025);
      cropMatches = Math.abs(next-cropCandidate) < .04 ? cropMatches+1 : 1;
      cropCandidate = next;
      if (cropMatches >= 3) root().style.setProperty('--spitify-crop', next.toFixed(4));
      return cropMatches < 3;
    } catch (_) {
      // Some hosts forbid pixel reads. Viewport cover still applies; never reveal a player UI
      // or invent crop measurements when the video cannot be sampled.
      return false;
    }
  }
  function markReady(video) {
    framePending = false;
    if (document.querySelector('.ad-showing') || video !== current || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;
    fill(video);
    if (inspectBars(video)) return; // Confirm suspected bars before revealing the first frame.
    displayed = true;
    root().setAttribute('data-spitify-frame', 'ready'); hide(video); send(true);
  }
  function hideFrame() {
    displayed = false; root().removeAttribute('data-spitify-frame'); hide(current); send(false);
  }
  function checkFrame() {
    if (document.querySelector('.ad-showing')) { hideFrame(); return; }
    const video = document.querySelector('video.html5-main-video');
    if (video !== current) {
      if (current) { const previous = current; current = null; hide(previous); }
      current = video; displayed = false; framePending = false; cropMatches = 0; cleanFrames = 0; cropCandidate = 1; lastCropCheck = -Infinity;
      root().style.setProperty('--spitify-crop', '1'); root().removeAttribute('data-spitify-frame'); send(false);
      if (video) {
        fill(video); hide(video);
        video.addEventListener('loadeddata', checkFrame);
        video.addEventListener('playing', checkFrame);
        video.addEventListener('timeupdate', () => { if (current === video) { inspectBars(video); if (!displayed) checkFrame(); } });
        video.addEventListener('emptied', () => { if (current === video) hideFrame(); });
        video.addEventListener('error', () => { if (current === video) hideFrame(); });
      }
    }
    if (!video) return;
    fill(video);
    if (displayed || framePending || video.readyState < 2 || !video.videoWidth) return;
    framePending = true;
    let delivered = false;
    const decoded = () => { if (!delivered) { delivered = true; markReady(video); } };
    if (typeof video.requestVideoFrameCallback === 'function' && !video.paused) video.requestVideoFrameCallback(decoded);
    // A hidden/preloaded surface may not submit frames to the compositor. HAVE_CURRENT_DATA
    // already means a decoded frame is available, so do not deadlock waiting for its first paint.
    requestAnimationFrame(() => requestAnimationFrame(decoded));
  }
  function attach() {
    if (!root()) return false;
    root().appendChild(style); lockTree(document.body); send(false); checkFrame();
    new MutationObserver(records => {
      for (const change of records) {
        if (change.type === 'attributes') hide(change.target);
        else change.addedNodes.forEach(lockTree);
      }
      checkFrame();
    }).observe(root(), {childList:true, subtree:true, attributes:true, attributeFilter:['style','class','controls']});
    return true;
  }
  if (!attach()) {
    const observer = new MutationObserver(() => { if (attach()) observer.disconnect(); });
    observer.observe(document, {childList:true, subtree:true});
  }
  window.addEventListener('message', event => {
    if (event.source !== window.parent || !event.data) return;
    if (event.data.spitifyRequestFrame === true) { checkFrame(); send(displayed); }
    const canvas = event.data.spitifyCanvas;
    if (canvas) {
      paused = canvas.paused === true; reducedMotion = canvas.reduceMotion === true;
      root().setAttribute('data-spitify-paused', String(paused));
      root().setAttribute('data-spitify-reduce-motion', String(reducedMotion));
      root().style.setProperty('--spitify-focus', reducedMotion ? '50% 50%' : canvas.shot === 1 ? '52% 50%' : '48% 50%');
      if (current) { fill(current); inspectBars(current); }
    }
  });
})();
