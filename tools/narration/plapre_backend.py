"""Plapre 3 FP32 adapter using verified shared ONNX components, without copies.

Sampling follows tools/plapre3/samples.py's validated conversion. Kanade/HiFT
attribution and conversion notices remain with the installed model packs.
"""
from __future__ import annotations
from pathlib import Path
import json
import secrets
import threading
from .core import NarrationError


class PlapreAdapter:
    sample_rate = 24000
    engine = 'plapre3'
    supports_parallel = True

    def __init__(self, model_dir, sampling, accelerator, threads):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        if accelerator not in ('cpu','onnx-directml'):
            raise NarrationError('Plapre supports cpu or onnx-directml acoustic acceleration.')
        index=json.loads((Path(model_dir)/'folio-component-index.json').read_text(encoding='utf-8'))
        if index.get('engine')!=self.engine:raise NarrationError('Model component index belongs to another engine.')
        files={x['name']:Path(x['path']) for x in index['files']}
        def session(name,gpu=False):
            options=ort.SessionOptions();options.intra_op_num_threads=threads;options.inter_op_num_threads=1
            options.add_session_config_entry('session.intra_op.allow_spinning','0')
            options.enable_mem_pattern=False;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
            if gpu and 'DmlExecutionProvider' not in ort.get_available_providers():
                raise NarrationError('The selected Plapre GPU runtime has no DirectML provider.')
            providers=[('DmlExecutionProvider',{'device_id':'0'}),'CPUExecutionProvider'] if gpu else ['CPUExecutionProvider']
            return ort.InferenceSession(str(files[name]),options,providers=providers)
        self.lm=session('language-model.onnx')
        self.kanade=session('kanade.onnx',accelerator!='cpu');self.hift=session('hift.onnx',accelerator!='cpu')
        self.tokenizer=Tokenizer.from_file(str(files['tokenizer.json']))
        voice_key=sampling.get('voice','ida')
        if self.engine=='pico':
            self.embedding=np.load(files['embeddings.npy'],mmap_mode='r',allow_pickle=False)
            self.projection=session('speaker-projection.onnx')
            speakers=json.loads(files['speakers.json'].read_text(encoding='utf-8'))
            self.voice=dict(key=voice_key,embedding=speakers[voice_key]) if voice_key in speakers else None
            self.audio_start=self.tokenizer.token_to_id('<audio_0>');self.eos=self.tokenizer.token_to_id('<eos>')
            if self.audio_start is None or self.eos is None:raise NarrationError('Invalid Pico tokenizer.')
        else:
            self.embedding=np.memmap(files['embeddings.bin'],dtype='<f4',mode='r',shape=(21224,960))
            voices=json.loads(files['voices.json'].read_text(encoding='utf-8'))
            self.voice=next((v for v in voices if v['key']==voice_key),None)
        if self.voice is None:raise NarrationError('The selected Plapre voice is not installed.')
        self.sampling=sampling
        self.decode_lock=threading.Lock()
        self.execution=dict(profile=self.engine+('-cpu-lm-dml-acoustic-v1' if accelerator!='cpu' else '-cpu-v1'),
                            accelerator=accelerator,tokenDevice='cpu',acousticProvider=self.kanade.get_providers(),
                            precision='int8-lm-float32-acoustics' if self.engine=='pico' else 'float32',voice=self.voice['key'],runtime=ort.__version__)

    def synthesize(self,text,depth=0,seed=None):
        import numpy as np
        from .engines import TokenBudgetExhausted, split_at_safe_clause_boundary
        try:return self._synthesize(text,seed)
        except TokenBudgetExhausted:
            if depth>=4:raise
            parts=split_at_safe_clause_boundary(text)
            if not parts:raise
            return np.concatenate([self.synthesize(part,depth+1,None if seed is None else seed+i+1) for i,part in enumerate(parts)])

    def _synthesize(self,text,seed=None):
        import numpy as np
        from .engines import TokenBudgetExhausted
        rng=np.random.default_rng(secrets.randbits(64) if seed is None else seed)
        ids=[8000]+self.tokenizer.encode(text,add_special_tokens=False).ids+[8001]
        prefix=np.asarray(self.voice['prefix'],np.float32)[None]
        prompt=np.concatenate([prefix,self.embedding[ids]],0)[None]
        cache=[np.zeros((1,5,0,64),np.float32) for _ in range(64)];position=0;codes=[]
        names=[x.name for x in self.lm.get_inputs()][3:]
        for _ in range(751):
            length=prompt.shape[1]
            feed={'inputs_embeds':prompt,'attention_mask':np.ones((1,position+length),np.int64),
                  'cache_position':np.arange(position,position+length,dtype=np.int64)}
            feed.update(zip(names,cache))
            result=self.lm.run(None,feed);cache=result[1:];position+=length
            logits=result[0][0,-1].astype(np.float64)
            top_k=int(self.sampling['top_k']);indices=np.argpartition(logits,-top_k)[-top_k:]
            indices=indices[np.argsort(logits[indices])[::-1]]
            probabilities=np.exp((logits[indices]-logits[indices[0]])/float(self.sampling['temperature']))
            probabilities/=probabilities.sum()
            keep=np.searchsorted(np.cumsum(probabilities),float(self.sampling['top_p']))+1
            indices=indices[:keep];probabilities=probabilities[:keep]
            token=int(rng.choice(indices,p=probabilities/probabilities.sum()))
            if token in (0,21223):break
            if not 8002<=token<20802:raise NarrationError('Plapre emitted an invalid speech token; passage was not committed.')
            if len(codes)>=750:raise TokenBudgetExhausted(len(codes)+1,750)
            codes.append(token-8002);prompt=self.embedding[token][None,None]
        else:raise TokenBudgetExhausted(len(codes)+1,750)
        if not codes:raise NarrationError('Plapre returned no audio tokens.')
        return self._decode(codes,rng)

    def _decode(self,codes,rng):
        # CPU LM runs may overlap using independent request caches. DirectML
        # sessions cannot receive concurrent Run calls; serialize acoustics.
        with self.decode_lock:return self._decode_locked(codes,rng)

    def _decode_locked(self,codes,rng):
        import numpy as np
        mel=self.kanade.run(None,{'tokens':np.asarray(codes,np.int64),
                                'speaker':np.asarray(self.voice['embedding'],np.float32)[None]})[0]
        initial=rng.random((1,9),dtype=np.float32);initial[:,0]=0
        noise=rng.normal(size=(1,len(codes)*960,9)).astype(np.float32)
        wave=self.hift.run(None,{'mel':mel,'noise':noise,'initial_phase':initial})[0].reshape(-1)
        if len(wave)!=len(codes)*960 or not np.isfinite(wave).all() or np.max(np.abs(wave))<1e-6:
            raise NarrationError('Plapre returned invalid waveform audio; passage was not committed.')
        return wave


