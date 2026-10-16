# Folio 0.10: experimental Danish Plapre Pico

Implementation candidate, 16 October 2026. Release status and final validation are recorded separately; this document does not certify Galaxy S10/Fold7 performance.

## Shipped design

The selected implementation is all ONNX: Pico INT8 generates content codes, a synthesis-only Kanade model decodes them to mel features, and HiFT creates 24 kHz mono PCM. A small C++ JNI bridge uses the ONNX Runtime library already shipped with Folio. Kotlin handles the pinned byte-level tokenizer, basic Danish number normalization, voice selection and engine lifecycle. Only one neural backend is resident; native work and release use Folio's existing serial worker.

Five built-in voices (Ida, Tor, Liv, Ask, Kaj) share one pack. Model data stays outside the base APK. The finished archive is **528,869,334 bytes (0.529 GB)**; installed pack files total **621,778,031 bytes (0.622 GB)**. The archive SHA-256 is `84f7ea1939719278e1e24db3751431a0cdf4d7c674a0c1d6f0a32eab9730ac2f`. Existing download/file/expanded-size limits remain sufficient after removing unused audio encoders. Archive/file hashes, safe staging, resumable transfers and atomic activation remain in force.

The original public Pico ONNX export bakes a rotary base of 10000, while its explicit `rope_parameters` configuration and the independent CoreML implementation use 100000. The derived model corrects that constant. In the initial local recognition comparison, missing clauses returned after the correction. This evidence does not establish perfect pronunciation or equivalence to the gated official reference runtime. Upstream files remain unchanged and checksum-pinned.

The Kanade CPU source applies SDPA dropout during eval, unlike its intended FlashAttention inference path. The reference/export comparison disables that dropout. Rotary operations are real-valued and retain the interleaved convention and local attention window. HiFT uses real convolutional STFT/iSTFT, preserves excitation noise and magnitude clipping, and exports complete waveform tails. Dynamic ONNX/reference checks cover 10, 66, 100 and 250 code lengths, with output exactly 960 samples per code. Standalone examples and comparison measurements are retained under workspace `tools/pico/results`.

## Narration and recovery

Pico returns one complete clip to the existing persistent AudioTrack queue. It never plays sound independently. Source frames, not generated tokens or guessed word durations, determine passage completion and saved progress. Voice changes get distinct cache identities; playback speed changes reuse Pico's normal-rate source and sample offset. Existing Kokoro/Supertonic cache identities remain unchanged.

Natural EOS is required. A token limit, generation timeout, invalid token, cancellation or invalid waveform fails the passage and keeps its listening position; none is reported as successful completion. Cancellation combines an atomic request counter with native RunOptions termination. Native ownership survives cancellation and is released on the serial worker, preventing release/generation races.

Pico uses shorter bounded source passages (up to 160 characters under the normal service path). Basic integers/decimal digits are spoken in Danish without modifying book contents or EPUB locators. Context-sensitive dates, ordinals, foreign names and abbreviations can still need pronunciation work. Passage highlighting stays accurate at the existing passage level; no fabricated word timestamps are exposed.

Read aloud, the reader bar, model loading, local previews, voice-pack samples, settings persistence and backup restore recognize Pico. Preparing/buffering remains visible. A preview reports its actual backend so a loaded pack cannot be removed under it. Samples and previews do not move book listening checkpoints.

## Build and provenance

Gradle builds the bridge for arm64-v8a, armeabi-v7a, x86_64 and x86 with pinned NDK 27.2.12479018 and CMake 3.22.1. Static libc++ avoids an additional conflicting shared C++ runtime; ELF alignment is set to 16 KB. The explicit C++ driver mode also handles Windows short-path compiler invocation. Existing speech native libraries are reused, rather than replaced.

Pico source metadata, conversion scripts and checks are documented in `pico-tools/README.md` in source bundles (workspace path `tools/pico/README.md`). Public conversion inputs are hash-verified. Model/code attribution and CC-BY-4.0, MIT and Apache-2.0 license texts accompany the pack and the in-app notices. No Hugging Face token is embedded, and synthesis sends no text/audio to a speech service.

The experimental label is deliberate. Desktop and emulator timing cannot promise continuous 1.5–3× narration on a Galaxy S10, and automatic recognition is not a Danish listening panel. Test names, missing/repeated words, endings, sustained speed, RAM, heat, battery, screen-off listening and Bluetooth on the actual phones before treating Pico as a recommended default. Existing reliable engines remain selectable.

Plapre 3, Røst v3 Chatterbox 350M and Folio Cat Café remain deferred in the [roadmap](TTS-roadmap.md).
