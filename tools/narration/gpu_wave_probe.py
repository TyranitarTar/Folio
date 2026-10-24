"""Isolate Røst acoustic stages with identical captured inputs; no user job changes."""
from __future__ import annotations
import argparse
import inspect
import json
import time
import traceback
from pathlib import Path
from gpu_probe import HERE, TEXTS, versions, save


def run(args, result):
    import torch
    import torch_directml
    from tools.narration.engines import load_engine
    from tools.narration.core import ENGINES
    torch.set_num_threads(4)
    engine = load_engine('roest', HERE/'models/roest', HERE/'voices/mic_trimmed.wav',
                         ENGINES['roest'], cpu_threads=4, accelerator='directml')
    model = engine.model
    if args.stage == 'combined':
        import numpy as np
        from tools.narration.directml_backend import enable_acoustic
        from tools.narration.core import trim_boundary_silence, _write_wav
        enable_acoustic(model.s3gen, torch_directml.device(0))
        result['samples'] = []
        for index in range(6):
            torch.manual_seed(42+index)
            started=time.perf_counter()
            wave=engine.synthesize(TEXTS[index%len(TEXTS)]).detach().cpu().numpy().reshape(-1)
            assert np.isfinite(wave).all() and len(wave)>2400
            pcm=trim_boundary_silence(wave.tolist(),24000)
            seconds=time.perf_counter()-started
            path=args.output.with_name(args.output.stem+f'-{index+1}.wav')
            _write_wav(path,pcm,24000)
            row=dict(index=index,seconds=seconds,audioSeconds=len(pcm)/24000,
                     rtf=seconds/(len(pcm)/24000),file=path.name)
            result['samples'].append(row);save(args.output,result)
            print(json.dumps(row),flush=True)
        result['passed']=True
        return
    seen = set()
    for module in model.s3gen.modules():
        for cls in type(module).__mro__:
            if cls in seen: continue
            seen.add(cls)
            for name, func in list(cls.__dict__.items()):
                if inspect.isfunction(func) and hasattr(func, '__wrapped__') and func.__module__.startswith('chatterbox'):
                    setattr(cls, name, torch.no_grad()(func.__wrapped__))
    captured = {}
    flow = model.s3gen.flow
    original_euler = flow.decoder.basic_euler
    def fixed_noise(x, *a, **kw):
        # Meanflow also samples noise for the reference prefix internally. Cache
        # that whole initial state, not just noised_mels for the target suffix.
        if 'initialNoise' not in captured:
            captured['initialNoise'] = x.detach().cpu().clone()
        return original_euler(captured['initialNoise'].to(x.device), *a, **kw)
    flow.decoder.basic_euler = fixed_noise
    original_flow = flow.inference
    def capture_flow(*a, **kw):
        assert not a
        captured['flow'] = kw
        output = original_flow(**kw)
        captured['mel'] = output[0].detach().clone()
        return output
    flow.inference = capture_flow
    hift = model.s3gen.mel2wav
    original_decode = hift.decode
    def capture_decode(*a, **kw):
        captured['decode'] = inspect.signature(original_decode).bind(*a, **kw).arguments
        output = original_decode(*a, **kw)
        captured['wave'] = output.detach().clone()
        return output
    hift.decode = capture_decode
    print('Capturing real acoustic inputs from an authored Danish sentence.', flush=True)
    torch.manual_seed(42)
    engine.synthesize(TEXTS[0])
    flow.inference = original_flow
    hift.decode = original_decode
    result['samples'] = []
    device = torch_directml.device(0)
    result['adapter'] = torch_directml.device_name(0).rstrip('\0')
    def tree(value, destination):
        if torch.is_tensor(value): return value.to(destination)
        if isinstance(value, tuple): return tuple(tree(x,destination) for x in value)
        if isinstance(value, dict): return {k:tree(v,destination) for k,v in value.items()}
        return value
    def measure(label, forward, inputs, reference):
        for index in range(7 if args.stage=='hift-real' else 4):
            started = time.perf_counter()
            with torch.no_grad(): output = forward(**inputs)
            if isinstance(output, tuple): output=output[0]
            output = output.cpu()  # Completion/readback included.
            seconds = time.perf_counter()-started
            assert output.shape == reference.shape and torch.isfinite(output).all()
            delta = output-reference
            row = dict(stage=label, run=index, seconds=seconds, shape=list(output.shape),
                       maxAbsoluteError=float(delta.abs().max()), rmse=float(delta.square().mean().sqrt()),
                       referenceRms=float(reference.square().mean().sqrt()))
            result['samples'].append(row);save(args.output,result)
            print(json.dumps(row),flush=True)
    if args.stage == 'flow':
        measure('flow-cpu',original_flow,captured['flow'],captured['mel'])
        print('Moving only the acoustic flow network to GPU.',flush=True)
        flow.to(device)
        measure('flow-dml',original_flow,tree(captured['flow'],device),captured['mel'])
    else:
        measure('hift-decode-cpu',original_decode,captured['decode'],captured['wave'])
        stft, istft = hift._stft, hift._istft
        # Keep FFTs on CPU because DirectML does not implement complex tensors.
        def bridge_stft(x): return tree(stft(x.cpu()),device)
        def bridge_istft(magnitude,phase): return istft(magnitude.cpu(),phase.cpu()).to(device)
        if args.stage=='hift-real':
            from tools.narration.gpu_real_fft import RealHiFTTransform
            transform=RealHiFTTransform(device,hift.istft_params['n_fft'],hift.istft_params['hop_len'])
            hift._stft,hift._istft=transform.stft,transform.istft
            for module in hift.modules():
                if torch.nn.utils.parametrize.is_parametrized(module,'weight'):
                    torch.nn.utils.parametrize.remove_parametrizations(module,'weight',leave_parametrized=True)
            result['experimental']='GPU decoder with real-valued STFT/ISTFT; captured excitation, not full GPU speech'
        else:hift._stft, hift._istft = bridge_stft, bridge_istft
        hift.to(device)
        measure('hift-decode-dml-real-fft' if args.stage=='hift-real' else 'hift-decode-dml-cpu-fft',original_decode,tree(captured['decode'],device),captured['wave'])
        if args.stage=='hift-real':
            hift._stft,hift._istft=bridge_stft,bridge_istft
            measure('hift-decode-dml-cpu-fft',original_decode,tree(captured['decode'],device),captured['wave'])
    result['passed'] = all(row['maxAbsoluteError']<=1e-4 and row['rmse']/max(row['referenceRms'],1e-9)<=1e-4 for row in result['samples']) if args.stage=='hift-real' else True


def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',choices=['flow','hift','hift-real','combined'],required=True)
    p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    args.output=args.output.resolve()
    result=dict(stage=args.stage,versions=versions(),passed=False)
    save(args.output,result);started=time.perf_counter()
    try: run(args,result)
    except Exception as exc:
        result.update(error=repr(exc),traceback=traceback.format_exc());traceback.print_exc()
    finally:
        result['wallSeconds']=time.perf_counter()-started;save(args.output,result)
    return 0 if result['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
