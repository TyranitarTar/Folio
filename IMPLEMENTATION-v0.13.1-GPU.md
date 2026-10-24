# Folio 0.13.1 — desktop GPU narration investigation

20 October 2026. This implements desktop export tooling. The Android app remains
0.13.0; the planned audiobook-folder UI is separate work and no new APK is published
by this investigation. Miso's rejected animation/writing is recorded as a future
substantial redesign in `design/cat-cafe/TODO.md`.

## Outcome

Røst 350M now has a working experimental **DirectML token/acoustic backend** on
the physical **Radeon RX 5700 XT**, with unsupported FFTs and excitation explicitly
kept on CPU. A complete 42-passage export generated 146.92 seconds of speech in
154.578 seconds of synthesis (RTF 1.052). Loading, synthesis, encoding and export
together took 176.406 seconds. The earlier token-only GPU backend needed 212.939
seconds for 144.97 seconds of speech (RTF 1.469; 235.125 seconds including setup
and export). These longer runs use the same input text but unseeded synthesis;
they are not identical generated takes.

The separate same-seed comparisons below generated identical audio lengths on
CPU/GPU. The improved path is approximately **2.8 times faster than the measured
CPU stack on those six cases**, rather than an assertion that the GPU operates
at 100% or that all hardware/software configurations have that speedup.

## Hardware and runtime

- Physical Windows desktop: Ryzen 7 5800X, 8 cores / 16 logical processors,
  16 GB RAM, Radeon RX 5700 XT. Driver 32.0.21030.2001, Windows build 19045.
- No Android emulator or concurrent heavy narration test during timed samples.
  Other desktop applications remained open; ambient host load is not controlled
  like a dedicated laboratory machine.
- Existing CPU environment unchanged: Python 3.10.6, torch/torchaudio 2.6.0+cpu,
  Chatterbox 0.1.7, transformers 5.2.0, four configured CPU threads.
- Separate `.venv-dml`: torch/torchaudio 2.4.1+cpu, torchvision 0.19.1+cpu,
  torch-directml 0.2.5.dev240914, onnxruntime-directml 1.23.0, numpy 1.26.4.
  The `+cpu` wheel name does not mean DirectML is unused: the plugin supplies the
  `privateuseone:0` GPU device. This differs from Chatterbox's upstream torch pin
  and remains an experimental compatibility combination.
- No driver, clock, voltage or power-limit changes. No Hugging Face credential,
  remote speech service or private-book upload. Models/reference already present
  locally are reused. The additional runtime is desktop-only.

## Same-seed finished-audio measurements

RTF means synthesis seconds / audio seconds; lower is faster. Load times are
excluded from this table and retained separately in the local JSON results.

| Model / placement | Cases | Audio seconds | Synthesis seconds | RTF | Minutes per audio hour |
| --- | ---: | ---: | ---: | ---: | ---: |
| Røst: CPU reference stack | 6 | 28.38 | 81.23 | 2.862 | 171.7 |
| Røst: GPU tokens, CPU waveform | 6 | 28.38 | 40.49 | 1.427 | 85.6 |
| Røst: GPU tokens/flow/convolutions, CPU FFT/excitation | 6 | 28.38 | 28.95 | 1.020 | 61.2 |
| Plapre 3 FP32: CPU | 3 | 16.56 | 28.01 | 1.691 | 101.5 |
| Plapre 3 FP32: GPU LM and acoustics | 3 | 16.56 | 58.70 | 3.545 | 212.7 |
| Plapre 3 FP32: CPU LM, GPU acoustics | 3 | 16.56 | 25.75 | 1.555 | 93.3 |
| Pico INT8 LM: CPU | 3 | 18.64 | 8.58 | 0.460 | 27.6 |
| Pico INT8 LM: CPU LM, GPU acoustics | 3 | 18.64 | 6.75 | 0.362 | 21.7 |

For Røst, the CPU and GPU stacks differ in torch version and attention backend;
these are practical measured configurations, not an experiment holding every
software variable constant. There are only six short matched cases; warm samples
are repeated texts with different seeds. The five improved Røst samples after
startup averaged RTF 0.957; the longer exporter averaged 1.052. Sustained real-time
generation is therefore not guaranteed. Plapre/Pico are short desktop experiments
only, not implemented audiobook exporters or phone performance measurements.

## What changed

`tools/narration/directml_backend.py` supplies two explicitly selected profiles:

- `directml` / `directml-t3-v1`: T3 FP32 weights and fixed voice conditioning
  on GPU, CPU S3Gen waveform generation and watermarking.
- `directml-acoustic` / `directml-acoustic-v1`: additionally moves the acoustic
  flow network and learned HiFT waveform convolutions to GPU. F0, excitation,
  complex FFTs and watermarking remain on CPU. An unsupported nearest-neighbor
  upsample inside flow is reported by DirectML as CPU fallback. Transfers and
  synchronized CPU output readback are included in synthesis measurements.

Fixed conditioning is computed/checked once on CPU, cached on GPU, and eager
attention plus `no_grad` avoids this plugin's inference-tensor incompatibility.
The acoustic adapter binds compatible wrappers on the loaded model instance;
installed package source and separately loaded CPU classes are untouched.
Waveform weight-normalization parametrizations are materialized once before
GPU transfer, eliminating repeated unsupported weight-normalization calls.
The original weights, sampler, diffusion step count, output lengths, watermark
and EOS/token-budget protections remain active. No quantization or shortened
sentence budget was used to create a faster Røst/Plapre 3 result.

