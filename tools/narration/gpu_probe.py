"""Explicit desktop inference experiments; never selects or alters a production job.

Run with the separate .venv-dml interpreter. Results are local, not release assets.
"""
from __future__ import annotations
import argparse
import dataclasses
import importlib.metadata
import json
import os
import platform
import sys
import time
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parents[1]
sys.path.insert(0, str(WORKSPACE))
os.environ.setdefault('HF_HOME', str(HERE / 'cache'))
os.environ.setdefault('HF_HUB_OFFLINE', '1')
os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')
os.environ.setdefault('TQDM_DISABLE', '1')

TEXTS = [
    'Regnen er holdt op. Hun lukker bogen og går stille ud i den klare morgen.',
    'På biblioteket i København koster bogen tolv komma fem nul kroner.',
    'Hun hviskede: Jeg kommer tilbage i morgen. Derefter lukkede hun døren.',
]


def save(path, record):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding='utf-8')


def versions():
    result = dict(python=sys.version, platform=platform.platform())
    for name in ('torch', 'torchaudio', 'torch-directml', 'chatterbox-tts', 'transformers', 'onnxruntime-directml', 'numpy'):
        try: result[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError: pass
    return result


def tensor_probe(args, result):
    import torch
    import torch_directml
    result['deviceCount'] = torch_directml.device_count()
    result['deviceNames'] = [torch_directml.device_name(i) for i in range(result['deviceCount'])]
    assert result['deviceCount'] > args.device, 'No DirectML device at this index'
    device = torch_directml.device(args.device)
    torch.manual_seed(42)
    a, b = torch.randn(512, 512), torch.randn(512, 512)
    expected = a @ b
    ga, gb = a.to(device), b.to(device)
    actual = (ga @ gb).cpu()
    error = float((expected - actual).abs().max())
    assert torch.allclose(expected, actual, atol=.001, rtol=.001), error
    started = time.perf_counter()
    loops = 0
    while time.perf_counter() - started < 5:
        actual = (ga @ gb).cpu()  # Readback waits for actual GPU completion.
        loops += 1
    result.update(device=str(device), maxAbsoluteError=error, loops=loops,
                  seconds=time.perf_counter()-started, passed=True,
                  note='Hardware/matmul compatibility only, not a TTS speed result.')


def roest_probe(args, result):
    import torch
    if args.no_inference_mode:
        from chatterbox.models.t3.t3 import T3
        # DirectML 0.2.5 cannot use this inference-tensor fast path. Preserve
        # the exact forward code and disable gradients through no_grad instead.
        T3.inference_turbo = torch.no_grad()(T3.inference_turbo.__wrapped__)
        result['experimentalNoGradInsteadOfInferenceMode'] = True
    from tools.narration.engines import load_engine
    from tools.narration.core import ENGINES, trim_boundary_silence, _write_wav
    torch.set_num_threads(args.threads)
    result['sampling'] = ENGINES['roest']
    print('Loading existing local Røst on CPU before experimental placement.', flush=True)
    start = time.perf_counter()
    engine = load_engine('roest', HERE/'models/roest', HERE/'voices/mic_trimmed.wav',
                         ENGINES['roest'], cpu_threads=args.threads)
    model = engine.model
    if args.no_inference_mode and args.stage in ('vocoder','both'):
        import inspect
        seen=set(); changed=[]
        for module in model.s3gen.modules():
            for cls in type(module).__mro__:
                if cls in seen: continue
                seen.add(cls)
                for name,func in list(cls.__dict__.items()):
                    if inspect.isfunction(func) and hasattr(func,'__wrapped__') and func.__module__.startswith('chatterbox'):
                        setattr(cls,name,torch.no_grad()(func.__wrapped__))
                        changed.append(cls.__name__+'.'+name)
        result['experimentalNoGradWaveMethods']=sorted(changed)
    result['loadAndConditionSeconds'] = time.perf_counter()-start
    cached_conditioning = None
    if args.cache_conditioning:
        with torch.no_grad():
            cached_conditioning = model.t3.prepare_conditioning(model.conds.t3).detach()
        result['conditioningCache'] = 'Fixed voice conditioning computed once on CPU, then retained on token-model device.'
    if args.attention == 'eager':
        model.t3.tfmr.set_attn_implementation('eager')
    result['attention'] = model.t3.tfmr.config._attn_implementation
    stage_devices = dict(t3='cpu', vocoder='cpu')
    if args.backend == 'dml':
        import torch_directml
        device = torch_directml.device(args.device)
        result['adapter'] = torch_directml.device_name(args.device)
        def place(module, label):
            names = {id(t):n for n,t in list(module.named_parameters())+list(module.named_buffers())}
            def convert(tensor):
                try: return tensor.to(device)
                except Exception:
                    result['placementFailure'] = dict(stage=label, name=names.get(id(tensor)),
                        shape=list(tensor.shape), stride=list(tensor.stride()), dtype=str(tensor.dtype),
                        bytes=tensor.numel()*tensor.element_size())
                    raise
            module._apply(convert)
        print('Moving selected inference stages to '+str(device), flush=True)
        if args.stage in ('t3', 'both'):
            place(model.t3, 't3'); stage_devices['t3'] = device
        if args.stage in ('vocoder', 'both'):
            place(model.s3gen, 'vocoder'); stage_devices['vocoder'] = device
    # Keep orchestration/reference data on CPU. Bridge only the selected stages,
    # including transfer costs in their timings. Do not patch installed packages.
    original_t3 = model.t3.inference_turbo
    original_vocoder = model.s3gen.inference
    timings = []

    def t3(*a, **kw):
        t = time.perf_counter()
        cond = kw['t3_cond']
        kw['t3_cond'] = dataclasses.replace(cond, **{
            key: value.to(stage_devices['t3']) if torch.is_tensor(value) else value
            for key, value in cond.__dict__.items()
        })
        kw['text_tokens'] = kw['text_tokens'].to(stage_devices['t3'])
        tokens = original_t3(*a, **kw).cpu()
        timings.append(dict(stage='t3', seconds=time.perf_counter()-t,
                            speechTokens=tokens.numel(), device=str(stage_devices['t3'])))
        return tokens

    def cpu_tree(value):
        if torch.is_tensor(value): return value.cpu()
        if isinstance(value, tuple): return tuple(cpu_tree(x) for x in value)
        if isinstance(value, list): return [cpu_tree(x) for x in value]
        if isinstance(value, dict): return {k:cpu_tree(v) for k,v in value.items()}
        return value

    def vocoder(*a, **kw):
        t = time.perf_counter()
        kw['speech_tokens'] = kw['speech_tokens'].to(stage_devices['vocoder'])
        kw['ref_dict'] = {k:v.to(stage_devices['vocoder']) if torch.is_tensor(v) else v
                          for k,v in kw['ref_dict'].items()}
        output = cpu_tree(original_vocoder(*a, **kw))
        timings.append(dict(stage='vocoder', seconds=time.perf_counter()-t,
                            device=str(stage_devices['vocoder'])))
        return output

    model.t3.inference_turbo = t3
    model.s3gen.inference = vocoder
    result['stages'] = {k:str(v) for k,v in stage_devices.items()}
    if cached_conditioning is not None:
        cached_conditioning = cached_conditioning.to(stage_devices['t3'])
        model.t3.prepare_conditioning = lambda _cond: cached_conditioning
    result['samples'] = []
    for i in range(args.samples):
        torch.manual_seed(42+i)
        text = TEXTS[i % len(TEXTS)]
        print('Generating sample '+str(i+1), flush=True)
        timings.clear()
        start = time.perf_counter()
        wave = engine.synthesize(text).detach().cpu().numpy().reshape(-1)
        assert wave.size > 2400 and bool(__import__('numpy').isfinite(wave).all())
        pcm = trim_boundary_silence(wave.tolist(), 24000)
        path = args.output.parent/(args.output.stem+f'-{i+1}.wav')
        _write_wav(path, pcm, 24000)
        elapsed = time.perf_counter()-start
        row = dict(text=text, seconds=elapsed, audioSeconds=len(pcm)/24000,
                   rtf=elapsed/(len(pcm)/24000), stages=list(timings), file=path.name)
        result['samples'].append(row)
        save(args.output, result)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    result['passed'] = True


def onnx_probe(args, result):
    import numpy as np
    import onnxruntime as ort
    providers = ort.get_available_providers()
    result['availableProviders'] = providers
    if args.backend == 'dml':
        assert 'DmlExecutionProvider' in providers, 'DirectML execution provider is unavailable'
        selected = [('DmlExecutionProvider', dict(device_id=str(args.device))), 'CPUExecutionProvider']
    else: selected = ['CPUExecutionProvider']
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.enable_mem_pattern = False
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.add_session_config_entry('session.intra_op.allow_spinning', '0')
    if args.strict_gpu:
        if args.backend!='dml':raise ValueError('Strict GPU qualification requires DirectML')
        options.add_session_config_entry('session.disable_cpu_ep_fallback','1')
        selected=[('DmlExecutionProvider',dict(device_id=str(args.device)))]
    result['cpuFallbackDisabled']=args.strict_gpu
    options.enable_profiling = True
    options.profile_file_prefix = str(args.output.with_suffix(''))
    paths = {
        'plapre3': WORKSPACE/'tools/plapre3/export/plapre3.onnx',
        'pico-fp32': WORKSPACE/'tools/pico/export/pico-fp32.onnx',
        'pico-int8': WORKSPACE/'tools/pico/export/pico.onnx',
        'kanade': WORKSPACE/'tools/pico/export/kanade.onnx',
        'hift': WORKSPACE/'tools/pico/export/hift.onnx',
    }
    path = paths[args.model]
    print('Loading '+str(path.name)+' with '+args.backend, flush=True)
    start = time.perf_counter()
    session = ort.InferenceSession(str(path), options, providers=selected)
    result.update(loadSeconds=time.perf_counter()-start, providers=session.get_providers(), model=str(path))
    rng = np.random.default_rng(42)
    n = args.tokens
    if args.model in ('plapre3','pico-fp32','pico-int8'):
        wide, heads = (960,5) if args.model=='plapre3' else (576,3)
        names = [i.name for i in session.get_inputs()]
        feed = dict(inputs_embeds=rng.normal(0,.02,(1,1,wide)).astype(np.float32),
                    attention_mask=np.ones((1,n+1),np.int64), cache_position=np.array([n],np.int64))
        feed.update({name:rng.normal(0,.02,(1,heads,n,64)).astype(np.float32) for name in names[3:]})
    elif args.model=='kanade':
        voice=json.loads((WORKSPACE/'tools/plapre3/export/voices.json').read_text())[0]['embedding']
        feed=dict(tokens=rng.integers(0,12800,n,dtype=np.int64), speaker=np.asarray(voice,np.float32)[None])
    else:
        # Read actual mel shape from the export; channels are fixed, time is dynamic.
        inputs={i.name:i.shape for i in session.get_inputs()}
        print(json.dumps(inputs), flush=True)
        feed=dict(mel=rng.normal(0,.5,(1,80,n*2)).astype(np.float32),
                  noise=rng.normal(0,1,(1,n*960,9)).astype(np.float32),
                  initial_phase=rng.random((1,9),dtype=np.float32))
        feed['initial_phase'][:,0]=0
    times=[]
    for i in range(args.samples+1):
        start=time.perf_counter()
        outputs=session.run(None,feed)
        elapsed=time.perf_counter()-start
        assert all(np.isfinite(x).all() for x in outputs)
        if i==0: result['firstRunSeconds']=elapsed
        else: times.append(elapsed)
    result.update(warmRunSeconds=times, outputShapes=[list(x.shape) for x in outputs])
    # Small deterministic evidence for CPU/GPU numerical comparison; no private text.
    result['firstOutputHead']=outputs[0].reshape(-1)[:256].astype(float).tolist()
    np.savez(args.output.with_suffix('.npz'), first=outputs[0])
    profile=Path(session.end_profiling())
    nodes={};operators={}
    for event in json.loads(profile.read_text(encoding='utf-8')):
        provider=event.get('args',{}).get('provider')
        if provider:
            entry=nodes.setdefault(provider,dict(events=0,profileMicroseconds=0))
            entry['events']+=1;entry['profileMicroseconds']+=event.get('dur',0)
            op=event.get('args',{}).get('op_name','unknown')
            group=operators.setdefault(provider,{})
            group[op]=group.get(op,0)+1
    result.update(profile=str(profile), nodeProviders=nodes,nodeOperators=operators, passed=True)
    if args.backend=='dml':
        assert nodes.get('DmlExecutionProvider',{}).get('events',0)>0, 'No profiled model work ran on DirectML'


def main():
    p=argparse.ArgumentParser()
    p.add_argument('mode', choices=['tensor','roest','onnx'])
    p.add_argument('--backend',choices=['cpu','dml'],default='dml')
    p.add_argument('--stage',choices=['t3','vocoder','both'],default='both')
    p.add_argument('--attention',choices=['default','eager'],default='default')
    p.add_argument('--no-inference-mode',action='store_true')
    p.add_argument('--strict-gpu',action='store_true',help='ONNX qualification: reject graphs with any CPU-assigned nodes.')
    p.add_argument('--cache-conditioning',action='store_true')
    p.add_argument('--model',choices=['plapre3','pico-fp32','pico-int8','kanade','hift'],default='plapre3')
    p.add_argument('--threads',type=int,default=4)
    p.add_argument('--tokens',type=int,default=50)
    p.add_argument('--samples',type=int,default=1)
    p.add_argument('--device',type=int,default=0)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();args.output=args.output.resolve();args.output.parent.mkdir(parents=True,exist_ok=True)
    result=dict(mode=args.mode,backend=args.backend,versions=versions(),pid=os.getpid(),passed=False)
    save(args.output,result)
    start=time.perf_counter()
    try: {'tensor':tensor_probe,'roest':roest_probe,'onnx':onnx_probe}[args.mode](args,result)
    except Exception as exc:
        result.update(passed=False,error=repr(exc),traceback=traceback.format_exc())
        traceback.print_exc()
    finally:
        result['wallSeconds']=time.perf_counter()-start
        save(args.output,result)
        print(json.dumps({k:v for k,v in result.items() if k not in ('versions','samples','firstOutputHead','traceback')},ensure_ascii=False),flush=True)
    return 0 if result['passed'] else 1


if __name__=='__main__': raise SystemExit(main())
