"""Compare complete dynamic ONNX acoustic outputs with the PyTorch reference."""
import json
import time
import numpy as np
import onnxruntime as ort
import torch
from audio import ROOT, KanadeSynthesis, PortableHiFT, load_hift

torch.set_num_threads(4)
options = ort.SessionOptions()
options.intra_op_num_threads = 4
options.inter_op_num_threads = 1
kanade = ort.InferenceSession(str(ROOT / "export/kanade.onnx"), options, providers=["CPUExecutionProvider"])
hift = ort.InferenceSession(str(ROOT / "export/hift.onnx"), options, providers=["CPUExecutionProvider"])
reference = KanadeSynthesis().portable()
vocoder = PortableHiFT(load_hift()).eval()
result = json.loads((ROOT / "results/lm-int8-42.json").read_text(encoding="utf-8"))
voice = np.asarray(json.loads((ROOT / "artifacts/pico/speakers.json").read_text())["ida"], dtype=np.float32)[None]
report = []
for count in [10, len(result["tokens"]), 100, 250]:
    tokens = np.resize(np.asarray(result["tokens"], np.int64), count)
    torch.manual_seed(42)
    initial = torch.rand((1, 9)); initial[:, 0] = 0
    noise = torch.randn((1, count * 960, 9))
    start = time.perf_counter()
    mel = kanade.run(None, {"tokens": tokens, "speaker": voice})[0]
    wave = hift.run(None, {"mel": mel, "noise": noise.numpy(), "initial_phase": initial.numpy()})[0]
    elapsed = time.perf_counter() - start
    with torch.no_grad():
        refmel = reference(torch.from_numpy(tokens), torch.from_numpy(voice)).numpy()
        refwave = vocoder(torch.from_numpy(mel), noise, initial).numpy()
    row = dict(tokens=count, mel_max_error=float(np.abs(mel-refmel).max()),
               wave_rmse=float(np.sqrt(np.mean((wave-refwave)**2))),
               samples=wave.shape[-1], seconds=elapsed, acoustic_rtf=elapsed / (count / 25))
    assert np.isfinite(wave).all() and wave.shape == (1, count * 960)
    assert row["mel_max_error"] < 0.0001 and row["wave_rmse"] < 0.005, row
    print(json.dumps(row), flush=True)
    report.append(row)
(ROOT / "results/acoustic-validation.json").write_text(json.dumps(report, indent=2))