CPU, token-only GPU and acoustic-GPU plans have separate deterministic job/cache
identities. Existing 0.13 CPU plans retain their original IDs and resumability.
Changing a job's accelerator cannot silently reuse its recorded chunks. Jobs
record the actual adapter, execution profile and component placement. English
models are rejected for these unvalidated Røst-only GPU profiles.

Use `tools/narration/setup_directml.py` and `tools/narration/DIRECTML.md` for
reproduction. Choose `--accelerator directml-acoustic` during planning, then run
generation with the isolated GPU interpreter. CPU remains the default.

## Verification and limits

- Ten desktop narrator regression tests pass, including legacy CPU identity,
  separate GPU cache identities, accelerator-change rejection and rejecting an
  untested English GPU model before creating a job.
- Six token-only GPU WAVs were byte-identical to the CPU reference WAVs.
- Six improved-GPU WAVs had identical lengths; PCM RMSE from CPU was
  2.06e-6–2.82e-6 on a -1..1 scale, versus reference RMS 0.052–0.063.
- Acoustic flow was separately tested with the complete meanflow noise state
  fixed, including its internally randomized reference prefix. Mel RMSE was
  1.52e-6. An earlier probe fixed only target noise and is not evidence of
  numerical equivalence; its timings are retained but superseded by this check.
- Captured-input HiFT convolution comparison measured waveform RMSE 1.65e-7.
  CPU decode took about 0.58–0.64 s; GPU convolutions with CPU FFTs took about
  0.14–0.20 s on that sample.
- Both complete 42-cue `.foliobook` exports pass ZIP integrity, original EPUB
  SHA equality, full/ordered text coverage, completed-chapter and cue-bound checks.
- Offline small-Whisper Danish spot checks supplement numerical validation.
  Recognition still makes common-word errors on this voice; ASR is not proof of
  pronunciation or audiobook quality. Human listening review remains required.
- ONNX profiling was separate from timing. A Plapre 3 diagnostic recorded actual
  DirectML kernel events for LM, Kanade and HiFT, plus CPU shape/fallback events.
  Enabling profiling slows the autoregressive loop, so that run is not used in
  the performance table. Plapre 3 GPU token generation is genuinely slower on this
  stack; merely selecting more GPU work would be counterproductive.

## Utilization: what the desktop percentages mean

The first completed benchmark had no narration process left when the user asked
about 1% CPU / 33% GPU. During a later active acoustic probe, process CPU-time
sampling measured **18.9–25.1% of the whole 16-thread CPU**, roughly three to
four logical processors. The inactive virtual-environment launcher used 0%;
the child Python process did the computation.

GPU autoregressive decoding produces one token at a time, repeatedly dispatching
small operations and synchronizing sampling. GPU/CPU stages also have transfer
boundaries. High total utilization is therefore not automatic. The earlier
token-only path left substantial waveform work on CPU; the improved path removes
much of that bottleneck. End-to-end audio throughput, rather than a utilization
target, determines which path is useful.

Windows GPU Engine performance counters returned invalid values above 100% in
one collection, so those percentages were rejected. No trustworthy aggregate
GPU-utilization percentage is claimed. Device assignment, completed numerical
GPU work, synchronized output checks and ONNX kernel profiles establish actual
GPU execution, but do not imply every operation executes on GPU.

## Failed routes and next useful work

- Unmodified DirectML T3 inference failed on inference-tensor version counters;
  cached CPU conditioning, eager attention and `no_grad` produced the working path.
- Direct full waveform offload terminated a native probe with unsupported
  `ComplexFloat` (exit -1073740791). The CPU FFT bridge is the implemented solution.
- Plapre 3 all-GPU ONNX decoding with GPU KV-cache IO binding was slower than CPU.
  Keep CPU tokens for this tested stack; do not claim selecting DirectML speeds
  every model. Pico LM GPU inference was not measured in this investigation.
- Future work: larger paired sample sets, genuine batch decoding/dispatch
  reduction, chapter-length thermal/load measurements, and only then optional
  reduced precision with explicit quality checks. Pipeline overlap alone is not
  guaranteed to help when token/acoustic stages already share one GPU.

The supplied 22-chapter text has 108,956 words. The interrupted CPU chapter sample
had 4,296 words / 1,482.34 s of audio, suggesting about 10.4 audio hours for all
22 chapters. Multiplying by the longer GPU RTF gives **about 11 synthesis hours**,
before load/export/retries/host-load changes. This is a provisional extrapolation,
not a whole-book render. A separate GPU job has started for chapter 1 only; the
318 cached CPU cues remain intact and are not mixed with the new GPU job.

Local detailed JSON, WAVs and bundles are in `tools/narration/gpu-results/v0131/`;
audit logs are in `android-reader/audit/v0131-*`. These and private user-book jobs
are excluded from public source/release archives. The source allowlist includes
the new desktop tooling/documentation and keeps Pico/Plapre tools under their
expected `tools/` hierarchy for cross-tool imports in a future release; existing 0.13.0
release archives have not been overwritten.
