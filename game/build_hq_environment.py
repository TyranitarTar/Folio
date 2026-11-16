"""Re-export existing room originals at 2x; keep all logical anchors/geometry."""
from pathlib import Path
from PIL import Image
import json
R=Path(__file__).resolve().parent
B=R/'Folio_Miso_Handoff/pixel-cat-experiment/production/playful-room-playable-v2'
A=R.parent/'app/src/main/assets/cafe'
S=2
env=json.loads((B/'environment.json').read_text());plan=json.loads((B/'source/room-plan.json').read_text())
out=A/'art/environment/hq';out.mkdir(exist_ok=True)
for asset in env['assets']:
    source=B/'source/environment-originals'/f"{asset['id']}.png"
    if asset['id']=='window':source=B/'originals/review-fixes/window_perch.png'
    if asset['id']=='cat_tower':source=B/'originals/review-fixes/tower_step.png'
    image=Image.open(source).convert('RGBA').resize((320*S,384*S),Image.Resampling.LANCZOS)
    image.putalpha(image.getchannel('A').point(lambda v:round(max(0,v-128)*255/127)))
    image.save(out/f"{asset['id']}.png");asset['sprite']=f"art/environment/hq/{asset['id']}.png"
mat=Image.open(B/'source/environment-originals/materials.png').convert('RGB').resize((512*S,256*S),Image.Resampling.LANCZOS)
mats={k:mat.crop((i%4*128*S,i//4*128*S,(i%4+1)*128*S,(i//4+1)*128*S)) for i,k in enumerate(plan['material_names'])}
for tile in env['tiles']:
    k=tile['id'];w,h=tile['size'];im=Image.new('RGBA',(w*S,h*S));pix=im.load()
    for py in range(h*S):
        for px in range(w*S):
            x=(px+.5)/S;y=(py+.5)/S;c=None
            if k.startswith('floor_'):
                u=(y/32+(x-64)/64)/2;v=(y/32-(x-64)/64)/2
                if 0<=u<1 and 0<=v<1:c=mats['wood_trim' if u<.012 or v<.012 else k].getpixel((int(u*128*S),int(v*128*S)))
            elif k.startswith('wall_') and k!='wall_post':
                side=k.split('_')[1];t=(96-x)/64 if side=='left' else (x-32)/64;z=256+32*t-y
                if 0<=t<1 and 0<=z<208:c=mats['wood_trim' if z<14 or z>=207 else k].getpixel((int(t*128*S),int(z*S)%(128*S)))
            elif k.startswith('foundation_'):
                side=k.split('_')[1];t=(x-32)/64 if side=='left' else (96-x)/64;d=y-32*t
                if 0<=t<1 and 0<=d<16:c=mats['foundation_light' if side=='left' else 'foundation_dark'].getpixel((int(t*128*S),int(d*8*S)))
            elif k=='wall_post':c=mats['wood_trim'].getpixel((int(x*16*S),int(y*S)%(128*S)))
            if c is not None:pix[px,py]=(*c,255)
    im.save(out/f'{k}.png');tile['path']=f'art/environment/hq/{k}.png'
env['render_scale']=S
(A/'environment.json').write_text(json.dumps(env,indent=2)+'\n')
print('Exported original environment at 2x source resolution; logical positions, cells and anchors unchanged.')
