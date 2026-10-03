// A small offline stand-in for the provider API. The video itself is decoded by WebView.
window.YT = { Player: function(target, options) {
  const frame = document.createElement('iframe');
  frame.id = target; frame.allow = 'autoplay';
  frame.src = 'https://www.youtube.com/embed/' + options.videoId;
  document.getElementById(target).replaceWith(frame);
  let state = -1, time = 0, duration = 0, rate = 1, muted = false, volume = 100;
  const command = (name, value) => frame.contentWindow.postMessage({fixtureCommand:name, value}, 'https://www.youtube.com');
  window.fixtureSetAd = value => command('ad', value);
  this.getIframe = () => frame;
  this.getPlayerState = () => state;
  this.getCurrentTime = () => time;
  this.getDuration = () => duration;
  this.getPlaybackRate = () => rate;
  this.setPlaybackRate = value => { rate = value; command('rate', value); };
  this.mute = () => { muted = true; command('mute'); };
  this.isMuted = () => muted;
  this.setVolume = value => { volume = value; command('volume', value); };
  this.getVolume = () => volume;
  this.seekTo = value => setTimeout(() => command('seek', value), window.fixtureSeekDelay || 0);
  this.playVideo = () => command('play');
  this.pauseVideo = () => command('pause');
  this.stopVideo = () => command('pause');
  this.setSize = (width, height) => { frame.width = width; frame.height = height; };
  this.destroy = () => frame.remove();
  this.unloadModule = this.setOption = () => {};
  window.addEventListener('message', event => {
    if (event.source !== frame.contentWindow || !event.data?.fixtureStatus) return;
    const data = event.data;
    window.fixtureSurfaceDebug = data.debug;
    const changed = state !== data.state;
    state = data.state; time = data.time; duration = data.duration;
    if (data.ready) options.events.onReady();
    if (changed) options.events.onStateChange({data:state});
  });
}};
window.onYouTubeIframeAPIReady();
