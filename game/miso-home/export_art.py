"""Export complete fixed-grid generated poses and a shared native animation library."""
from pathlib import Path
from PIL import Image
import hashlib
import json
import statistics

ROOT=Path(__file__).resolve().parent
SRC=ROOT/'art-source/production'
ASSETS=ROOT/'assets'

def export():
    records=[]
    # Only whole-sheet translation: preserve contacts, body bob and authored scale.
    jobs=[('directions',2,2,[0,1,2,3]),
          *[(f'walk-{f}',3,2,list(range(6))) for f in ['nw','n','w','s']],
          ('rest',3,2,[0,1,2,3,4]),('feeding',3,2,list(range(6))),
          ('play',3,2,[0,1,3,4,5]),('scratch',2,2,list(range(4))),
          ('jump',3,2,[0,3]),('tunnel',2,2,list(range(4)))]
    for name,cols,rows,contacts in jobs:
        path=SRC/(name+'-original.png')
        source=Image.open(path).convert('RGBA')
        assert abs(source.width/source.height-cols/rows)<.03,name
        frames=[]
        for i in range(cols*rows):
            cell=source.crop((i%cols*source.width//cols,i//cols*source.height//rows,
                              (i%cols+1)*source.width//cols,(i//cols+1)*source.height//rows))
            cell.putalpha(cell.getchannel('A').point(lambda a:255 if a>=160 else 0))
            frames.append(cell.resize((128,128),Image.Resampling.NEAREST))
        ground=round(statistics.median(frames[i].getbbox()[3]-1 for i in contacts))
        dy=111-ground
        assert abs(dy)<=22,(name,dy)
        registered=[]
        for i,frame in enumerate(frames):
            shifted=Image.new('RGBA',(128,128));shifted.alpha_composite(frame,(0,dy));registered.append(shifted)
            for size in [128,96]:
                shifted.resize((size,size),Image.Resampling.NEAREST).save(ASSETS/f'{name}-{size}-{i}.png')
        board=Image.new('RGBA',(cols*128,rows*128))
        for i,frame in enumerate(registered):board.alpha_composite(frame,(i%cols*128,i//cols*128))
        board.resize((cols*384,rows*384),Image.Resampling.NEAREST).save(SRC/(name+'-native-review.png'))
        previews=[f.resize((384,384),Image.Resampling.NEAREST) for f in registered]
        previews[0].save(SRC/(name+'.gif'),save_all=True,append_images=previews[1:],
                         duration=135 if name.startswith('walk') else 350,loop=0,disposal=2)
        records.append(dict(job=name,sourceSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                            cells=[cols,rows],frames=len(frames),wholeSheetTranslation=[0,dy],
                            alphaThreshold=160,paletteQuantized=False,silhouetteFit=False))
    clips={}
    def clip(name,files,durations,loop=True):
        roots=[]
        for file in files:
            basename=Path(file).name
            root_y=112
            # Grounded curled/crawling poses need their actual contact anchor.
            # Keep walk body bob and all airborne jump registration untouched.
            if basename.startswith(('rest-','tunnel-')) or (basename.startswith('jump-') and basename.endswith(('-0.png','-3.png'))):
                root_y=Image.open(ASSETS/basename.replace('{density}','128')).getbbox()[3]-1
            roots.append([64,root_y])
        clips[name]=dict(files=files,durations=durations,loop=loop,roots=roots)
    def files(job,indices):return [f'res://assets/{job}-{{density}}-{i}.png' for i in indices]
    clip('idle_sw',['res://assets/miso-{density}.png','res://assets/miso-idle-{density}-1.png',
                    'res://assets/miso-{density}.png','res://assets/miso-idle-{density}-2.png'],[2.8,.15,1.6,.7])
    clip('walk_sw',[f'res://assets/miso-walk-{{density}}-{i}.png' for i in range(6)],[.133]*6)
    for i,face in enumerate(['nw','n','w','s']):
        clip('idle_'+face,files('directions',[i]),[1])
        clip('walk_'+face,files('walk-'+face,range(6)),[.133]*6)
    clip('sit',files('rest',[0]),[1])
    clip('groom',files('rest',[0,1,1,2,1,0]),[.7,.3,.6,.5,.4,.5])
    clip('curl_down',files('rest',[0,3,4]),[.35,.5,.65],False)
    clip('curl_up',files('rest',[4,3,5]),[.3,.4,.7],False)
    clip('sleep',files('rest',[4]),[1])
    for face,offset in [('sw',0),('nw',3)]:
        clip('eat_'+face,files('feeding',[offset,offset+1]),[.4,.35])
        clip('drink_'+face,files('feeding',[offset,offset+2]),[.25,.2])
        clip('jump_'+face,files('jump',range(offset,offset+3)),[.22,.18,.3],False)
    clip('sniff',files('play',[0]),[1])
    clip('pounce',files('play',[1,2,3,0]),[.45,.3,.25,.5],False)
    clip('bat',files('play',[4,5,4,0]),[.28,.28,.35,.6])
    clip('scratch',files('scratch',[0,1,2,3,2,3]),[.4,.5,.35,.35,.35,.35])
    clip('crawl',files('tunnel',[0,1,2,1]),[.2,.2,.2,.2])
    clip('tunnel_enter',files('tunnel',[3,0]),[.45,.45],False)
    clip('tunnel_exit',files('tunnel',[2,3]),[.45,.45],False)
    library=dict(version=1,cell=[128,128],root=[64,112],worldScale=.75,clips=clips,
                 facingClips={'sw':['sw',False],'se':['sw',True],'nw':['nw',False],'ne':['nw',True],
                              's':['s',False],'n':['n',False],'w':['w',False],'e':['w',True]})
    (ASSETS/'animation-library.json').write_text(json.dumps(library,indent=2)+'\n',encoding='utf-8')
    (SRC/'export.json').write_text(json.dumps(dict(jobs=records,userApprovedIdentity='miso-master-v2',
        originalMasterUnchanged=True,agentReviewed=True,newMotionUserAccepted=False),indent=2)+'\n',encoding='utf-8')
    print(f'Exported {sum(j["frames"] for j in records)} new complete poses; {len(clips)} clips, eight facings.')

if __name__=='__main__':export()
