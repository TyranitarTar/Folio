# Folio Pico conversion and checks

These scripts use public pinned conversions of syv.ai's Danish Plapre Pico. They do not download the gated official files, submit text/audio to a speech service, or use a Hugging Face credential. Base model CC-BY-4.0 attribution remains required, regardless of an intermediate conversion's MIT label. Complete license texts and transformation notices accompany the generated pack.

Use Python 3.10, a workspace virtual environment, CPU PyTorch 2.5.1 from the official CPU wheel index, and `requirements.txt`. For optional local transcription checks, install faster-whisper 1.2.0; its recognition models stay in the workspace cache. No CUDA is needed.

1. Run `fetch.py` to download and verify public model files and pinned source. `--small-only` skips model weights.
2. Run `fix_lm.py`. This corrects the ONNX export's rotary base from the legacy 10000 to the explicit `rope_parameters` value 100000, preserving the original files.
3. Run `probe_lm.py` (or `--precision fp32`) to check natural termination and save audio tokens. A safety cap is a failure.
4. Run `audio.py --export` to build synthesis-only Kanade and HiFT ONNX graphs. The reference comparison disables the CPU implementation's unintended inference dropout. Real-valued DSP retains voiced/unvoiced excitation noise, magnitude clipping and complete overlap-add tails.
5. Run `validate_audio.py` for dynamic-length ONNX/reference comparisons. Run `qa_samples.py` for authentic Danish previews and local recognition diagnostics. The tiny recognizer is a diagnostic only; `recognize.py` uses the stronger small model for spot checks.
6. Run `make_pack.py` to produce the checksummed ZIP, samples, pinned Android catalog/identity, attribution texts and tokenizer reference fixtures. This updates workspace app source; it does not publish a release.

Folio's Gradle build compiles the native C++ bridge from `android-reader/app/src/main/cpp/pico`, linking to its existing ONNX Runtime library. NDK 27.2.12479018 and CMake 3.22.1 are pinned in Gradle. The bridge supports all four existing ABIs, static libc++, 16 KB ELF alignment, cancellation, natural EOS and one output waveform per completed utterance.

The optional `build-native.ps1` is a standalone diagnostic build; do not copy its output into `jniLibs` when using Gradle's native build, or the APK would contain duplicate libraries.

Physical Galaxy S10/Fold7 speed, heat, battery and listening quality require device acceptance. Desktop timings, emulator results and recognizer transcripts cannot establish those properties.
