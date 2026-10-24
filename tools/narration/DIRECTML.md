# Experimental desktop DirectML narration

20 October 2026. Tested on the physical Windows desktop's AMD Radeon RX 5700 XT.
The Android emulator is not involved in these measurements. This is an optional
desktop export experiment; no GPU runtime or model is added to the Android APK.

## Working path

Røst 350M has two optional FP32 DirectML profiles. `directml` moves its T3 token
model to GPU and keeps waveform generation on CPU. `directml-acoustic` also moves
the acoustic flow network and learned waveform convolutions to GPU. FFTs, F0/
excitation and watermarking remain on CPU, with explicit transfers. Fixed speaker
conditioning is computed once on CPU and retained on GPU. Eager attention and
no_grad replace the tested plugin's incompatible
inference-tensor path. The original forward function, model weights, sampler and
EOS/token-budget protections remain in use. A capped generation is not accepted
as a completed sentence.

On six same-seed short Danish cases, the reference CPU stack generated 28.38 s
of audio in 81.23 s (RTF 2.86). The GPU-token/CPU-waveform experiment generated
the same 28.38 s in 40.49 s (RTF 1.43), about 2.0x faster. All six saved PCM WAV
outputs were byte-identical. This is short-sample evidence, not a completed
whole-book benchmark or independent certification of Danish voice quality.

The improved token/acoustic profile took 28.95 s for the same 28.38 s of audio
(RTF 1.02, about 2.8x faster than the measured CPU stack). The five samples after
startup averaged RTF 0.96. These were not bit-identical: their PCM RMSE from CPU
was 2.1–2.8e-6 on a -1..1 scale, with identical lengths. Fixed-input acoustic
comparisons measured mel RMSE 1.52e-6 and waveform-convolution RMSE 1.65e-7.
Longer exporter checks, complete timings and limitations are recorded in
`android-reader/IMPLEMENTATION-v0.13.1-GPU.md` in the workspace.

An unmodified full PyTorch waveform offload is not supported: DirectML's
complex-float operation in the waveform path terminated a probe process. The
improved adapter bridges those FFT operations to CPU, rather than dropping them
or changing the audio algorithm. A nearest-neighbor upsample in the flow network
also falls back to CPU. Inference weight normalization is materialized once before
moving waveform convolutions to GPU. No
driver, clock, voltage or system power-limit changes were made. Basic GPU matrix
arithmetic passed, but a matrix test is not a speech throughput measurement.
The Windows GPU Engine counters returned invalid percentages during a probe;
no 100% utilization claim is made from those values.

## Separate environment

The working CPU environment pins PyTorch/torchaudio 2.6.0. The available tested
torch-directml 0.2.5.dev240914 requires PyTorch 2.4.1. These are intentionally
separate environments; do not install the experimental packages into `.venv`.
This is a tested compatibility adapter, not the upstream package's supported
dependency combination.

With the CPU environment already installed:

```powershell
python tools/narration/setup_directml.py
$gpuPython = '.\tools\narration\.venv-dml\Scripts\python.exe'
```

The setup uses the GPU environment's own torch/torchaudio and reads the existing
CPU environment's other speech dependencies without modifying them. All downloads
go to the project environments/cache; no driver installation is performed.

## Use an independent GPU job

```powershell
& $gpuPython tools/narration/narrate.py plan --input 'R:\Books\book.epub' --engine roest --accelerator directml-acoustic --voice-reference 'R:\Voices\narrator.wav'
& $gpuPython tools/narration/narrate.py generate --job JOB_ID --model-dir 'R:\FolioModels\roest' --cpu-threads 4 --limit-chapters 1
```

The accelerator is chosen when planning. CPU, token-only GPU and acoustic-GPU
plans each use a different job identity and cache. Existing v0.13 CPU plans remain resumable with their
original IDs. Editing an existing plan to change accelerators is rejected when
its progress binding differs. The experimental DirectML option currently accepts
only Røst, not an untested English model.

`progress.json` records the execution profile, token/waveform placement and
adapter. Loading failures are reported explicitly; there is no silent switch to
CPU. A generated `.foliobook` still contains the original EPUB and ordinary MP3
chapter audio; its phone playback does not require DirectML.

## Repeat measurements

`gpu_probe.py` supports explicit CPU/DML stage probes and Røst samples.
`gpu_wave_probe.py` compares captured real acoustic inputs, fixing the complete
meanflow noise state; its combined mode runs the improved full speech adapter.
`gpu_onnx_speech.py` tests the existing Plapre ONNX exports, optionally keeping
decoder caches on GPU with IO binding. Use a separate `--profile` run for ONNX
node assignment; profiling is disabled for normal timing because it adds overhead.
Keep loading, warm synthesis and encoding timings separate. Record CPU fallback,
stopping failures and output checks rather than reporting a token-only result as
complete speech speed. Local results and generated audio belong in `gpu-results/`
and are excluded from the public source package.
