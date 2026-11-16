(function(root,factory){const api=factory();if(typeof module!=='undefined'&&module.exports)module.exports=api;else root.LoftRender=api;})(typeof window==='undefined'?globalThis:window,function(){'use strict';
 const REGIONS={high_shelf:[{box:[220,309,45,44],pivot:[230,309],amplitude:.035,period:3500}],cubby_plant:[{box:[180,302,25,37],pivot:[184,302],amplitude:.045,period:4100}],cat_tower:[{box:[187,219,23,40],pivot:[197,219],amplitude:.06,period:2700}],feather:[{box:[164,280,49,37],pivot:[172,309],amplitude:.025,period:3100}]};
 // Ground hulls are registered to the actual prop bitmap, not its padded cell
 // centre. Heights turn each opaque prop pixel into a bounded isometric ray.
 const VOLUMES={bowls:{ground:[[110,296],[147,278],[213,312],[177,330]],height:8},ball_track:{ground:[[109,309],[158,283],[209,309],[158,335]],height:12},sofa:{ground:[[81,286],[140,252],[241,309],[180,341]],height:104},cat_tower:{ground:[[116,308],[159,286],[204,310],[160,333]],height:118},lamp:{ground:[[142,317],[160,308],[179,318],[160,327]],height:130},box:{ground:[[112,308],[160,283],[208,308],[160,336]],height:52},tunnel:{ground:[[89,301],[115,286],[213,334],[187,350]],height:50}};
 const BOX_FRONT=[[101,279],[159,304],[213,281],[208,312],[160,335],[109,312]],SOFA_ARM=[[208,252],[248,273],[248,315],[215,338],[208,283]];
 function contains(poly,x,y){let inside=false;for(let i=0,j=poly.length-1;i<poly.length;j=i++){const a=poly[i],b=poly[j];if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])inside=!inside;}return inside;}
 function spans(poly,width){return Array.from({length:width},(_,x)=>{const at=x+.5,ys=[];for(let i=0;i<poly.length;i++){const a=poly[i],b=poly[(i+1)%poly.length];if((a[0]<=at&&b[0]>at)||(b[0]<=at&&a[0]>at))ys.push(a[1]+(at-a[0])*(b[1]-a[1])/(b[0]-a[0]));}return ys.length>=2?[Math.min(...ys),Math.max(...ys)]:null;});}
 class Renderer{
  constructor(env,meta,images,makeCanvas){this.env=env;this.meta=meta;this.images=images;this.makeCanvas=makeCanvas;this.res=1;this.shell=makeCanvas(1024,768);this.room=makeCanvas(1024,768);this.sceneKey=null;this.sceneBuilds=0;this.props=new Map(env.assets.map(a=>[a.id,a]));this.tiles=new Map(env.tiles.map(a=>[a.id,a]));this.hit=new Map();this.mapping={scale:1,tx:0,ty:0};this.buildShell();
   for(const a of env.assets){const c=makeCanvas(...a.cell),g=c.getContext('2d');g.drawImage(images[a.sprite],0,0,...a.cell);this.hit.set(a.id,g.getImageData(0,0,...a.cell).data);}
   this.spans=new Map(Object.entries(VOLUMES).map(([id,v])=>[id,spans(v.ground,320)]));this.catLayer=makeCanvas(100,100);this.catCtx=this.catLayer.getContext('2d',{willReadFrequently:true});this.coverLayer=makeCanvas(100,100);this.coverData=this.coverLayer.getContext('2d').createImageData(100,100);this.lastOcclusion={};
  }
  project(p,z=0){return [this.env.origin[0]+(p[0]-p[1])*64,this.env.origin[1]+(p[0]+p[1])*32-z];}
  pos(a){const p=this.project(a.pos,a.elevation);return [p[0]-a.anchor[0],p[1]-a.anchor[1]];}
  buildShell(){const g=this.shell.getContext('2d');g.scale(this.res,this.res);g.imageSmoothingEnabled=false;g.fillStyle='#f2ebdf';g.fillRect(0,0,1024,768);const tile=(id,x,y)=>{const t=this.tiles.get(id),p=this.project([x,y]);g.drawImage(this.images[t.path],Math.round(p[0]-t.anchor[0]),Math.round(p[1]-t.anchor[1]),...t.size);};
   for(let i=0;i<5;i++){tile('foundation_left',i,5);tile('foundation_right',5,i);}for(let y=0;y<5;y++)for(let x=0;x<5;x++)tile(['floor_a','floor_b','floor_c'][(x*3+y*7)%3],x,y);for(let i=0;i<5;i++){tile('wall_left',0,i);tile('wall_right',i,0);}for(const [x,y] of [[0,0],[0,5],[5,0]])tile('wall_post',x,y);
  }
  screenToWorld(x,y){const m=this.mapping;return [(x-m.tx)/m.scale,(y-m.ty)/m.scale];}
  pick(x,y,pad=0){const ordered=[...this.env.assets].sort((a,b)=>(b.surface==='floor'?1:0)-(a.surface==='floor'?1:0)||(b.pos[0]+b.pos[1])-(a.pos[0]+a.pos[1]));
   for(const a of ordered){if(a.id==='rug')continue;const p=this.pos(a),ix=Math.floor(x-p[0]),iy=Math.floor(y-p[1]);if(ix>=0&&iy>=0&&ix<a.cell[0]&&iy<a.cell[1]&&this.hit.get(a.id)[(iy*a.cell[0]+ix)*4+3]>40)return a.id;}
   if(pad){let best=null,bestD=Infinity;for(const a of ordered){if(['rug','high_shelf','cubby_plant'].includes(a.id))continue;const p=this.pos(a),b=a.visible_bounds,dx=Math.max(p[0]+b[0]-x,0,x-p[0]-b[2]),dy=Math.max(p[1]+b[1]-y,0,y-p[1]-b[3]),d=Math.hypot(dx,dy);if(d<pad&&d<bestD){best=a.id;bestD=d;}}return best;}return null;
  }
  prop(g,a,game,opt){const [x,y]=this.pos(a),im=this.images[a.sprite],regions=opt.ambient?REGIONS[a.id]||[]:[];g.save();g.translate(Math.round(x),Math.round(y));g.imageSmoothingEnabled=opt.soft;
   if(opt.selected===a.id){g.shadowColor='#b69655aa';g.shadowBlur=7;}
   if(!opt.static&&game.actor.effect==='ball'&&a.id==='ball_track')g.translate(Math.sin(game.time*.026)*1.2,0);
   if(!regions.length)g.drawImage(im,0,0,...a.cell);else{
    g.save();g.beginPath();g.rect(0,0,...a.cell);for(const r of regions)g.rect(...r.box);g.clip('evenodd');g.drawImage(im,0,0,...a.cell);g.restore();
    g.shadowBlur=0;if(!opt.static)for(const r of regions){const angle=Math.sin(game.time*2*Math.PI/r.period)*r.amplitude*(game.actor.effect==='scratch'&&a.id==='cat_tower'?2.4:1);g.save();g.translate(...r.pivot);g.rotate(angle);const [sx,sy,w,h]=r.box;const density=im.width/a.cell[0];g.drawImage(im,sx*density,sy*density,w*density,h*density,sx-r.pivot[0],sy-r.pivot[1],w,h);g.restore();}
   }
   g.shadowBlur=0;
   if(a.id==='bowls'&&opt.ambient&&!opt.static){g.save();g.beginPath();g.ellipse(175,299,12,6,0,0,Math.PI*2);g.clip();const t=(game.time%1700)/1700;g.strokeStyle=`rgba(244,255,249,${.5*(1-t)})`;g.lineWidth=1.2;g.beginPath();g.ellipse(175,299,3+t*10,1.5+t*5,0,0,Math.PI*2);g.stroke();if(game.action==='drink'){g.fillStyle='#fff8d9';g.fillRect(171+Math.round(Math.sin(game.time*.01)*3),296,2,1);}g.restore();}
   g.restore();
  }
  shadow(g,game){const a=game.actor;if(a.occlusion==='tunnel'&&a.progress>.2&&a.progress<.8)return;const platform=game.platforms[a.support],base=platform?.z??0,p=this.project(a.at,base),lift=Math.max(0,a.z-base);g.save();g.fillStyle=`rgba(68,48,44,${Math.max(.07,.2-lift*.001)})`;g.beginPath();g.ellipse(p[0],p[1]+1,Math.max(6,13*game.scale-lift*.025),4*game.scale,0,0,Math.PI*2);g.fill();g.restore();}
  cat(g,game){const a=game.actor,p=this.project(a.at,a.z),r=this.meta.rects[this.meta.clips[a.clip].frames[a.frame]],scale=game.scale,root=[50,76],cg=this.catCtx;cg.setTransform(1,0,0,1,0,0);cg.clearRect(0,0,100,100);cg.save();cg.translate(...root);cg.scale(a.flip?-scale:scale,scale);cg.imageSmoothingEnabled=false;cg.drawImage(this.images[this.meta.atlas],...r,-31,-55,64,64);cg.restore();
   const tr=g.getTransform(),px=(Math.round(p[0]*tr.a+tr.e)-tr.e)/tr.a,py=(Math.round(p[1]*tr.d+tr.f)-tr.f)/tr.d,left=px-root[0],top=py-root[1],catPixels=cg.getImageData(0,0,100,100).data,cover=this.coverData.data,depth=this.project(a.at)[1];cover.fill(0);const candidates=[];this.lastOcclusion={};
   for(const [id,model] of Object.entries(VOLUMES)){const prop=this.props.get(id),at=this.pos(prop),x=Math.round(at[0]),y=Math.round(at[1]),b=prop.visible_bounds;if(left+100<x+b[0]||left>x+b[2]||top+100<y+b[1]||top>y+b[3])continue;candidates.push({id,model,x,y,pixels:this.hit.get(id),spans:this.spans.get(id)});}
   const tunnelAt=this.pos(this.props.get('tunnel'));
   let raw=0,visible=0,headRaw=0,headVisible=0;
   for(let iy=0;iy<100;iy++)for(let ix=0;ix<100;ix++){const index=(iy*100+ix)*4;if(!catPixels[index+3])continue;raw++;const wx=left+ix+.5,wy=top+iy+.5;let alpha=0;
    if(a.occlusion==='tunnel'){const lx=wx-Math.round(tunnelAt[0]),ly=wy-Math.round(tunnelAt[1]),hole=((lx-184)/17)**2+((ly-322)/23)**2<1;if(!(lx<111||lx>194||hole))alpha=255;}
    for(const c of candidates){const lx=Math.floor(wx-c.x),ly=Math.floor(wy-c.y);if(lx<0||ly<0||lx>=320||ly>=384)continue;const pa=c.pixels[(ly*320+lx)*4+3];if(!pa)continue;let hide=false;
     if(c.id==='tunnel'&&a.occlusion==='tunnel'){const hole=((lx-184)/17)**2+((ly-322)/23)**2<1;hide=!hole;}
     else if(c.id==='box'&&(a.support==='box'||game.step?.supportAfter==='box'||a.occlusion==='box'))hide=a.z<53&&contains(BOX_FRONT,lx+.5,ly+.5);
     else{
      if(c.id==='cat_tower'){if(a.effect==='scratch'&&lx>=155&&lx<=185&&ly>=220&&ly<305)continue;if(a.z>=107&&(a.support==='perch'||game.step?.supportAfter==='perch'||game.step?.supportAfter==='bookshelf'))continue;if(a.z>=29&&(a.support==='step'||game.step?.supportAfter==='step')&&lx<165&&ly>=270&&ly<309)continue;}
      if(c.id==='sofa'&&a.z>=35&&(a.support==='sofa'||game.step?.supportAfter==='sofa')&&!contains(SOFA_ARM,lx+.5,ly+.5))continue;
      const span=c.spans[lx];if(!span)continue;let h=c.model.height;
      if(c.id==='cat_tower'){if(lx<165&&ly>=270&&ly<309)h=34;else if(ly>=309&&!(lx>=160&&lx<=181&&ly<313))h=12;}
      if(a.z>h+3)continue;const back=c.y+span[0],front=c.y+span[1],near=Math.max(back,wy),far=Math.min(front,wy+h);hide=far>=near&&far>depth+1;
     }
     if(hide){alpha=255-(255-alpha)*(255-pa)/255;this.lastOcclusion[c.id]=(this.lastOcclusion[c.id]||0)+1;}
    }
    cover[index+3]=Math.round(alpha);const remaining=1-alpha/255;visible+=remaining;const sourceX=31+(ix+.5-root[0])/(scale*(a.flip?-1:1)),sourceY=55+(iy+.5-root[1])/scale;if(sourceX>=5&&sourceX<=30&&sourceY>=24&&sourceY<=54){headRaw++;headVisible+=remaining;}
   }
   this.lastCatStats={rawPixels:raw,visiblePixels:visible,visibleFraction:visible/Math.max(1,raw),headVisibleFraction:headVisible/Math.max(1,headRaw)};const mg=this.coverLayer.getContext('2d');mg.putImageData(this.coverData,0,0);cg.save();cg.globalCompositeOperation='destination-out';cg.drawImage(this.coverLayer,0,0);cg.restore();g.save();g.imageSmoothingEnabled=false;g.drawImage(this.catLayer,left,top);g.restore();
  }
  boxFront(g){const a=this.props.get('box'),p=this.pos(a);g.save();g.translate(...p);g.beginPath();[[101,279],[159,304],[213,281],[208,312],[160,335],[109,312]].forEach(([x,y],i)=>i?g.lineTo(x,y):g.moveTo(x,y));g.closePath();g.clip();g.drawImage(this.images[a.sprite],0,0,...a.cell);g.restore();}
  tunnelFront(g){const a=this.props.get('tunnel'),p=this.pos(a);g.save();g.translate(...p);g.beginPath();g.rect(0,0,...a.cell);g.ellipse(184,322,17,23,.18,0,Math.PI*2);g.clip('evenodd');g.drawImage(this.images[a.sprite],0,0,...a.cell);g.restore();}
  scene(game,opt){const key=[game.lamp,opt.ambient,opt.soft,opt.selected].join('|');if(key!==this.sceneKey){this.sceneKey=key;this.sceneBuilds++;const g=this.room.getContext('2d');g.setTransform(this.res,0,0,this.res,0,0);g.imageSmoothingEnabled=opt.soft;g.drawImage(this.shell,0,0,1024,768);
   if(game.lamp){const a=this.props.get('lamp'),p=this.pos(a),x=p[0]+160,y=p[1]+216,r=g.createRadialGradient(x,y,3,x,y,105);const pulse=0;r.addColorStop(0,`rgba(255,219,143,${.13+pulse})`);r.addColorStop(1,'rgba(255,219,143,0)');g.fillStyle=r;g.fillRect(x-105,y-105,210,210);}
   const props=this.env.assets;for(const a of props.filter(a=>a.surface!=='floor'))this.prop(g,a,game,{...opt,static:true});for(const a of props.filter(a=>a.flat))this.prop(g,a,game,{...opt,static:true});
   for(const a of props.filter(a=>a.surface==='floor'&&!a.flat&&a.id!=='ball_track').sort((a,b)=>(a.pos[0]+a.pos[1])-(b.pos[0]+b.pos[1])))this.prop(g,a,game,{...opt,static:true});
}return this.room;}
  dynamic(g,game,opt){
   if(opt.ambient)for(const [id,regions] of Object.entries(REGIONS)){const a=this.props.get(id),im=this.images[a.sprite],[x,y]=this.pos(a),density=im.width/a.cell[0];for(const r of regions){const [sx,sy,w,h]=r.box,angle=Math.sin(game.time*2*Math.PI/r.period)*r.amplitude*(game.actor.effect==='scratch'&&id==='cat_tower'?2.4:1);g.save();g.translate(Math.round(x)+r.pivot[0],Math.round(y)+r.pivot[1]);g.rotate(angle);g.imageSmoothingEnabled=opt.soft;g.drawImage(im,sx*density,sy*density,w*density,h*density,sx-r.pivot[0],sy-r.pivot[1],w,h);g.restore();}}
   this.prop(g,this.props.get('ball_track'),game,{...opt,ambient:false});
   if(opt.ambient){const a=this.props.get('bowls'),[x,y]=this.pos(a),t=(game.time%1700)/1700;g.save();g.translate(Math.round(x),Math.round(y));g.beginPath();g.ellipse(175,299,12,6,0,0,Math.PI*2);g.clip();g.strokeStyle=`rgba(244,255,249,${.5*(1-t)})`;g.lineWidth=1.2;g.beginPath();g.ellipse(175,299,3+t*10,1.5+t*5,0,0,Math.PI*2);g.stroke();g.restore();}
  }
  render(g,game,width,height,options={}){const opt={soft:true,ambient:true,zoom:1,selected:null,...options},v=this.env.viewport;let scale=Math.min(width/v[2],height/v[3])*opt.zoom;
   const resolution=Math.min(this.env.render_scale||1,Math.max(1,Math.ceil(scale)));
   if(resolution!==this.res){this.res=resolution;this.shell.width=1024*resolution;this.shell.height=768*resolution;this.room.width=1024*resolution;this.room.height=768*resolution;this.sceneKey=null;this.buildShell();}
   let cx=v[0]+v[2]/2,cy=v[1]+v[3]/2;if(opt.zoom>1&&opt.focus){cx=opt.focus[0];cy=opt.focus[1];cx=Math.max(144+width/scale/2,Math.min(880-width/scale/2,cx));cy=Math.max(64+height/scale/2,Math.min(704-height/scale/2,cy));}
   const tx=width/2-cx*scale,ty=height/2-cy*scale;this.mapping={scale,tx,ty};g.setTransform(1,0,0,1,0,0);g.fillStyle='#f2ebdf';g.fillRect(0,0,width,height);g.setTransform(scale,0,0,scale,tx,ty);g.imageSmoothingEnabled=opt.soft;g.drawImage(this.scene(game,opt),0,0,1024,768);this.dynamic(g,game,opt);
   if(game.mark&&game.busy){const p=this.project(game.mark);g.strokeStyle='#7c956d99';g.lineWidth=1.5;g.beginPath();g.ellipse(p[0],p[1],8,4,0,0,Math.PI*2);g.stroke();}
   this.shadow(g,game);this.cat(g,game);
   if(!game.lamp){g.fillStyle='rgba(59,64,81,.045)';g.fillRect(100,40,800,680);}g.setTransform(1,0,0,1,0,0);return this.mapping;
  }
 }
 return {Renderer,REGIONS,VOLUMES,contains,spans};
});
