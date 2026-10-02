import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../shared/video/music-video.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const calls = [];
let options;
let position = 0;
let state = -1;
let rate = 1;
let visibility;
const mock = {
  mute: () => calls.push('mute'), setVolume: v => calls.push(['volume', v]),
  getCurrentTime: () => position, getAvailablePlaybackRates: () => [0.5, 1, 1.5, 2],
  getPlaybackRate: () => rate, setPlaybackRate: v => { rate = v; calls.push(['rate', v]); },
  getPlayerState: () => state, playVideo: () => { state = 1; calls.push('play'); },
  pauseVideo: () => { state = 2; calls.push('pause'); },
  seekTo: v => { position = v; calls.push(['seek', v]); },
  stopVideo: () => calls.push('stop'), destroy: () => calls.push('destroy')
};
const context = vm.createContext({console: {log() {}}, Date, location: {origin: 'https://com.calebtrueman.spitify'},
  document: {hidden: false, addEventListener: (_, f) => visibility = f}, window: {},
  YT: {Player: function(_, config) { options = config; return mock; }}
});
vm.runInContext(source, context);
context.spitifySync(42, true, 1);
assert.equal(calls.length, 0, 'No playback before the embed is ready');
context.onYouTubeIframeAPIReady();
options.events.onReady();
assert.equal(calls[0], 'mute', 'Mute must happen before the first play');
assert.ok(calls.findIndex(v => v === 'play') > calls.findIndex(v => Array.isArray(v) && v[0] === 'volume' && v[1] === 0));
assert.equal(position, 42);
context.spitifySync(42, false, 1);
assert.equal(state, 2);
context.spitifySync(42, true, 1.5);
assert.equal(rate, 1.5);
assert.equal(state, 1);
context.document.hidden = true; visibility();
assert.equal(state, 2, 'Hidden video must stop playing');
context.spitifyStop();
assert.equal(calls.at(-1), 'destroy', 'Closing video releases the embed');
console.log('Video controls: mute before play, seek, pause, speed, background and teardown passed.');
