"""Compare meaningful cached Danish speech tokens through CPU/HIP acoustics."""
from pathlib import Path
import json
import sys
import traceback

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))


def main():
    import torch
    import numpy as np
    import soundfile as sf
    from tools.pico.audio import KanadeSynthesis, PortableHiFT, load_hift
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    out = ROOT/'android-reader/audit/gpu-rocm-2026-10-24/meaningful-codec-parity'
    out.mkdir(parents=True, exist_ok=True)
    result = dict(passed=False, fixture='tools/pico/results/lm-int8-42.json')
    try:
        fixture = json.loads((ROOT/result['fixture']).read_text())
        assert fixture['stop'] == 'eos'
        tokens = torch.tensor(fixture['tokens'], dtype=torch.long)
        speakers = json.loads((ROOT/'tools/pico/artifacts/pico/speakers.json').read_text())
        speaker = torch.tensor(speakers[fixture['speaker']], dtype=torch.float32)[None]
        codec = KanadeSynthesis()
        vocoder = PortableHiFT(load_hift()).eval()
        rng = torch.Generator().manual_seed(41)
        phase = torch.rand((1, 9), generator=rng)
        phase[:, 0] = 0
        noise = torch.randn((1, len(tokens)*960, 9), generator=rng)
        with torch.inference_mode():
            cpu_mel = codec(tokens, speaker)
            cpu_wave = vocoder(cpu_mel, noise, phase).reshape(-1)
            codec.to('cuda')
            vocoder.to('cuda')
            for module in codec.modules():
                value = getattr(module, 'freqs_cis', None)
                if isinstance(value, torch.Tensor):
                    module.freqs_cis = value.to('cuda')
            gpu_mel = codec(tokens.to('cuda'), speaker.to('cuda'))
            # Hold the reference mel/noise fixed for an isolated vocoder check.
            isolated = vocoder(cpu_mel.to('cuda'), noise.to('cuda'), phase.to('cuda')).reshape(-1)
            # Also compare the whole acoustic chain including codec differences.
            gpu_wave = vocoder(gpu_mel, noise.to('cuda'), phase.to('cuda')).reshape(-1)
            assert gpu_mel.device.type == 'cuda' and gpu_wave.device.type == 'cuda'
            torch.cuda.synchronize()
        reference = cpu_wave.numpy()
        actual = gpu_wave.cpu().numpy()
        isolated_delta = isolated.cpu().numpy()-reference
        delta = actual-reference
        rms = float(np.sqrt(np.mean(reference.astype(np.float64)**2)))
        result.update(text=fixture['text'], tokenCount=len(tokens), audioSeconds=len(reference)/24000,
                      referencePeak=float(abs(reference).max()), referenceRms=rms,
                      maxAbsoluteMelError=float((gpu_mel.cpu()-cpu_mel).abs().max()),
                      isolatedVocoderRelativeRms=float(np.sqrt(np.mean(isolated_delta.astype(np.float64)**2))/rms),
                      chainRelativeRms=float(np.sqrt(np.mean(delta.astype(np.float64)**2))/rms),
                      chainMaximumAbsoluteError=float(abs(delta).max()),
                      finite=bool(np.isfinite(actual).all()))
        result['passed'] = (result['finite'] and rms > .001 and result['maxAbsoluteMelError'] < .01
                            and result['isolatedVocoderRelativeRms'] < .01 and result['chainRelativeRms'] < .02)
        sf.write(out/'cpu-reference.wav', reference, 24000, subtype='PCM_16')
        sf.write(out/'gpu.wav', actual, 24000, subtype='PCM_16')
    except Exception:
        result['error'] = traceback.format_exc()
    (out/'result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(result, indent=2, ensure_ascii=False), flush=True)
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    from tools.narration.rocm_trial_support import gpu_trial_lease
    with gpu_trial_lease(ROOT):
        raise SystemExit(main())
