const fs=require('node:fs'),path=require('node:path');
const C=require(process.env.ROOM_CANVAS_MODULE||'@napi-rs/canvas');
const root=path.resolve(__dirname,'../app/src/main/assets/cafe'),out=path.resolve(__dirname,'../audit/v0181');
fs.mkdirSync(out,{recursive:true});
async function load(base){
 const env=JSON.parse(fs.readFileSync(path.join(base,'environment.json'))),meta=JSON.parse(fs.readFileSync(path.join(base,'animation_data.json'))),images={};
 for(const f of [...env.assets.map(a=>a.sprite),...env.tiles.map(t=>t.path),meta.atlas])images[f]=await C.loadImage(path.join(base,f));
 return {env,meta,images};
}
(async()=>{
 const data=await load(root),E=require(path.join(root,'engine.js')),R=require(path.join(root,'renderer.js'));
 const renderer=new R.Renderer(data.env,data.meta,data.images,C.createCanvas),board=C.createCanvas(1472,640),bg=board.getContext('2d'),tile=C.createCanvas(368,320),ctx=tile.getContext('2d');
 for(const [i,face] of ['n','ne','e','se','s','sw','w','nw'].entries()){
  const game=new E.Game(data.env,data.meta);game.actor.at=[2.1,2.5];game.actor.face=face;game.actor.flip=E.mirror(face);
  game.actor.clip=data.meta.direction_clips[face]||(E.rear(face)?data.meta.movement_modes.run.nw:data.meta.movement_modes.run.sw);game.actor.frame=1;
  renderer.render(ctx,game,368,320,{ambient:false,zoom:1.8,focus:game.world.project(game.actor.at).map((v,i)=>i?v-45:v)});
  bg.drawImage(tile,i%4*368,Math.floor(i/4)*320);bg.fillStyle='#51473d';bg.font='18px sans-serif';bg.fillText(face.toUpperCase(),i%4*368+12,Math.floor(i/4)*320+25);
 }
 fs.writeFileSync(path.join(out,'eight-facings.png'),board.toBuffer('image/png'));
 function bench(Renderer,Engine,d,fps){
  const r=new Renderer(d.env,d.meta,d.images,C.createCanvas),g=new Engine.Game(d.env,d.meta),canvas=C.createCanvas(1280,1113),context=canvas.getContext('2d');
  const start=performance.now();for(let i=0;i<120;i++){g.update(1000/fps);r.render(context,g,1280,1113,{ambient:true});}
  return {draws:120,milliseconds:Math.round(performance.now()-start),backgroundRebuilds:r.sceneBuilds??120,softwareCanvasOnly:true};
 }
 const result={updatedIdle:bench(R.Renderer,E,data,8),updatedMovingBudget:bench(R.Renderer,E,data,24),physicalPhonePowerMeasured:false};
 const baseline=path.resolve(__dirname,'Folio_Miso_Handoff/pixel-cat-experiment/production/playful-room-playable-v2');
 if(fs.existsSync(baseline))result.previousIdle=bench(require(path.join(baseline,'source/renderer.js')).Renderer,require(path.join(baseline,'source/engine.js')),await load(baseline),30);
 fs.writeFileSync(path.join(out,'renderer-work.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1;});
