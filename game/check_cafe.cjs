// Exercise the shipped engine, director and Android host controller with real Canvas.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../app/src/main/assets/cafe');
const E = require(path.join(root, 'engine.js')), D = require(path.join(root, 'director.js'));
const read = n => JSON.parse(fs.readFileSync(path.join(root, n), 'utf8'));
const env = read('environment.json'), meta = read('animation_data.json');
const C = require(process.env.ROOM_CANVAS_MODULE || '@napi-rs/canvas');
function game() { return new E.Game(env, meta); }
function finish(g) { let i=0; while(g.busy || g.pending) { assert(i++ < 20000); g.update(50); } }
for (const [mode, actions] of Object.entries(D.routines)) {
  const g=game(), d=new D.Director(g, E.ACTIONS); d.setMode(mode);
  for (const id of [...actions, actions[0]]) {
    d.update(7001); assert.equal(g.history.filter(h=>h.event==='command').at(-1).id, id); finish(g);
  }
}
const g=game(), random=new D.Director(g, E.ACTIONS, ()=>.25);
random.update(7001); const first=g.action; finish(g); random.update(7001); assert.notEqual(g.action, first);
// Reconfiguring an unchanged mode must never discard a command queued during a leap.
const pending=game(), director=new D.Director(pending, E.ACTIONS);
director.command('perch'); while(pending.step.type!=='jump') pending.update(50);
director.command('drink'); director.setMode('manual'); assert.equal(pending.pending.id, 'drink'); finish(pending); assert.equal(pending.counts.drink,1);
director.command('bookshelf'); while(pending.step?.type!=='jump') pending.update(50);
director.stop(); finish(pending); assert(pending.actor.z === pending.platforms[pending.actor.support].z);
const long=game(), bounded=new D.Director(long,E.ACTIONS); long.history=Array(1000).fill({event:'old'}); bounded.update(1); assert.equal(long.history.length,256);

async function main() {
  const canvas=C.createCanvas(300,150), events={}, docEvents={}, raf=new Map(), timers=new Map(), messages={}; let next=0;
  canvas.getBoundingClientRect=()=>({left:0,top:0,width:360,height:360});
  canvas.addEventListener=(id,fn)=>events[id]=fn; canvas.removeEventListener=id=>delete events[id];
  let disconnected=false;
  const context={console, Math, JSON, Object, Set, Promise, Image:C.Image, devicePixelRatio:3,
    document:{hidden:false, getElementById:id=>id==='room'?canvas:{hidden:false,textContent:''},
      createElement:()=>C.createCanvas(1,1),addEventListener:(id,fn)=>docEvents[id]=fn,removeEventListener:id=>delete docEvents[id]},
    addEventListener:(id,fn)=>events[id]=fn,removeEventListener:id=>delete events[id],
    ResizeObserver:class{observe(){}disconnect(){disconnected=true;}},
    requestAnimationFrame:fn=>{const id=++next;raf.set(id,fn);return id;},cancelAnimationFrame:id=>raf.delete(id),
    setTimeout:fn=>{const id=++next;timers.set(id,fn);return id;},clearTimeout:id=>timers.delete(id),
    fetch:async n=>({ok:true,json:async()=>read(n)}),
    FolioRoom:Object.fromEntries(['status','discoveries','pick','failure'].map(id=>[id,data=>(messages[id]||=[]).push(JSON.parse(data))]))
  };
  // Resolve local image URLs as a WebView would, using the real native image decoder.
  context.Image=class extends C.Image { set src(value){super.src=fs.readFileSync(path.join(root,value));} };
  context.window=context; vm.createContext(context);
  for(const name of ['engine.js','renderer.js','director.js','host.js']) vm.runInContext(fs.readFileSync(path.join(root,name),'utf8'),context);
  const host=context.FolioCafe;
  host.configure({counts:{eat:3,drink:-1,box:'2'},mode:'manual'}); host.active(true);
  for(let i=0;i<150&&!host.snapshot().ready;i++) await new Promise(r=>setTimeout(r,10));
  assert(host.snapshot().ready); assert.equal(canvas.width,1080); assert.equal(raf.size+timers.size,1);
  context.devicePixelRatio=4;events.resize();assert.equal(canvas.width,1440);context.devicePixelRatio=3;events.resize();
  assert.equal(host.snapshot().counts.eat,3); assert(!host.snapshot().counts.drink); assert(!host.snapshot().counts.box);
  let now=0; const tick=dt=>{now+=dt;if(timers.size){const [tid,wake]=timers.entries().next().value;timers.delete(tid);wake();}const [id,fn]=raf.entries().next().value;raf.delete(id);fn(now);};
  host.command('perch_sleep'); tick(0); tick(40); const t=host.snapshot().time;
  host.command('pause'); assert.equal(raf.size+timers.size,0); assert.equal(host.snapshot().time,t);
  host.command('pause'); assert.equal(raf.size+timers.size,1);
  for(let i=0;i<1500&&host.snapshot().busy;i++)tick(50);
  assert.equal(host.snapshot().actor.support,'perch'); assert.equal(host.snapshot().actor.resting,'sleep');
  host.active(false); assert.equal(raf.size+timers.size,0); const saved=host.snapshot().time;
  host.active(true);host.active(true);assert.equal(raf.size+timers.size,1);tick(100000);assert.equal(host.snapshot().time,saved);
  host.command('bookshelf_sleep');for(let i=0;i<1500&&host.snapshot().busy;i++)tick(50);
  assert.equal(host.snapshot().actor.support,'bookshelf');
  host.command('down');for(let i=0;i<1500&&host.snapshot().busy;i++)tick(50);
  assert.equal(host.snapshot().actor.support,'floor'); assert(!host.snapshot().actor.resting);
  for (const id of ['eat','drink','eat_far','drink_far','tunnel','window','box','sofa']) {
    host.command(id); for(let i=0;i<2000&&host.snapshot().busy;i++)tick(50);
    assert(!host.snapshot().busy,id); assert(host.snapshot().counts[id]>0,id);
  }
  context.document.hidden=true;docEvents.visibilitychange();assert.equal(raf.size+timers.size,0);
  context.document.hidden=false;docEvents.visibilitychange();assert.equal(raf.size+timers.size,1);
  assert(messages.discoveries.some(v=>v.tunnel===1));
  host.destroy();assert.equal(raf.size+timers.size,0);assert(disconnected);assert.equal(canvas.width,1);assert(!events.pointerup);
  assert(!messages.failure);console.log('PASS: four repeating routines; random choice; queued actions; safe stop; bounded history; shipped host loading, DPR, counts, pause, background/resume, support routes, feeding, tunnel, visibility and cleanup.');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
