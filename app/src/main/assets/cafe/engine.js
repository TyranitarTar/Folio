(function(root,factory){const api=factory();if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.LoftGame=api;})(typeof window==='undefined'?globalThis:window,function(){'use strict';
 const DIR={sw:[0,1],nw:[-1,0],ne:[0,-1],se:[1,0]},Q=8,RADIUS=.156,EPS=1e-6;
 const clamp=(x,a=0,b=1)=>Math.min(b,Math.max(a,x)),mix=(a,b,t)=>a.map((v,i)=>v+(b[i]-v)*t),dist=(a,b)=>Math.hypot(a[0]-b[0],a[1]-b[1]),same=(a,b)=>dist(a,b)<EPS;
 const rear=f=>f==='nw'||f==='ne',mirror=f=>f==='ne'||f==='se';
 const JUMP_WINDOWS={jump:[220,550],jump_nw:[250,510]};
 function jumpTime(clip,total,p){const [lift,land]=JUMP_WINDOWS[clip];return p<.18?lift*p/.18:p<.82?lift+(land-lift)*(p-.18)/.64:land+(total-land)*(p-.82)/.18;}
 const heading=(a,b)=>Math.abs(b[0]-a[0])>Math.abs(b[1]-a[1])?(b[0]>a[0]?'se':'nw'):(b[1]>a[1]?'sw':'ne');
 const TURN={sw:[{to:'nw',clip:'turn'},{to:'se',clip:'turn_front'}],nw:[{to:'sw',clip:'turn',reverse:true},{to:'ne',clip:'turn_back'}],ne:[{to:'nw',clip:'turn_back',reverse:true},{to:'se',clip:'turn',reverse:true,flip:true}],se:[{to:'ne',clip:'turn',flip:true},{to:'sw',clip:'turn_front',reverse:true}]};
 function turnRoute(from,to){const q=[{f:from,edges:[]}],seen=new Set([from]);while(q.length){const n=q.shift();if(n.f===to)return n.edges;for(const e of TURN[n.f])if(!seen.has(e.to)){seen.add(e.to);q.push({f:e.to,edges:[...n.edges,e]});}}throw Error('Invalid facing');}
 function frameAt(c,time,loop=false,reverse=false){const ds=reverse?[...c.durations_ms].reverse():c.durations_ms;let t=loop?time%c.total_ms:Math.min(c.total_ms-.001,time),i=0;while(i<ds.length-1&&t>=ds[i])t-=ds[i++];return reverse?ds.length-1-i:i;}
 class Heap{constructor(){this.a=[];}push(n){let i=this.a.push(n)-1;while(i){let p=(i-1)>>1;if(this.a[p].score<=n.score)break;this.a[i]=this.a[p];i=p;}this.a[i]=n;}pop(){const out=this.a[0],last=this.a.pop();if(this.a.length){let i=0;while(i*2+1<this.a.length){let c=i*2+1;if(c+1<this.a.length&&this.a[c+1].score<this.a[c].score)c++;if(last.score<=this.a[c].score)break;this.a[i]=this.a[c];i=c;}this.a[i]=last;}return out;}get length(){return this.a.length;}}
 class World{
  constructor(env){this.env=env;this.props=new Map(env.assets.map(a=>[a.id,a]));this.obstacles=env.assets.filter(a=>a.surface==='floor'&&a.solid&&!['bowls','ball_track'].includes(a.id));}
  project(p,z=0){return [this.env.origin[0]+(p[0]-p[1])*64,this.env.origin[1]+(p[0]+p[1])*32-z];}
  inverse(p){const dx=(p[0]-this.env.origin[0])/64,dy=(p[1]-this.env.origin[1])/32;return [(dx+dy)/2,(dy-dx)/2];}
  local(id,p){const a=this.props.get(id),at=this.project(a.pos,a.elevation);return [at[0]+p[0]-a.anchor[0],at[1]+p[1]-a.anchor[1]];}
  surface(id,p){const a=this.props.get(id),dx=(p[0]-a.anchor[0])/128;return {at:[a.pos[0]+dx,a.pos[1]-dx],z:a.elevation+a.anchor[1]-p[1]};}
  clear(p,ignore=[]){if(p.some(x=>x<RADIUS||x>5-RADIUS))return false;return !this.obstacles.some(a=>!ignore.includes(a.id)&&Math.abs(p[0]-a.pos[0])<a.footprint[0]/2+RADIUS-EPS&&Math.abs(p[1]-a.pos[1])<a.footprint[1]/2+RADIUS-EPS);}
  segment(a,b,ignore=[]){const n=Math.max(1,Math.ceil(dist(a,b)*40));for(let i=0;i<=n;i++)if(!this.clear(mix(a,b,i/n),ignore))return false;return true;}
  nearest(p,maxDistance=.6){let best=null,bestD=Infinity;for(let x=1;x<40;x++)for(let y=1;y<40;y++){const q=[x/Q,y/Q],d=dist(q,p);if(d<bestD&&d<=maxDistance&&this.clear(q)){best=q;bestD=d;}}return best;}
  path(start,goal,face='sw'){
   if(!this.clear(start)||!this.clear(goal))return null;if(same(start,goal))return [];
   const grid=p=>{const a=p.map(v=>Math.round(v*Q)/Q),cs=[];for(let x=-1;x<=1;x++)for(let y=-1;y<=1;y++){const q=[a[0]+x/Q,a[1]+y/Q];if(this.clear(q)&&this.segment(p,q))cs.push(q);}cs.sort((a,b)=>dist(a,p)-dist(b,p));return cs[0];};
   const begin=grid(start),end=grid(goal);if(!begin||!end)return null;
   const key=(p,f)=>p.join(',')+','+f,heur=p=>Math.abs(p[0]-end[0])+Math.abs(p[1]-end[1]),open=new Heap(),best=new Map();
   const first={p:begin,f:face,g:0,score:heur(begin),prev:null};open.push(first);best.set(key(begin,face),0);let found=null,visited=0;
   while(open.length&&visited++<30000){const n=open.pop();if(n.g>(best.get(key(n.p,n.f))??Infinity)+EPS)continue;if(same(n.p,end)){found=n;break;}for(const [f,d] of Object.entries(DIR)){const p=[n.p[0]+d[0]/Q,n.p[1]+d[1]/Q];if(!this.segment(n.p,p))continue;const g=n.g+1/Q+(f===n.f?0:.16),k=key(p,f);if(g>=(best.get(k)??Infinity)-EPS)continue;best.set(k,g);open.push({p,f,g,score:g+heur(p),prev:n});}}
   if(!found)return null;const points=[];for(let n=found;n;n=n.prev)points.unshift(n.p);if(!same(start,points[0]))points.unshift(start);if(!same(goal,points.at(-1)))points.push(goal);
   const runs=[];let a=points[0];for(const b of points.slice(1)){if(same(a,b))continue;const f=heading(a,b),last=runs.at(-1);if(last&&last.face===f&&Math.abs((b[0]-a[0])*(a[1]-last.from[1])-(b[1]-a[1])*(a[0]-last.from[0]))<EPS)last.to=b;else runs.push({from:a,to:b,face:f});a=b;}return runs;
  }
 }
 const ACTIONS={
  sofa:{label:'Sofa nap',prop:'sofa',hint:'Jump onto the seat and curl up.'},
  eat:{label:'Eat · front',prop:'bowls',hint:'Approach the food from the front of the tray.'},drink:{label:'Drink · front',prop:'bowls',hint:'Drink from the front of the water bowl.'},
  eat_far:{label:'Eat · back',prop:'bowls',hint:'Walk around the tray to eat from the back.'},drink_far:{label:'Drink · back',prop:'bowls',hint:'Walk around the tray to drink from the back.'},
  tunnel:{label:'Run through',prop:'tunnel',hint:'Race through the purple tunnel.'},
  scratch:{label:'Scratch post',prop:'cat_tower',hint:'Stretch up and scratch the sisal post.'},step:{label:'Lower step',prop:'cat_tower',hint:'Hop onto the small platform first.'},perch:{label:'Top perch',prop:'cat_tower',hint:'Hop to the small platform, then to the top.'},
  perch_sleep:{label:'Perch nap',prop:'cat_tower',hint:'Use both steps, then curl up on the top perch.'},perch_groom:{label:'Perch groom',prop:'cat_tower',hint:'Sit and groom on the top perch.'},perch_watch:{label:'Look around',prop:'cat_tower',hint:'Watch the room from the top perch.'},
  bookshelf:{label:'Bookshelf leap',prop:'cubby_books',hint:'Climb the perch, then jump onto the book cubby.'},
  bookshelf_sleep:{label:'Bookshelf nap',prop:'cubby_books',hint:'Leap from the top perch and sleep on the bookshelf.'},
  box:{label:'Hop into box',prop:'box',hint:'Jump inside and peek over the cardboard rim.'},
  ball:{label:'Bat the ball',prop:'ball_track',hint:'Paw at the rolling ball toy.'},feather:{label:'Pounce',prop:'feather',hint:'Pounce beside the feather wand.'},
  groom:{label:'Groom on rug',prop:'rug',hint:'A slow sit, lick and face wash.'},sleep:{label:'Rug nap',prop:'rug',hint:'Settle down for a quiet nap.'},
  window:{label:'Window watch',prop:'window',hint:'Jump onto the sill and watch outside.'},door:{label:'Investigate',prop:'door',hint:'Sniff around the closed door.'}
 };
 class Plan{
  constructor(game){this.game=game;this.world=game.world;this.meta=game.meta;this.at=[...game.actor.at];this.z=game.actor.z;this.face=game.actor.face;this.support=game.actor.support;this.resting=game.actor.resting;this.steps=[];}
  turn(to){for(const e of turnRoute(this.face,to))this.steps.push({type:'turn',clip:e.clip,duration:this.meta.clips[e.clip].total_ms,flip:e.flip||false,reverse:e.reverse||false,faceAfter:e.to});this.face=to;}
  walk(to,pace=this.game.pace){const route=this.world.path(this.at,to,this.face);if(!route)return false;const gait=this.meta.movement_modes[pace];for(const r of route){this.turn(r.face);const pixels=dist(this.world.project(r.from),this.world.project(r.to));this.steps.push({type:'walk',clip:rear(r.face)?gait.nw:gait.sw,flip:mirror(r.face),from:r.from,to:r.to,z0:0,z1:0,duration:Math.max(60,pixels/(gait.speed*this.game.scale)*1000),loop:true,faceAfter:r.face,supportAfter:'floor'});this.at=[...r.to];}return true;}
  pose(clip,duration=null,face='sw',extra={}){this.turn(face);this.steps.push({type:'pose',clip,duration:duration??this.meta.clips[clip].total_ms,flip:mirror(face),loop:!!duration,...extra});}
  jump(target,support,extra={}){const f=heading(this.at,target.at);this.turn(f);const clip=rear(f)?'jump_nw':'jump';this.steps.push({type:'jump',clip,duration:1100,from:[...this.at],to:[...target.at],z0:this.z,z1:target.z,apex:Math.max(32,Math.abs(target.z-this.z)*.6+20),flip:mirror(f),faceAfter:f,supportAfter:support,...extra});this.at=[...target.at];this.z=target.z;this.support=support;}
  wake(){if(this.resting){this.pose('curl_up',null,'sw',{restingAfter:null});this.resting=null;}}
  settle(){this.pose('curl_down',null,'sw',{restingAfter:'sleep'});this.resting='sleep';}
  floor(){this.wake();if(this.support==='floor')return true;if(this.support==='box')this.pose('sit_up',null,'sw',{occlusion:'box'});if(this.support==='bookshelf')this.jump(this.game.platforms.perch,'perch');if(this.support==='perch'){this.jump(this.game.platforms.step,'step');this.pose(rear(this.face)?'idle_nw':'idle',180,this.face);}if(this.support==='step'){this.jump({at:this.game.approach.step,z:0},'floor');return true;}const s=this.support,a=this.game.approach[s];if(!a)return false;this.jump({at:a,z:0},'floor',s==='box'?{occlusion:'box',exiting:true}:{});return true;}
  platform(name){this.wake();if(this.support===name)return true;
   if(name==='bookshelf'){if(!this.platform('perch'))return false;this.jump(this.game.platforms.bookshelf,'bookshelf');return true;}
   if(name==='perch'){if(this.support==='bookshelf'){this.jump(this.game.platforms.perch,'perch');return true;}if(!this.platform('step'))return false;this.pose(rear(this.face)?'idle_nw':'idle',180,this.face);this.jump(this.game.platforms.perch,'perch');return true;}
   if(name==='step'){if(this.support==='bookshelf')this.jump(this.game.platforms.perch,'perch');if(this.support==='perch'){this.jump(this.game.platforms.step,'step');return true;}if(!this.floor()||!this.walk(this.game.approach.step))return false;this.jump(this.game.platforms.step,'step');return true;}
   if(!this.floor()||!this.walk(this.game.approach[name]))return false;this.jump(this.game.platforms[name],name,name==='box'?{occlusion:'box'}:{});return true;
  }
  action(id){const W=this.world,G=this.game;
   if(['perch_sleep','bookshelf_sleep','perch_groom','perch_watch'].includes(id)){const destination=id==='bookshelf_sleep'?'bookshelf':'perch';if(!this.platform(destination))return false;if(id.endsWith('sleep'))this.settle();else if(id==='perch_groom')this.pose('rest');else this.pose('idle',2400,'sw');return true;}
   if(['sofa','step','perch','bookshelf','box','window'].includes(id)){
    if(this.support===id&&['sofa','box'].includes(id))return true;
    if(!this.platform(id))return false;
    if(id==='sofa')this.settle();else if(id==='box')this.pose('sit_down',null,'sw',{occlusion:'box'});else this.pose(rear(this.face)?'idle_nw':'idle',1000,this.face);
    return true;
   }
   if(!this.floor())return false;
   if(['eat','drink','eat_far','drink_far'].includes(id)){const far=id.endsWith('_far'),kind=id.startsWith('eat')?'eat':'drink',face=far?'sw':'ne',clip=far?kind:kind+'_nw',contact=W.local('bowls',kind==='eat'?[147,284]:[175,299]),mouth=this.meta.feeding_mouths?.[clip]??[12,47],dx=(mouth[0]-31)*G.scale*(mirror(face)?-1:1),dy=(mouth[1]-55)*G.scale,root=W.inverse([contact[0]-dx,contact[1]-dy]);if(!this.walk(root))return false;this.pose(clip,null,face,{effect:'feeding'});return true;}
   if(id==='scratch'){const p=W.local('cat_tower',[170,267]),stand=W.inverse([p[0]+20*G.scale,p[1]+29*G.scale]).map(v=>v-.125);if(!this.walk(stand))return false;this.pose('scratch_nw',5200,'nw',{effect:'scratch'});return true;}
   if(id==='tunnel'){if(!this.walk([1.5,4.5]))return false;this.pose('tunnel_enter',null,'se');const from=[...this.at],to=[4,4.5];this.steps.push({type:'walk',clip:'tunnel_crawl',duration:dist(W.project(from),W.project(to))/(30*G.scale)*1000,from,to,z0:0,z1:0,flip:true,loop:true,occlusion:'tunnel',faceAfter:'se',supportAfter:'floor',ignore:['tunnel']});this.at=to;this.pose('tunnel_exit',null,'se');return true;}
   if(id==='ball'){const p=W.local('ball_track',[181,288]),stand=W.inverse([p[0]+19,p[1]+10]);if(!this.walk(stand))return false;this.pose('pounce',null,'sw',{effect:'ball',hop:6});this.pose('sniff',null,'sw',{effect:'ball'});return true;}
   if(id==='feather'){const p=W.local('feather',[167,306]),stand=W.inverse([p[0]+20,p[1]+9]);if(!this.walk(stand))return false;this.pose('pounce',null,'sw',{effect:'feather',hop:12});return true;}
   if(id==='groom'||id==='sleep'){if(!this.walk([2.1,2.5]))return false;if(id==='sleep')this.settle();else this.pose('rest');return true;}
   if(id==='door'){if(!this.walk([4.25,.5]))return false;this.pose('sniff',null,'se');return true;}
   return false;
  }
 }
 class Game{
  constructor(env,meta){this.env=env;this.meta=meta;this.scale=env.cat_scale??1.3;this.world=new World(env);this.platforms={sofa:this.world.surface('sofa',[162,281]),step:this.world.surface('cat_tower',[126,288]),perch:this.world.surface('cat_tower',[171,211]),bookshelf:this.world.surface('cubby_books',[162,282]),box:this.world.surface('box',[162,312]),window:this.world.surface('window',[175,328])};this.approach={sofa:[1.625,1.5],step:[1.5,4],perch:[1.5,4],box:[4,2.5],window:[.75,1.875]};this.reset();}
  reset(){this.actor={at:[2,2.75],z:0,face:'sw',support:'floor',clip:'idle',frame:0,flip:false,occlusion:null,progress:0,effect:null,resting:null};this.queue=[];this.step=null;this.elapsed=0;this.time=0;this.idle=0;this.pace='run';this.pending=null;this.cancelAtBoundary=false;this.history=[];this.counts={};this.action=null;this.mark=null;this.notice='Tap a furnishing or a patch of floor.';this.lamp=true;this.auto=false;this.autoIndex=0;}
  get busy(){return !!this.step;}
  start(plan,id){if(!plan.steps.length)return false;this.queue=plan.steps;this.action=id;this.next();return true;}
  command(id,target=null){if(!ACTIONS[id]&&!['move','down'].includes(id))return false;if(this.busy){this.pending={id,target};this.notice='Next: '+(ACTIONS[id]?.label??'move there');return true;}const p=new Plan(this);let ok;
   if(id==='move'){const goal=this.world.nearest(target);ok=goal&&p.floor()&&p.walk(goal);if(ok)this.mark=goal;}
   else if(id==='down'){ok=p.floor();}
   else ok=p.action(id);
   if(!ok){this.notice='That route is blocked. Try another spot.';return false;}
   if(!p.steps.length){this.notice='Miso is already settled here.';return true;}
   this.notice=id==='move'?'Miso is on the way.':id==='down'?'Hopping down.':ACTIONS[id].hint;this.history.push({event:'command',id,time:this.time});return this.start(p,id);
  }
  stop(){this.auto=false;this.pending=null;if(this.busy){this.cancelAtBoundary=true;this.notice='Miso will stop safely after this step.';}}
  next(){this.step=this.queue.shift()??null;this.elapsed=0;
   if(this.step){const s=this.step;this.actor.clip=s.clip;this.actor.flip=s.flip??mirror(this.actor.face);this.actor.frame=s.reverse?this.meta.clips[s.clip].frames.length-1:0;this.actor.effect=s.effect||null;this.actor.occlusion=s.occlusion||null;this.actor.progress=0;this.history.push({event:'step',type:s.type,clip:s.clip,time:this.time});}
   else{if(this.action){this.counts[this.action]=(this.counts[this.action]||0)+1;this.history.push({event:'complete',id:this.action,time:this.time});}this.action=null;this.mark=null;this.actor.occlusion=this.actor.support==='box'?'box':null;this.actor.effect=null;this.actor.progress=1;this.idle=0;this.setIdle();this.notice='Miso is settled. Tap somewhere to explore.';}
  }
  setIdle(){const a=this.actor;if(a.support==='box'){a.clip='rest';a.frame=2;a.flip=false;}else if(a.resting==='sleep'){a.clip='sleep';a.frame=7;a.flip=false;}else{a.clip=rear(a.face)?'idle_nw':'idle';a.frame=frameAt(this.meta.clips[a.clip],this.idle,true);a.flip=mirror(a.face);}}
  update(ms){let remaining=Math.max(0,ms);this.time+=remaining;
   while(this.step&&remaining>0){const s=this.step,dt=Math.min(remaining,s.duration-this.elapsed);remaining-=dt;this.elapsed+=dt;const p=clamp(this.elapsed/s.duration),a=this.actor,c=this.meta.clips[s.clip];a.progress=p;a.frame=frameAt(c,s.type==='jump'?jumpTime(s.clip,c.total_ms,p):this.elapsed,s.loop,s.reverse);
    if(s.from){let t=p;if(s.type==='jump'){t=clamp((p-.18)/.64);a.z=s.z0+(s.z1-s.z0)*t+4*s.apex*t*(1-t);}else a.z=s.z0??0;a.at=mix(s.from,s.to,t);}
    else if(s.hop){const t=clamp((this.elapsed-520)/300);a.z=4*s.hop*t*(1-t);}
    if(this.elapsed>=s.duration-EPS){if(s.to)a.at=[...s.to];a.z=s.z1??this.platforms[a.support]?.z??0;if(s.supportAfter)a.support=s.supportAfter;if(s.faceAfter)a.face=s.faceAfter;if('restingAfter'in s)a.resting=s.restingAfter;if(this.cancelAtBoundary){this.queue=[];this.cancelAtBoundary=false;this.action=null;}this.next();}
   }
   if(!this.step){this.idle+=remaining;this.setIdle();if(this.pending){const next=this.pending;this.pending=null;this.command(next.id,next.target);}else if(this.auto&&this.idle>7000){const actions=['groom','eat','perch','bookshelf','drink','tunnel','box','feather','window','sofa'];this.command(actions[this.autoIndex++%actions.length]);}}
  }
  snapshot(){return JSON.parse(JSON.stringify({actor:this.actor,action:this.action,busy:this.busy,notice:this.notice,time:this.time,counts:this.counts}));}
 }
 return {Game,World,Plan,ACTIONS,DIR,RADIUS,Q,turnRoute,frameAt,heading,mirror,rear,clamp};
});
