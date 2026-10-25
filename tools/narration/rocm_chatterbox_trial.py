"""Isolated original-weight Røst/Nano HIP speech trial; never changes Studio jobs."""
from pathlib import Path
import argparse
import gc
import hashlib
import json
import math
import os
import sys
import threading
import time
import traceback
import types

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--engine', choices=('roest', 'chatterbox-nano'), default='roest')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--count', type=int, default=1, choices=range(1, 5))
    p.add_argument('--repeat', type=int, default=1, choices=range(1, 5))
    p.add_argument('--precision', choices=('fp32', 'fp16'), default='fp32')
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result = dict(passed=False, state='starting', engine=args.engine, precision=args.precision+'-lm-fp32-acoustics', passages=[], validation=[],
                  started=time.time(), productionBackendChanged=False, watermarkDevice='cpu',
                  miopenFindMode=os.environ.get('MIOPEN_FIND_MODE', 'runtime-default'))
    def save(state=None):
        if state:
            result['state'] = state
            print(state, flush=True)
        from tools.narration.rocm_trial_support import write_trial_json
        write_trial_json(args.output/'result.json', result)
    save()
    from tools.narration.benchmark_parallel import memory, DA, EN
    from tools.narration.gpu_memory import GpuMemorySampler
    sampler = GpuMemorySampler()
    stop = threading.Event()
    peaks = {}
    def monitor():
        while not stop.wait(.1):
            for key, value in dict(memory(), **sampler.sample()).items():
                peaks[key] = max(value, peaks.get(key, 0))
    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    try:
        import torch
        import torchaudio
        import numpy as np
        import soundfile as sf
        import transformers.utils.import_utils as imports
        # Never import the old environment's incompatible optional vision extension.
        imports._torchvision_available = False
        # Read-only reuse of non-torch dependencies, after loading the isolated torch/audio.
        deps = ROOT/'tools/narration/.venv/Lib/site-packages'
        sys.path.append(str(deps))
        # Avoid the upstream initializer's unrelated multilingual/VC imports.
        package = types.ModuleType('chatterbox')
        package.__path__ = [str(deps/'chatterbox')]
        sys.modules['chatterbox'] = package
        from tools.narration.nano_backend import load_roest, load_nano
        from tools.narration.engines import install_turbo_eos_guard
        from tools.narration.core import ENGINES
        import chatterbox.models.t3.t3 as token_module
        token_module.tqdm = lambda iterable, *_a, **_k: iterable
        # Transformers 4.57 retains non-checkpoint GPT-2 causal buffers that 5.2
        # removed. Rebuild exactly the derived mask, sharing its CPU storage.
        # Never materialize a second copy of checkpoint parameters.
        original_t3_init = token_module.T3.__init__
        def initialize_t3(instance, *a, **kw):
            original_t3_init(instance, *a, **kw)
            shared_masks = {}
            for module in instance.modules():
                bias = getattr(module, 'bias', None)
                masked = getattr(module, 'masked_bias', None)
                if type(module).__name__ == 'GPT2Attention' and isinstance(bias, torch.Tensor) and bias.is_meta:
                    length = bias.shape[-1]
                    if length not in shared_masks:
                        shared_masks[length] = torch.tril(torch.ones((length, length), dtype=torch.bool, device='cpu')).reshape(1, 1, length, length)
                    module.bias = shared_masks[length]
                    module.masked_bias = torch.tensor(-1e4, device='cpu')
        token_module.T3.__init__ = initialize_t3
        torch.set_num_threads(2)
        torch.set_num_interop_threads(1)
        torch.backends.cuda.matmul.allow_fp16_reduced_precision_reduction = False
        result['fp16ReducedPrecisionReduction'] = False
        if not torch.version.hip or not torch.cuda.is_available():
            raise RuntimeError('No HIP GPU; refusing CPU substitution')
        device = torch.device('cuda:0')
        result.update(torch=torch.__version__, torchaudio=torchaudio.__version__, hip=torch.version.hip,
                      gpu=torch.cuda.get_device_name())
        start_load = time.perf_counter()
        save('loading-original-model-weights')
        folder = ROOT/'tools/narration/models'/args.engine
        try:
            model = (load_roest if args.engine == 'roest' else load_nano)(folder)
        finally:
            token_module.T3.__init__ = original_t3_init
        if args.engine == 'roest':
            save('preparing-existing-reference-voice-once')
            model.prepare_conditionals(str(ROOT/'tools/narration/voices/mic_trimmed.wav'), exaggeration=0.0)
        if model.conds is None:
            raise RuntimeError('No voice conditionals')
        texts = (DA if args.engine == 'roest' else EN)[:args.count]
        # Check the original token model's prefill and cached next-token output.
        text_ids = model.tokenizer(texts[0], return_tensors='pt', padding=True, truncation=True).input_ids
        cpu_logits = []
        with torch.inference_mode():
            conditioning = model.t3.prepare_conditioning(model.conds.t3).detach()
            repeated = model.t3.prepare_conditioning(model.conds.t3).detach()
            if not torch.equal(conditioning, repeated):
                raise RuntimeError('Reference conditioning is not stable')
            prompt, _ = model.t3.prepare_input_embeds(t3_cond=model.conds.t3, text_tokens=text_ids,
                       speech_tokens=model.t3.hp.start_speech_token*torch.ones_like(text_ids[:, :1]), cfg_weight=0.0)
            past = None
            current = prompt
            forced = (100, 200, 300)
            for token in forced:
                output = model.t3.tfmr(inputs_embeds=current, past_key_values=past, use_cache=True)
                cpu_logits.append(model.t3.speech_head(output[0][:, -1:])[0, -1].clone())
                past = output.past_key_values
                current = model.t3.speech_emb(torch.tensor([[token]]))
            del output, past, current
        save('moving-neural-stages-to-gpu')
        # Cache voice conditioning once; preserve the existing weights and reference.
        lm_dtype = torch.float16 if args.precision == 'fp16' else torch.float32
        conditioning = conditioning.to(device=device, dtype=lm_dtype)
        masks = []
        for module in model.t3.modules():
            if type(module).__name__ == 'GPT2Attention':
                masks.append((module, module.bias, module.masked_bias))
                module.bias = None
                module.masked_bias = None
        model.t3.to(device=device, dtype=lm_dtype)
        shared_gpu_masks = {}
        for module, bias, masked in masks:
            key = bias.shape[-1]
            if key not in shared_gpu_masks:
                shared_gpu_masks[key] = (bias.to(device), masked.to(device))
            module.bias, module.masked_bias = shared_gpu_masks[key]
        del masks
        model.t3.prepare_conditioning = lambda _cond: conditioning
        model.conds.to(device)
        model.conds.t3.to(device=device, dtype=lm_dtype)
        for module in model.s3gen.mel2wav.modules():
            if torch.nn.utils.parametrize.is_parametrized(module, 'weight'):
                torch.nn.utils.parametrize.remove_parametrizations(module, 'weight', leave_parametrized=True)
        model.s3gen.to(device)
        for module in model.s3gen.modules():
            for name in ('pe', 'freqs_cis', 'stft_window'):
                value = getattr(module, name, None)
                if isinstance(value, torch.Tensor):
                    setattr(module, name, value.to(device))
        model.device = device
        with torch.inference_mode():
            past = None
            current = prompt.to(device=device, dtype=lm_dtype)
            for i, token in enumerate(forced):
                output = model.t3.tfmr(inputs_embeds=current, past_key_values=past, use_cache=True)
                actual = model.t3.speech_head(output[0][:, -1:])[0, -1].float().cpu()
                delta = float((actual-cpu_logits[i]).abs().max())
                row = dict(stage='token-prefill-and-cache', step=i, maxAbsoluteLogitError=delta,
                           sameTopToken=int(actual.argmax()) == int(cpu_logits[i].argmax()))
                result['validation'].append(row)
                tolerance = .15 if args.precision == 'fp16' else .002
                row['tolerance'] = tolerance
                if not math.isfinite(delta) or delta > tolerance or not row['sameTopToken']:
                    raise RuntimeError('Token-model GPU parity failed: '+str(row))
                past = output.past_key_values
                current = model.t3.speech_emb(torch.tensor([[token]], device=device))
            del output, past, current, prompt, cpu_logits
        if any(p.device.type != 'cuda' for net in (model.t3, model.s3gen) for p in net.parameters()):
            raise RuntimeError('A rendering neural parameter remains on CPU')
        result['neuralParameterDevices'] = ['cuda:0']
        result['referenceEncoderDevice'] = 'cpu-unused-during-rendering'
        result['modelLoadAndValidationSeconds'] = time.perf_counter()-start_load
        guard = install_turbo_eos_guard(model.t3)
        stages = {}
        token_hash = None
        def wrap(owner, attribute, name, verify_gpu=False):
            original = getattr(owner, attribute)
            def call(*a, **kw):
                nonlocal token_hash
                torch.cuda.synchronize()
                started = time.perf_counter()
                value = original(*a, **kw)
                torch.cuda.synchronize()
                stages[name] = stages.get(name, 0.0)+time.perf_counter()-started
                tensor = value[0] if isinstance(value, tuple) else value
                if verify_gpu and (not torch.is_tensor(tensor) or tensor.device.type != 'cuda'):
                    raise RuntimeError(name+' output is not on GPU')
                if name == 'tokens':
                    token_hash = hashlib.sha256(tensor.cpu().numpy().tobytes()).hexdigest()
                return value
            setattr(owner, attribute, call)
        wrap(model.t3, 'inference_turbo', 'tokens', True)
        wrap(model.s3gen, 'flow_inference', 'acousticFlow', True)
        wrap(model.s3gen, 'hift_inference', 'vocoder', True)
        wrap(model.watermarker, 'apply_watermark', 'watermark')
        settings = {key: ENGINES[args.engine][key] for key in ('temperature', 'top_p', 'top_k', 'repetition_penalty')}
        gc.collect()
        def render(text, seed):
            stages.clear()
            torch.manual_seed(seed)
            torch.cuda.synchronize()
            started = time.perf_counter()
            guard['max_gen_len'] = 1000
            with torch.inference_mode():
                wave = model.generate(text, **settings).reshape(-1).cpu().numpy()
            torch.cuda.synchronize()
            elapsed = time.perf_counter()-started
            if len(wave) < 2400 or not np.isfinite(wave).all() or np.abs(wave).max() < 1e-6:
                raise RuntimeError('Invalid speech waveform')
            return wave, dict(text=text, seed=seed, audioSeconds=len(wave)/24000, synthesisSeconds=elapsed,
                             rtf=elapsed/(len(wave)/24000), stages=stages.copy(), tokenSha256=token_hash)
        save('warming-up-speech')
        warm, row = render('Hun åbner sin bog og læser videre.' if args.engine == 'roest' else 'She opens her book and reads on.', 20261024)
        sf.write(args.output/'warmup.wav', warm, 24000, subtype='PCM_16')
        result['warmup'] = row
        save('generating-authored-passages')
        for repeat in range(args.repeat):
            for index, text in enumerate(texts):
                wave, row = render(text, 20261022+index)
                row.update(repeat=repeat, passageIndex=index)
                sf.write(args.output/f'passage-{index+1:03d}-repeat-{repeat+1}.wav', wave, 24000, subtype='PCM_16')
                result['passages'].append(row)
                save()
        result.update(passed=True, totalAudioSeconds=sum(r['audioSeconds'] for r in result['passages']),
                      totalSynthesisSeconds=sum(r['synthesisSeconds'] for r in result['passages']),
                      peakGpuAllocatedBytes=torch.cuda.max_memory_allocated(), peakGpuReservedBytes=torch.cuda.max_memory_reserved())
        save('complete')
    except Exception as exc:
        result.update(error=str(exc), traceback=traceback.format_exc())
        print(result['traceback'], flush=True)
        save('failed')
    finally:
        stop.set()
        watcher.join(timeout=1)
        sampler.close()
        result.update(memory=peaks, finished=time.time(), gpuMemoryCounterError=sampler.error)
        save()
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    from tools.narration.rocm_trial_support import gpu_trial_lease
    with gpu_trial_lease(ROOT):
        raise SystemExit(main())
