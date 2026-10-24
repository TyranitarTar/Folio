"""Compare complete desktop Plapre speech on CPU/DirectML, retaining GPU KV caches."""
from __future__ import annotations
import argparse
import importlib.util
import json
import time
import traceback
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer
from gpu_probe import HERE, WORKSPACE, TEXTS, versions, save


class BoundDecode:
    """The existing sampler can carry OrtValues without reading KV tensors back."""
    def __init__(self, session): self.session=session
    def __getattr__(self, name): return getattr(self.session,name)
    def run(self, _names, feed):
        binding=self.session.io_binding()
        for key,value in feed.items():
            if isinstance(value,ort.OrtValue): binding.bind_ortvalue_input(key,value)
            else: binding.bind_cpu_input(key,np.ascontiguousarray(value))
        names=[x.name for x in self.session.get_outputs()]
        binding.bind_output(names[0],'cpu')
        for name in names[1:]: binding.bind_output(name,'dml',0)
        self.session.run_with_iobinding(binding)
        outputs=binding.get_outputs()
        return [outputs[0].numpy()]+outputs[1:]


def load_module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def run(args,result):
    import soundfile as sf
    pico=WORKSPACE/'tools/pico';p3=WORKSPACE/'tools/plapre3'
    sessions={}
    def session(label,path,gpu):
        options=ort.SessionOptions();options.intra_op_num_threads=args.threads;options.inter_op_num_threads=1
        options.enable_mem_pattern=False;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
        options.add_session_config_entry('session.intra_op.allow_spinning','0')
        options.enable_profiling=args.profile;options.profile_file_prefix=str(args.output.with_suffix(''))+'-'+label
        if gpu:
            assert 'DmlExecutionProvider' in ort.get_available_providers()
            providers=[('DmlExecutionProvider',{'device_id':'0'}),'CPUExecutionProvider']
        else: providers=['CPUExecutionProvider']
        print('Loading '+label+' '+('DirectML'if gpu else'CPU'),flush=True)
        s=ort.InferenceSession(str(path),options,providers=providers);sessions[label]=s;return s
    gpu_lm=args.backend=='dml'and args.placement in ('lm','both')
    gpu_acoustic=args.backend=='dml'and args.placement in ('acoustic','both')
    start=time.perf_counter()
    if args.model=='plapre3':
        module=load_module('folio_plapre3_samples',p3/'samples.py');lm=module.Plapre3LM.__new__(module.Plapre3LM)
        s=session('lm',p3/'export/plapre3.onnx',gpu_lm)
        lm.session=BoundDecode(s)if gpu_lm and args.io_binding else s
        lm.embedding=np.memmap(p3/'export/embeddings.bin',dtype='<f4',mode='r',shape=(21224,960))
        lm.tokenizer=Tokenizer.from_file(str(p3/'artifacts/tokenizer.json'))
        lm.voices=json.loads((p3/'export/voices.json').read_text())
        voice=np.asarray(lm.voices[0]['embedding'],np.float32)[None]
    else:
        module=load_module('folio_pico_probe',pico/'probe_lm.py');lm=module.PicoLM.__new__(module.PicoLM)
        precision='fp32' if args.model=='pico-fp32'else'int8'
        s=session('lm',pico/'export'/('pico-fp32.onnx'if precision=='fp32'else'pico.onnx'),gpu_lm)
        lm.session=BoundDecode(s)if gpu_lm and args.io_binding else s
        lm.project=session('projection',pico/'artifacts/pico/onnx/speaker_proj.onnx',False)
        lm.kv_names=[x.name for x in s.get_inputs()][3:]
        lm.tokenizer=Tokenizer.from_file(str(pico/'artifacts/pico/tokenizer.json'))
        lm.embeddings=np.load(pico/'artifacts/pico/embed_tokens.npy',mmap_mode='r')
        lm.speakers=json.loads((pico/'artifacts/pico/speakers.json').read_text())
        lm.audio_start=lm.tokenizer.token_to_id('<audio_0>');lm.eos=lm.tokenizer.token_to_id('<eos>')
        voice=np.asarray(lm.speakers['ida'],np.float32)[None]
    kanade=session('kanade',pico/'export/kanade.onnx',gpu_acoustic)
    hift=session('hift',pico/'export/hift.onnx',gpu_acoustic)
    result['loadSeconds']=time.perf_counter()-start
    result['samples']=[]
    try:
        for i in range(args.samples):
            text=TEXTS[i%len(TEXTS)];start=time.perf_counter()
            print('Generating sample '+str(i+1),flush=True)
            if args.model=='plapre3': codes,lmrow=lm.generate(text,0,42+i)
            else:
                lmrow=lm.generate(text,'ida',seed=42+i)
                assert lmrow['stop']=='eos',lmrow['stop']
                codes=np.asarray(lmrow['tokens'],np.int64)
            token_done=time.perf_counter()
            mel=kanade.run(None,{'tokens':codes,'speaker':voice})[0]
            acoustic_done=time.perf_counter()
            rng=np.random.default_rng(42+i)
            initial=rng.random((1,9),dtype=np.float32);initial[:,0]=0
            noise=rng.normal(size=(1,len(codes)*960,9)).astype(np.float32)
            wave=hift.run(None,{'mel':mel,'noise':noise,'initial_phase':initial})[0].reshape(-1)
            finished=time.perf_counter()
            assert len(wave)==len(codes)*960 and np.isfinite(wave).all()and np.max(np.abs(wave))>0
            dest=args.output.with_name(args.output.stem+f'-{i+1}.wav')
            sf.write(dest,wave,24000,subtype='PCM_16')
            row=dict(text=text,codes=len(codes),audioSeconds=len(wave)/24000,
                     lmSeconds=token_done-start,acousticSeconds=acoustic_done-token_done,
                     vocoderSeconds=finished-acoustic_done,totalSeconds=time.perf_counter()-start,
                     file=dest.name)
            row['rtf']=row['totalSeconds']/row['audioSeconds']
            result['samples'].append(row);save(args.output,result)
            print(json.dumps(row,ensure_ascii=False),flush=True)
    finally:
        result['profiles']={}
        for label,s in sessions.items():
            if not args.profile:
                result['profiles'][label]=dict(requestedProviders=s.get_providers(),note='Profiling disabled during timing; use a separate --profile run for node assignment.')
                continue
            try:
                p=Path(s.end_profiling());events=json.loads(p.read_text());providers={}
                for event in events:
                    provider=event.get('args',{}).get('provider')
                    if provider: providers[provider]=providers.get(provider,0)+1
                result['profiles'][label]=dict(path=str(p),nodeProviderEvents=providers)
            except Exception as exc: result['profiles'][label]=dict(error=str(exc))
    if args.backend=='dml' and args.profile:
        assert any(p.get('nodeProviderEvents',{}).get('DmlExecutionProvider',0)>0 for p in result['profiles'].values())
    result['passed']=True


