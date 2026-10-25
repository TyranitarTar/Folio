"""Experimental original-weight Plapre 3 HIP trial; not a Studio backend/default.

CPU text/control and WAV writing remain. Neural LM/KV, codec and vocoder use HIP.
No user books, jobs, downloads, or installed runtime code are modified.
"""
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

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--count', type=int, default=1, choices=range(1, 5))
    parser.add_argument('--repeat', type=int, default=1, choices=range(1, 6))
    parser.add_argument('--precision', choices=('fp32', 'fp16'), default='fp32')
    parser.add_argument('--accurate-fp16', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result = dict(passed=False, state='starting', precision=args.precision, passages=[], validation=[],
                  started=time.time(), cpuControl=True, productionBackendChanged=False,
                  miopenFindMode=os.environ.get('MIOPEN_FIND_MODE', 'runtime-default'))

    def save(state=None):
        if state:
            result['state'] = state
            print(state, flush=True)
        from tools.narration.rocm_trial_support import write_trial_json
        write_trial_json(args.output/'result.json', result)

    save()
    from tools.narration.benchmark_parallel import memory, DA
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
        import numpy as np
        import torch
        import soundfile as sf
        from safetensors.torch import load_file
        from tokenizers import Tokenizer
        from transformers import LlamaConfig, LlamaForCausalLM
        from transformers.models.llama.modeling_llama import LlamaRotaryEmbedding
        torch.set_num_threads(2)
        torch.set_num_interop_threads(1)
        if args.accurate_fp16:
            torch.backends.cuda.matmul.allow_fp16_reduced_precision_reduction = False
            result['fp16ReducedPrecisionReduction'] = False
        if not torch.version.hip or not torch.cuda.is_available():
            raise RuntimeError('No HIP GPU; refusing CPU substitution')
        device = torch.device('cuda:0')
        result.update(torch=torch.__version__, hip=torch.version.hip, gpu=torch.cuda.get_device_name())
        start_load = time.perf_counter()
        save('loading-original-language-model')
        config = LlamaConfig.from_dict(json.loads((ROOT/'tools/plapre3/artifacts/config.json').read_text()))
        config._attn_implementation = 'sdpa'
        assert config.rope_theta == 10000
        with torch.device('meta'):
            lm = LlamaForCausalLM(config)
        weights = load_file(str(ROOT/'tools/plapre3/artifacts/model.safetensors'), device='cpu')
        weights['lm_head.weight'] = weights['model.embed_tokens.weight']
        lm.load_state_dict(weights, assign=True, strict=True)
        lm.tie_weights()
        lm.model.rotary_emb = LlamaRotaryEmbedding(config, device='cpu')
        lm.eval()
        del weights
        tokenizer = Tokenizer.from_file(str(ROOT/'tools/plapre3/artifacts/tokenizer.json'))
        voice = next(v for v in json.loads((ROOT/'tools/plapre3/export/voices.json').read_text()) if v['key'] == 'ida')
        def prompt(text, target):
            ids = torch.tensor([[8000] + tokenizer.encode(text, add_special_tokens=False).ids + [8001]], device=target)
            prefix = torch.tensor(voice['prefix'], dtype=lm.dtype, device=target).reshape(1, 1, -1)
            return torch.cat([prefix, lm.get_input_embeddings()(ids)], dim=1)

        # Independent CPU vs GPU prefill/cache check before stochastic synthesis.
        parity_text = 'Hun åbner sin bog og læser videre.'
        cpu_logits = []
        with torch.inference_mode():
            past = None
            current = prompt(parity_text, 'cpu')
            for i in range(4):
                output = lm(inputs_embeds=current, past_key_values=past, use_cache=True)
                cpu_logits.append(output.logits[0, -1].clone())
                past = output.past_key_values
                current = lm.get_input_embeddings()(torch.tensor([[8002+i*127]]))
            del output, past, current
        dtype = torch.float16 if args.precision == 'fp16' else torch.float32
        save('moving-language-model-to-gpu')
        lm.to(device=device, dtype=dtype)
        gc.collect()
        with torch.inference_mode():
            past = None
            current = prompt(parity_text, device)
            for i in range(4):
                output = lm(inputs_embeds=current, past_key_values=past, use_cache=True)
                actual = output.logits[0, -1].float().cpu()
                delta = float((actual-cpu_logits[i]).abs().max())
                tolerance = .15 if args.precision == 'fp16' else .002
                row = dict(stage='lm-prefill-and-cache', step=i, maxAbsoluteLogitError=delta,
                           sameTopToken=int(actual.argmax()) == int(cpu_logits[i].argmax()), tolerance=tolerance)
                result['validation'].append(row)
                if not math.isfinite(delta) or delta > tolerance or not row['sameTopToken']:
                    raise RuntimeError('Language-model GPU parity failed: '+str(row))
                past = output.past_key_values
                if any(t.device.type != 'cuda' for pair in past.to_legacy_cache() for t in pair):
                    raise RuntimeError('KV cache escaped GPU')
                current = lm.get_input_embeddings()(torch.tensor([[8002+i*127]], device=device))
            del output, past, current, cpu_logits

        save('loading-synthesis-codec-and-vocoder')
        from tools.pico.audio import KanadeSynthesis, PortableHiFT, load_hift
        codec = KanadeSynthesis()
        vocoder = PortableHiFT(load_hift()).eval()
        # Validate acoustics with identical tokens/noise, separately from GPU RNG.
        validation_tokens = torch.arange(20, dtype=torch.long)*127
        validation_speaker = torch.tensor(voice['embedding'], dtype=torch.float32)[None]
        rng = torch.Generator().manual_seed(41)
        validation_phase = torch.rand((1, 9), generator=rng)
        validation_phase[:, 0] = 0
        validation_noise = torch.randn((1, 20*960, 9), generator=rng)
        with torch.inference_mode():
            cpu_mel = codec(validation_tokens, validation_speaker)
            cpu_wave = vocoder(cpu_mel, validation_noise, validation_phase)
        codec.to(device)
        vocoder.to(device)
        for module in codec.modules():
            if isinstance(getattr(module, 'freqs_cis', None), torch.Tensor):
                module.freqs_cis = module.freqs_cis.to(device)
        speaker = torch.tensor(voice['embedding'], dtype=torch.float32, device=device)[None]
        with torch.inference_mode():
            gpu_mel = codec(validation_tokens.to(device), validation_speaker.to(device))
            gpu_wave = vocoder(cpu_mel.to(device), validation_noise.to(device), validation_phase.to(device))
            mel_delta = float((gpu_mel.cpu()-cpu_mel).abs().max())
            wave_delta = (gpu_wave.cpu()-cpu_wave)
            relative_rms = float(wave_delta.square().mean().sqrt()/cpu_wave.square().mean().sqrt().clamp(min=1e-8))
            result['validation'].append(dict(stage='codec-identical-tokens', maxAbsoluteMelError=mel_delta))
            result['validation'].append(dict(stage='vocoder-identical-mel-noise', maxAbsoluteWaveError=float(wave_delta.abs().max()), relativeRmsError=relative_rms))
            if not math.isfinite(mel_delta) or not math.isfinite(relative_rms) or mel_delta > .01 or relative_rms > .01:
                raise RuntimeError('Acoustic GPU numeric validation failed')
            del gpu_mel, gpu_wave, cpu_mel, cpu_wave, wave_delta
        if any(p.device.type != 'cuda' for model in (lm, codec, vocoder) for p in model.parameters()):
            raise RuntimeError('A neural parameter remains on CPU')
        result['neuralParameterDevices'] = ['cuda:0']
        result['modelLoadAndLmValidationSeconds'] = time.perf_counter()-start_load
        torch.cuda.synchronize()

        @torch.inference_mode()
        def render(text, seed):
            generator = torch.Generator(device=device).manual_seed(seed)
            current = prompt(text, device)
            past = None
            codes = []
            torch.cuda.synchronize()
            started = time.perf_counter()
            for index in range(751):
                output = lm(inputs_embeds=current, past_key_values=past, use_cache=True)
                past = output.past_key_values
                values, indices = output.logits[0, -1].float().topk(50)
                probs = (values/.7).softmax(-1)
                # Retain the token crossing top-p, matching the existing sampler.
                probs = probs.masked_fill((probs.cumsum(-1)-probs) >= .95, 0)
                selected = torch.multinomial(probs/probs.sum(), 1, generator=generator)
                token = indices[selected]
                number = int(token.item())  # One scalar for EOS/control, no logits readback.
                if number in (0, 21223):
                    break
                if not 8002 <= number < 20802:
                    raise RuntimeError('Invalid speech token: '+str(number))
                if len(codes) >= 750:
                    raise RuntimeError('Speech token cap reached before EOS; refusing clipped audio')
                codes.append(token-8002)
                current = lm.get_input_embeddings()(token.reshape(1, 1))
                if index and index % 50 == 0:
                    print('generated tokens', index, flush=True)
            else:
                raise RuntimeError('No EOS')
            if not codes:
                raise RuntimeError('Empty speech')
            torch.cuda.synchronize()
            lm_seconds = time.perf_counter()-started
            token_tensor = torch.cat(codes).long()
            mel = codec(token_tensor, speaker)
            torch.cuda.synchronize()
            codec_done = time.perf_counter()
            phase = torch.rand((1, 9), device=device, generator=generator)
            phase[:, 0] = 0
            noise = torch.randn((1, len(codes)*960, 9), device=device, generator=generator)
            waveform = vocoder(mel, noise, phase).reshape(-1)
            torch.cuda.synchronize()
            done = time.perf_counter()
            if waveform.device.type != 'cuda' or mel.device.type != 'cuda':
                raise RuntimeError('Acoustic output escaped GPU')
            wave = waveform.float().cpu().numpy()
            if len(wave) != len(codes)*960 or not np.isfinite(wave).all() or abs(wave).max() < 1e-6:
                raise RuntimeError('Invalid output waveform')
            return wave, dict(text=text, seed=seed, stopToken=number, tokenCount=len(codes),
                             tokenSha256=hashlib.sha256(token_tensor.cpu().numpy().tobytes()).hexdigest(),
                             lmSeconds=lm_seconds, codecSeconds=codec_done-started-lm_seconds,
                             vocoderSeconds=done-codec_done, synthesisSeconds=done-started,
                             audioSeconds=len(wave)/24000, rtf=(done-started)/(len(wave)/24000))

        save('warmup-short-speech')
        warm, row = render(parity_text, 20261024)
        sf.write(args.output/'warmup.wav', warm, 24000, subtype='PCM_16')
        result['warmup'] = row
        save('generating-authored-passages')
        for repeat in range(args.repeat):
            for index, text in enumerate(DA[:args.count]):
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
