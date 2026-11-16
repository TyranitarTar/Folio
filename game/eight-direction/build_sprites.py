"""Append complete generated poses; never repaint or repack retained Miso frames."""
from pathlib import Path
from PIL import Image
import hashlib,json,math
R=Path(__file__).resolve().parent
B=R.parent/'Folio_Miso_Handoff/pixel-cat-experiment/production/playful-room-playable-v2'
if not B.exists(): B=R/'baseline'
A=R.parents[1]/'app/src/main/assets/cafe'
meta=json.loads((B/'animation_data.json').read_text())
palette=[tuple(bytes.fromhex(s[1:])) for s in meta['palette']]
base=Image.open(B/meta['atlas']).convert('RGBA')
frames=[base.crop((x,y,x+w,y+h)) for x,y,w,h in meta['rects']]
records=[]
for direction,facing in [('front','s'),('west','w'),('back','n')]:
    source=Image.open(R/f'run-{direction}-original.png').convert('RGBA')
    assert abs(source.width/source.height-1.5)<.03
    poses=[];bounds=[]
    for i in range(6):
        cell=source.crop((i%3*source.width//3,i//3*source.height//2,(i%3+1)*source.width//3,(i//3+1)*source.height//2))
        # Generated transparency includes a faint halo. Remove it at source scale.
        cell.putalpha(cell.getchannel('A').point(lambda a:255 if a>=190 else 0))
        cell=cell.resize((64,64),Image.Resampling.NEAREST)
        pixels=cell.load()
        for y in range(64):
            for x in range(64):
                c=pixels[x,y]
                pixels[x,y]=(*min(palette,key=lambda p:sum((a-b)**2 for a,b in zip(p,c[:3]))),255) if c[3] else (0,0,0,0)
        poses.append(cell);bounds.append(cell.getbbox())
    # A single whole-sheet registration preserves the authored flight/body bob.
    ground=sorted(bounds[i][3]-1 for i in [0,1,3,4])[2]
    dy=55-ground
    registered=[]
    for i,cell in enumerate(poses):
        aligned=Image.new('RGBA',(64,64));aligned.alpha_composite(cell,(0,dy));registered.append(aligned)
        aligned.save(R/f'run-{direction}-{i}.png')
    poses=registered
    neutral=Image.open(B/'masters'/f'miso-neutral-{direction}-64.png').convert('RGBA')
    neutral_id=len(frames);frames.append(neutral)
    start=len(frames);frames.extend(poses)
    meta['clips']['idle_'+facing]=dict(frames=[neutral_id],durations_ms=[1000],total_ms=1000,loop=True,root=[31,55],kind='idle',facing=facing)
    meta['clips']['run_'+facing]=dict(frames=list(range(start,start+6)),durations_ms=[85]*6,total_ms=510,loop=True,root=[31,55],kind='movement',facing=facing)
    board=Image.new('RGBA',(192,128))
    for i,p in enumerate(poses):board.alpha_composite(p,(i%3*64,i//3*64))
    board.resize((1152,768),Image.Resampling.NEAREST).save(R/f'run-{direction}-review.png')
    preview=[p.resize((384,384),Image.Resampling.NEAREST) for p in poses]
    preview[0].save(R/f'run-{direction}.gif',save_all=True,append_images=preview[1:],duration=85,loop=0,disposal=2)
    records.append(dict(direction=direction,facing=facing,grid=[3,2],source=list(source.size),alpha_threshold=190,
        native=[64,64],root=[31,55],whole_frame_translation=[0,dy],silhouette_fit=False,
        source_sha256=hashlib.sha256((R/f'run-{direction}-original.png').read_bytes()).hexdigest()))
meta['direction_clips']={'s':'run_s','n':'run_n','w':'run_w','e':'run_w'}
meta['direction_idle']={'s':'idle_s','n':'idle_n','w':'idle_w','e':'idle_w'}
atlas=Image.new('RGBA',(768,math.ceil(len(frames)/12)*64));rects=[]
for i,f in enumerate(frames):
    x=i%12*64;y=i//12*64;atlas.alpha_composite(f,(x,y));rects.append([x,y,64,64])
meta['rects']=rects;meta['atlas']='art/cat/miso-eight-atlas.png'
atlas.save(A/meta['atlas'])
(A/'animation_data.json').write_text(json.dumps(meta,indent=2)+'\n')
(R/'export.json').write_text(json.dumps(dict(frames=len(frames),retained_frames=289,clips=len(meta['clips']),jobs=records),indent=2)+'\n')
print('Appended 18 run poses and 3 reviewed directional neutrals; retained 289 frames unchanged.')
