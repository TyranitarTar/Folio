const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../app/src/main/assets/cafe'),E=require(path.join(root,'engine.js'));
const env=JSON.parse(fs.readFileSync(path.join(root,'environment.json'))),meta=JSON.parse(fs.readFileSync(path.join(root,'animation_data.json')));
const open=new E.World({...env,assets:[]}),facings=new Set();
for(let i=0;i<72;i++){
 const p=[2.5+Math.cos(i*Math.PI/36),2.5+Math.sin(i*Math.PI/36)],route=open.path([2.5,2.5],p);
 assert.equal(route.length,1);assert.deepEqual(route[0].to,p);facings.add(route[0].face);
}
assert.equal(facings.size,8);
const world=new E.World(env);
assert(!world.segment([2.7679687499999996,.9476562499999996],[2,3.5]),'Thin corner collision cannot be skipped');
let routes=0;
for(let x=.5;x<5;x+=.5)for(let y=.5;y<5;y+=.5){
 const to=[x,y];if(!world.clear(to))continue;
 const route=world.path([2,2.75],to);if(!route)continue;routes++;
 for(const run of route)for(let j=0;j<=200;j++)assert(world.clear(run.from.map((v,k)=>v+(run.to[k]-v)*j/200)));
}
assert(routes>20);
for(const facing of ['n','s','e','w'])assert(meta.clips[meta.direction_clips[facing]].frames.length===6);
console.log(`PASS: 72 arbitrary-angle direct paths, all 8 facings, ${routes} obstacle routes sampled densely, continuous corner collision, six-drawing new directional loops.`);