class PicoAdapter(PlapreAdapter):
    """Corrected RoPE INT8 LM; the same Kanade/HiFT acoustic weights as Plapre 3."""
    engine = 'pico'

    def _synthesize(self,text,seed=None):
        import numpy as np
        from .engines import TokenBudgetExhausted
        rng=np.random.default_rng(secrets.randbits(64) if seed is None else seed)
        speaker=np.asarray(self.voice['embedding'],np.float32)[None]
        projected=self.projection.run(None,{self.projection.get_inputs()[0].name:speaker})[0]
        names=[x.name for x in self.lm.get_inputs()][3:]
        cache=[np.zeros((1,3,0,64),np.float32) for _ in names];position=0
        def step(embedding):
            nonlocal cache,position
            feed=dict(inputs_embeds=np.asarray(embedding,np.float32).reshape(1,1,576),
                      attention_mask=np.ones((1,position+1),np.int64),cache_position=np.asarray([position],np.int64))
            feed.update(zip(names,cache));result=self.lm.run(None,feed)
            cache=result[1:];position+=1
            return result[0][0,-1].astype(np.float64)
        logits=step(projected)
        prompt=[self.tokenizer.token_to_id('<text>')]+self.tokenizer.encode(text,add_special_tokens=False).ids+[self.tokenizer.token_to_id('<audio>')]
        for token in prompt:logits=step(self.embedding[token])
        codes=[]
        for _ in range(751):
            if not np.isfinite(logits).all():raise NarrationError('Pico emitted non-finite logits; passage was not committed.')
            top_k=int(self.sampling['top_k']);indices=np.argpartition(logits,-top_k)[-top_k:]
            top=logits[indices]/float(self.sampling['temperature']);probabilities=np.exp(top-top.max());probabilities/=probabilities.sum()
            order=np.argsort(probabilities)[::-1];keep=order[:np.searchsorted(np.cumsum(probabilities[order]),float(self.sampling['top_p']))+1]
            token=int(rng.choice(indices[keep],p=probabilities[keep]/probabilities[keep].sum()))
            if token==self.eos:break
            if not self.audio_start<=token<self.audio_start+12800:raise NarrationError('Pico emitted an invalid speech token; passage was not committed.')
            if len(codes)>=750:raise TokenBudgetExhausted(len(codes)+1,750)
            codes.append(token-self.audio_start);logits=step(self.embedding[token])
        else:raise TokenBudgetExhausted(len(codes)+1,750)
        if not codes:raise NarrationError('Pico returned no audio tokens.')
        return self._decode(codes,rng)
