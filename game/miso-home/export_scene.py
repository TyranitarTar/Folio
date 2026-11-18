"""Stage native scene layers and GPU occluders from the unchanged Folio room kit."""
from pathlib import Path
from PIL import Image
import json

ROOT=Path(__file__).resolve().parent
ASSETS=ROOT/'assets'
VOLUMES={
 'bowls':dict(ground=[[110,296],[147,278],[213,312],[177,330]],height=8),
 'ball_track':dict(ground=[[109,309],[158,283],[209,309],[158,335]],height=12),
 'sofa':dict(ground=[[81,286],[140,252],[241,309],[180,341]],height=104),
 'cat_tower':dict(ground=[[116,308],[159,286],[204,310],[160,333]],height=118),
 'lamp':dict(ground=[[142,317],[160,308],[179,318],[160,327]],height=130),
 'box':dict(ground=[[112,308],[160,283],[208,308],[160,336]],height=52),
 'tunnel':dict(ground=[[89,301],[115,286],[213,334],[187,350]],height=50)}
REGIONS={
 'high_shelf':[dict(box=[220,309,45,44],pivot=[230,309],amplitude=.035,period=3.5)],
 'cubby_plant':[dict(box=[180,302,25,37],pivot=[184,302],amplitude=.045,period=4.1)],
 'cat_tower':[dict(box=[187,219,23,40],pivot=[197,219],amplitude=.06,period=2.7)],
 'feather':[dict(box=[164,280,49,37],pivot=[172,309],amplitude=.025,period=3.1)]}

def spans(poly):
 result=[]
 for x in range(320):
  at=x+.5;ys=[]
  for a,b in zip(poly,poly[1:]+poly[:1]):
   if a[0]<=at<b[0] or b[0]<=at<a[0]:ys.append(a[1]+(at-a[0])*(b[1]-a[1])/(b[0]-a[0]))
  result.append([min(ys),max(ys)] if len(ys)>=2 else None)
 return result

def export():
 env=json.loads((ASSETS/'environment.json').read_text(encoding='utf-8'))
 props={a['id']:a for a in env['assets']}
 atlas=Image.new('RGBA',(2560,1536))
 entries=[]
 for index,(id,model) in enumerate(VOLUMES.items()):
  prop=props[id];image=Image.open(ASSETS/Path(prop['sprite']).name).convert('RGBA')
  assert image.size==(640,768)
  atlas.alpha_composite(image,(index%4*640,index//4*768))
  x,y=prop['pos'];entries.append(dict(id=id,height=model['height'],spans=spans(model['ground']),
   topLeft=[512+(x-y)*64-prop['anchor'][0],308+(x+y)*32-prop['elevation']-prop['anchor'][1]]))
 atlas.save(ASSETS/'occluders.png')
 (ASSETS/'occluders.json').write_text(json.dumps(entries,separators=(',',':'))+'\n',encoding='utf-8')
 ambient={}
 for id,regions in REGIONS.items():
  prop=props[id];image=Image.open(ASSETS/Path(prop['sprite']).name).convert('RGBA')
  for i,region in enumerate(regions):
   x,y,w,h=region['box'];crop=image.crop((x*2,y*2,(x+w)*2,(y+h)*2))
   crop.save(ASSETS/f'ambient-{id}-{i}.png')
   image.paste((0,0,0,0),(x*2,y*2,(x+w)*2,(y+h)*2))
  image.save(ASSETS/f'ambient-base-{id}.png')
  ambient[id]=regions
 (ASSETS/'ambience.json').write_text(json.dumps(ambient,indent=2)+'\n',encoding='utf-8')
 print('Staged 7 GPU occluders and 4 native ambient layers from original room kit.')

if __name__=='__main__':export()