def main():
    p=argparse.ArgumentParser();p.add_argument('--model',choices=['plapre3','pico-fp32','pico-int8'],default='plapre3')
    p.add_argument('--backend',choices=['cpu','dml'],default='dml')
    p.add_argument('--placement',choices=['lm','acoustic','both'],default='both')
    p.add_argument('--io-binding',action='store_true');p.add_argument('--threads',type=int,default=4)
    p.add_argument('--profile',action='store_true',help='Separate diagnostic run; profiling adds overhead and is disabled for timing.')
    p.add_argument('--samples',type=int,default=1);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output=args.output.resolve();args.output.parent.mkdir(parents=True,exist_ok=True)
    result=dict(model=args.model,backend=args.backend,placement=args.placement,ioBinding=args.io_binding,profilingEnabled=args.profile,
                versions=versions(),passed=False)
    start=time.perf_counter()
    try: run(args,result)
    except Exception as exc: result.update(error=repr(exc),traceback=traceback.format_exc());traceback.print_exc()
    finally:
        result['wallSeconds']=time.perf_counter()-start;save(args.output,result)
        print(json.dumps({k:v for k,v in result.items()if k not in ('samples','versions','traceback')},ensure_ascii=False),flush=True)
    return 0 if result['passed']else 1

if __name__=='__main__':raise SystemExit(main())
