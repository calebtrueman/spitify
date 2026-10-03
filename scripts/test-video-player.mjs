import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../shared/video/music-video.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const calls = [], reports = [], messages = [];
let options, position = 0, state = -1, rate = 1, now = 10000, visibility, tick;
const stage = {clientWidth:400, clientHeight:800};
const frame = {style:{}, contentWindow:{postMessage:(message,origin) => messages.push([message,origin])}};
const events = {};
const mock = {
  unloadModule:name => calls.push(['unload',name]), setOption:(...args) => calls.push(['option',...args]),
  setSize:(w,h) => calls.push(['size',w,h]), mute:() => calls.push('mute'), setVolume:v => calls.push(['volume',v]),
  getCurrentTime:() => position, getDuration:() => 240, getPlaybackRate:() => rate,
  setPlaybackRate:v => {rate=v;calls.push(['rate',v]);}, getPlayerState:() => state,
  playVideo:() => {state=1;calls.push('play');}, pauseVideo:() => {state=2;calls.push('pause');},
  seekTo:v => {position=v;calls.push(['seek',v]);}, stopVideo:() => calls.push('stop'), destroy:() => calls.push('destroy')
};
const context = vm.createContext({console:{log:value => reports.push(value)}, Date:{now:() => now},
  setInterval:fn => {tick=fn;return 1;}, clearInterval:() => {tick=null;}, location:{origin:'https://com.calebtrueman.spitify'},
  document:{hidden:false,getElementById:id => id==='stage'?stage:frame,addEventListener:(_,fn) => visibility=fn},
  window:{addEventListener:(name,fn) => events[name]=fn}, YT:{Player:function(_,config){options=config;return mock;}}
});
vm.runInContext(source,context);
context.spitifySync(42,true,1.5,false);
assert.equal(calls.length,0);
context.onYouTubeIframeAPIReady(); options.events.onReady();
assert.equal(calls[0],'mute');
assert.ok(calls.indexOf('play')>calls.findIndex(v=>Array.isArray(v)&&v[0]==='volume'&&v[1]===0));
assert.equal(options.playerVars.controls,0);
assert.equal(options.playerVars.cc_load_policy,0);
assert.equal(frame.style.width,'400px'); assert.equal(frame.style.height,'800px');
assert.equal(reports.at(-1),'SPITIFY_VIDEO_WAITING','Player-ready must not reveal loading UI before a decoded frame');
assert.notEqual(position,42,'The visual is a short clip, not a copy of the music seek position');
const firstStart=position;
context.spitifySync(190,true,2,false);
assert.equal(position,firstStart); assert.equal(rate,1);
const surface=data=>events.message({origin:'https://www.youtube.com',source:frame.contentWindow,data:{spitifyVideoSurface:true,...data}});
events.message({origin:'https://wrong.example',source:frame.contentWindow,data:{spitifyVideoSurface:true,frameReady:true}});
assert.notEqual(frame.style.opacity,'1');
surface({frameReady:true}); assert.equal(frame.style.opacity,'1'); assert.equal(reports.at(-1),'SPITIFY_VIDEO_READY');
const plan=context.spitifyMakeLoop(240,'test-video');
assert.equal(plan.length,2); assert.equal(plan.reduce((sum,s)=>sum+s.length,0),7);
assert.ok(plan.every(s=>s.start>20&&s.start+s.length<215),'Intros and credits are outside the chosen clips');
assert.equal(JSON.stringify(plan),JSON.stringify(context.spitifyMakeLoop(240,'test-video')),'A song keeps the same loop');
for(const duration of [1,8,16,20,120]) assert.ok(context.spitifyMakeLoop(duration,'x').every(s=>s.start>=0&&s.start+s.length<=duration));
assert.equal(context.spitifyMakeLoop(0,'x').length,0);
position=firstStart+3.7; now+=4000; tick(); const secondStart=position;
assert.ok(secondStart>firstStart);
position=secondStart+3.5; now+=4000; tick(); assert.equal(position,firstStart,'Two cuts return to their first shot');
context.spitifySync(90,false,1,false); const held=position;
now+=5000; tick(); assert.equal(state,2); assert.equal(position,held,'Pausing holds the last frame without seeking');
assert.equal(messages.at(-1)[0].spitifyCanvas.paused,true);
context.spitifySync(90,false,1,true); assert.equal(messages.at(-1)[0].spitifyCanvas.reduceMotion,true);
context.spitifySync(90,true,1,false); context.document.hidden=true; visibility(); assert.equal(state,2);
context.document.hidden=false; visibility(); assert.equal(state,1);
stage.clientWidth=1200;stage.clientHeight=600;context.spitifyResize(); assert.equal(frame.style.width,'1200px');assert.equal(frame.style.height,'600px');
surface({frameReady:false});assert.equal(frame.style.opacity,'0');assert.equal(reports.at(-1),'SPITIFY_VIDEO_WAITING');
context.spitifyStop(); assert.equal(calls.at(-1),'destroy');assert.equal(tick,null);
const playsBeforeHidden=calls.filter(v=>v==='play').length;
context.document.hidden=true;state=-1;context.onYouTubeIframeAPIReady();options.events.onReady();
assert.equal(calls.filter(v=>v==='play').length,playsBeforeHidden,'Hidden preloads do not run video');
context.document.hidden=false;visibility();assert.equal(state,1,'A paused preload decodes its first frame when it becomes visible');
surface({frameReady:true});assert.equal(state,2,'A paused preload freezes immediately after that decoded frame');
context.spitifyStop();
console.log('Video loop: frame gate, muted and hidden bootstrap, viewport sizing, two cuts, no song sync, pause, reduced motion, background and teardown passed.');

const controls=fs.readFileSync(new URL('../shared/video/video-controls.js',import.meta.url),'utf8');
assert.match(controls,/body \*\{visibility:hidden!important/);
assert.match(controls,/visibility', wanted, 'important'/,'New and changed overlay elements stay hidden');
assert.match(controls,/requestVideoFrameCallback/);
assert.match(controls,/getImageData/,'Baked letterbox bars are sampled when the host permits it');
assert.match(controls,/cropMatches >= 3/,'A single dark frame cannot change cropping');
assert.match(controls,/prefers-reduced-motion:reduce/);
assert.match(controls,/spitify-held-frame/);
assert.match(controls,/\['width','100vw'\],\['height','100vh'\]/,'Video pixels use the viewport, not a short embed container');
assert.match(controls,/\.ad-showing/,'Ad frames must stay hidden');
console.log('Video surface: overlay lockdown, frame gate, viewport override, measured letterbox crop and quiet pause motion checks passed.');
