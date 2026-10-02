(() => {
  if (location.hostname !== 'www.youtube.com' || !location.pathname.startsWith('/embed/')) return;
  const style = document.createElement('style');
  style.textContent = '.html5-video-container{position:absolute!important;inset:0!important;width:100%!important;height:100%!important}.html5-main-video{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;object-fit:cover!important;object-position:center!important}.ytp-bezel,.ytp-bezel-text-wrapper,.ytp-large-play-button,.ytp-pause-overlay,.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-caption-window-container{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important}';
  const attach = () => {
    if (!document.documentElement) return false;
    document.documentElement.appendChild(style);
    window.parent.postMessage({spitifyVideoSurface: true}, "*");
    return true;
  };
  if (!attach()) {
    const observer = new MutationObserver(() => { if (attach()) observer.disconnect(); });
    observer.observe(document, {childList:true, subtree:true});
  }
})();
