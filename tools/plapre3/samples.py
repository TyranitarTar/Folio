"""Complete Plapre 3 -> existing Kanade -> HiFT waveform proof and voice samples."""
from pathlib import Path
import json,time
import numpy as np
import torch,onnxruntime as ort,soundfile as sf
from tokenizers import Tokenizer
ROOT=Path(__file__).resolve().parent
PICO=next(p for p in [ROOT.parent/'pico',ROOT.parent/'pico-tools'] if p.is_dir())
def options():
    o=ort.SessionOptions();o.intra_op_num_threads=4;o.inter_op_num_threads=1
    o.add_session_config_entry('session.intra_op.allow_spinning','0');return o
class Plapre3LM:
 def __init__(self,model=None):
    self.session=ort.InferenceSession(str(model or ROOT/'export/plapre3.onnx'),options(),providers=['CPUExecutionProvider'])
    self.embedding=np.memmap(ROOT/'export/embeddings.bin',dtype='<f4',mode='r',shape=(21224,960))
    self.tokenizer=Tokenizer.from_file(str(ROOT/'artifacts/tokenizer.json'))
    speakers=json.loads((ROOT/'reference/plapre/speakers.json').read_text())
    projection=torch.load(ROOT/'artifacts/speaker_proj.pt',map_location='cpu',weights_only=True)
    self.voices=[]
    for i,name in enumerate(['ida','tor','liv','ask','kaj']):
        speaker=torch.tensor(speakers[name],dtype=torch.float32)
        prefix=torch.nn.functional.linear(speaker,projection['weight'],projection['bias'])
        self.voices.append(dict(id=i,key=name,embedding=speaker.tolist(),prefix=prefix.tolist()))
    (ROOT/'export/voices.json').write_text(json.dumps(self.voices,separators=(',',':')))
 def generate(self,text,voice=0,seed=42):
    ids=[8000]+self.tokenizer.encode(text,add_special_tokens=False).ids+[8001]
    prefix=np.asarray(self.voices[voice]['prefix'],np.float32)[None]
    prompt=np.concatenate([prefix,self.embedding[ids]],0)[None]
    cache=[np.zeros((1,5,0,64),np.float32) for _ in range(64)];position=0
    rng=np.random.default_rng(seed);codes=[];started=time.perf_counter();stop=None
    for _ in range(501):
        sequence=prompt.shape[1]
        feed={'inputs_embeds':prompt,'attention_mask':np.ones((1,position+sequence),np.int64),
              'cache_position':np.arange(position,position+sequence,dtype=np.int64)}
        feed.update(zip([i.name for i in self.session.get_inputs()][3:],cache))
        result=self.session.run(None,feed);cache=result[1:];position+=sequence
        logits=result[0][0,-1].astype(np.float64)
        indices=np.argpartition(logits,-50)[-50:];order=np.argsort(logits[indices])[::-1];indices=indices[order]
        p=np.exp((logits[indices]-logits[indices[0]])/.7);p/=p.sum()
        keep=np.searchsorted(np.cumsum(p),.95)+1;indices=indices[:keep];p=p[:keep]
        token=int(rng.choice(indices,p=p/p.sum()))
        if token in [0,21223]:stop=token;break
        assert 8002<=token<20802,('invalid audio token',token)
        assert len(codes)<500,'Token cap reached; not a completed passage'
        codes.append(token-8002);prompt=self.embedding[token][None,None]
    assert stop is not None and len(codes)>0
    return np.asarray(codes,np.int64),dict(text=text,voice=voice,seed=seed,stopToken=stop,
        codes=len(codes),lmSeconds=time.perf_counter()-started)
def trim(wave):
    levels=np.array([np.sqrt(np.mean(wave[i:i+240].astype(np.float64)**2)) for i in range(0,len(wave),240)])
    threshold=min(.003981071706,levels.max()*.025118864315);active=np.flatnonzero(levels>threshold)
    if not len(active):return wave
    start=max(0,int(active[0])*240-4800);end=min(len(wave),(int(active[-1])+1)*240+2880)
    if start<1440:start=0
    if len(wave)-end<1440:end=len(wave)
    return wave[start:end] if end-start>=4800 else wave
def main():
    lm=Plapre3LM()
    kanade=ort.InferenceSession(str(PICO/'export/kanade.onnx'),options(),providers=['CPUExecutionProvider'])
    hift=ort.InferenceSession(str(PICO/'export/hift.onnx'),options(),providers=['CPUExecutionProvider'])
    out=ROOT/'results/samples';out.mkdir(parents=True,exist_ok=True)
    common='Jeg læser din bog højt, mens du følger teksten på skærmen.'
    cases=[(i,common) for i in range(5)]+[(0,'På biblioteket i København koster bogen tolv komma fem nul kroner.'),
            (1,'Hun hviskede: Jeg kommer tilbage i morgen. Derefter lukkede hun døren.')]
    report=[]
    for index,(voice,text) in enumerate(cases):
        started=time.perf_counter();codes,row=lm.generate(text,voice,42)
        speaker=np.asarray(lm.voices[voice]['embedding'],np.float32)[None]
        mel=kanade.run(None,{'tokens':codes,'speaker':speaker})[0]
        rng=np.random.default_rng(42);initial=rng.random((1,9),dtype=np.float32);initial[:,0]=0
        noise=rng.normal(size=(1,len(codes)*960,9)).astype(np.float32)
        raw=hift.run(None,{'mel':mel,'noise':noise,'initial_phase':initial})[0].reshape(-1)
        assert raw.size==len(codes)*960 and np.isfinite(raw).all()
        wave=trim(raw);assert np.max(np.abs(wave))<10 and np.sqrt(np.mean(wave**2))>.0001
        path=out/f'plapre3-da-{index}.wav';sf.write(path,wave,24000,subtype='PCM_16')
        row.update(file=path.name,rawSeconds=len(raw)/24000,audioSeconds=len(wave)/24000,
                   totalSeconds=time.perf_counter()-started)
        report.append(row);print(json.dumps(row,ensure_ascii=True),flush=True)
        (ROOT/'results/samples.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
if __name__=='__main__':main()
