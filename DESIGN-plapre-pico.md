# Plapre Pico: implementation feasibility and delivery plan

Evaluated 16 October 2026 against the released Folio 0.9.0 source. The user selected Pico as the next Danish engine, requested this evaluation first, and deferred Plapre 3, Røst v3 Chatterbox and the cat game. This document implements the planning step; it does not claim an integrated engine or a new APK.

Subsequent implementation: [Folio 0.10](IMPLEMENTATION-v0.10.md) uses the validated all-ONNX alternative, with synthesis-only acoustic exports. Its measured download is 0.529 GB and installed files 0.622 GB. The analysis below records the earlier full-component budgets and alternatives; its statements about tests not yet performed describe that evaluation stage.

## Decision

Proceed with a complete offline proof of concept before exposing a Pico download in Folio. Prefer the official Q8 GGUF language model through a small llama.cpp JNI adapter, with inference-only Kanade decoding and HiFT waveform generation exported to an Android-compatible runtime. Keep an all-ONNX path as an alternative if it proves easier and matches the reference audio. Neither path is already a complete working Android TTS engine.

Reuse Folio's existing player, ordered PCM queue, listening checkpoints, loading messages, voice samples and download/install workflow. Keep English Kokoro and the working Supertonic option. Initial Pico scope is Danish narration in built-in voices; user voice cloning is outside this first implementation.

The main uncertainty is the complete waveform pipeline's quality and sustained ARM performance, rather than the ability to load a small language model. No physical Galaxy S10 or Fold7 measurements, Pico audio auditions, model downloads or conversions were performed for this evaluation.

## Verified upstream components and corrected size budget

Pico has 118,185,408 base-model parameters. Its speech generator produces Kanade content codes; those codes are not playable audio. The selected clean codec produces mel features and separately loads the HiFT vocoder to make a waveform. The codec configuration explicitly selects `hift`; older comments and the community ONNX card refer to Vocos, so follow the pinned configuration and actual loading code. [Pico](https://huggingface.co/syvai/plapre-pico), [codec configuration](https://huggingface.co/frothywater/kanade-25hz-clean/blob/main/config.yaml), [vocoder loader](https://github.com/frothywater/kanade-tokenizer/blob/main/src/kanade_tokenizer/util.py).

GB below is decimal, 1,000,000,000 bytes. These are public file sizes, not measured compressed Android downloads or RAM requirements.

| Reference component | Bytes | GB |
|---|---:|---:|
| Pico Q8 GGUF | 126,282,688 | 0.126283 |
| Pico projection, voices, tokenizer and runtime configuration | 3,385,566 | 0.003386 |
| Full published Kanade checkpoint and configuration | 562,714,621 | 0.562715 |
| Separate HiFT checkpoint | 83,390,254 | 0.083390 |
| Reference total with Q8 | 775,773,129 | **0.775773** |
| Reference total using Q4_K_M instead | 736,416,201 | **0.736416** |

The previous approximately 0.69 GB Pico and 1.91 GB Plapre 3 figures omitted the separately loaded HiFT checkpoint. Full listed components are approximately **0.776 GB for Pico Q8** and **1.992 GB for Plapre 3**. Codec/vocoder data can potentially be shared when Plapre 3 is implemented; both together would have approximately 2.122 GB of these reference components. This is not an implemented dependency system or a measured finished pack.

Pico file metadata revision: `c862d1f3fdde56042e50c30016203999513994f0`. Kanade revision: `cb2c8f10959ff0e5d3e97c9b82fcc3779c9532a5`. HiFT is `hift.pt` in `FunAudioLLM/CosyVoice2-0.5B`, revision `eec1ae6c79877dbd9379285cf8789c9e0879293d`, SHA-256 `3386cc880324d4e98e05987b99107f49e40ed925b8ecc87c1f4939432d429879`. Verify all actual downloaded files against pinned manifests before conversion. [Pico metadata](https://huggingface.co/api/models/syvai/plapre-pico?blobs=true), [Kanade metadata](https://huggingface.co/api/models/frothywater/kanade-25hz-clean?blobs=true), [HiFT metadata](https://huggingface.co/api/models/FunAudioLLM/CosyVoice2-0.5B?blobs=true).

The full Kanade checkpoint includes components that fixed-voice synthesis should not need, such as reference-audio encoders. Export only the reachable synthesis graph after checking dependencies. Conversion may reduce the pack, or add graph/embedding data; the raw total is a reference budget, not a guaranteed upper bound. Measure final archive size, installed size, staging space, APK runtime growth and peak process memory separately.

## Runtime alternatives

| Path | Advantages | Remaining work and concerns |
|---|---|---|
| Official Pico Q8 GGUF + llama.cpp; exported Kanade/HiFT | One compact LM file, official quantization, Android build support in llama.cpp. Preferred first prototype. | Implement speaker-prefix embedding, exact tokenization/sampling/stopping, JNI cancellation and complete acoustic exports. New native code and runtime compatibility work are required. |
| Entire pipeline in ONNX | One runtime family, existing community LM conversion as a reference. | Current community INT8 prompt/decode files duplicate model data; separate embedding table is also required. Acoustic ONNX models are absent from that repository. Conversion reports generation divergence and uses stopping heuristics, so it cannot be treated as a verified narration engine. |
| Embed the reference Python/CUDA application | Useful desktop reference. | Unsuitable as the Android implementation. Reimplement the inference path rather than ship a desktop environment. |

The existing community INT8 ONNX files, speaker projection, embedding table and configuration plus the full Kanade/HiFT checkpoints total approximately **0.941 GB**. Its LM-only size and desktop token throughput do not describe the whole phone pipeline. Treat this conversion as a primary implementation reference, not as a tested quality baseline. [Conversion and limitations](https://huggingface.co/varsan-g/plapre-pico-ONNX), [file metadata](https://huggingface.co/api/models/varsan-g/plapre-pico-ONNX?blobs=true).

llama.cpp accepts embedding or token batches, making a speaker-embedding prefix followed by token batches a plausible way to avoid a separate full embedding table. Confirm numerical and positional parity before relying on that design. Do not use chat templates, automatic BOS/EOS insertion or a stock text-chat wrapper for speech prompts. [Android support](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md), [input API](https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h).

Folio ships ONNX Runtime through sherpa-onnx. That does not automatically expose generic inference to Kotlin or establish that the existing build contains every required operator. Check its version, exported C API and operator coverage before choosing either a small JNI bridge or a coordinated runtime rebuild. Avoid packaging two conflicting copies of `libonnxruntime.so` through an additional AAR. CPU/arm64 is the first phone target; GPU/NPU acceleration requires separate evidence.

## Changes required in Folio

- `LocalSpeechEngine.kt`: add the Pico adapter returning validated mono PCM; initialize, cancel and release native state on the existing worker. Only the selected engine remains resident. Add x86_64 support for emulator verification if the chosen runtime permits it.
- `SpeechService.kt`: add Pico settings and voice identity; make synthesis-at-1x with playback-rate control an explicit backend capability instead of a Supertonic-only conditional. Preserve source-sample checkpoints at 0.5–3x. Extend loading, preview, cancellation and queue replacement paths.
- `AiListeningStore.kt`: include pinned LM/codec/vocoder revisions, voice, normalization/sampling policy and runtime identity in Pico cache keys. Do not alter existing Kokoro/Supertonic keys. If regenerating a different waveform, restart the same safe passage rather than apply an old sample offset to it.
- `VoicePackCatalog.kt`: backend and runtime are currently restricted to two engines and sherpa-onnx 1.13.8. File and archive caps are 512 MiB, expanded total 768 MiB, and voice IDs are numeric. The full Kanade file exceeds the per-file cap; some possible exports exceed other caps. Use explicit supported runtime identities, stable voice mapping and appropriate pinned per-pack bounds; retain checksum, extraction and URL restrictions.
- `VoicePackStore.kt` / `VoicePackWorker.kt`: reuse verified staging, resumable downloads and cancellation. Account for old installed versions, archive, staging and headroom when calculating required free space. Start with one atomic self-contained Pico pack. Shared acoustic-component dependencies and removal accounting belong before shipping Plapre 3, rather than a speculative download graph now.
- `VoicePacksPanel.kt`, `SpeechPanel.kt`, `ReaderPlaybackBar.kt`, `LibraryBackup.kt`, `VerifiedSpeechAssets.kt`: extend selections, validation, loading text, downloaded-file resolution, backup and recovery consistently. Provide authentic same-text Danish samples before downloading and a local preview after installation. Do not display an installable pack until its complete engine and archive are usable.

Book extraction, visual/guided positions and the one persistent AudioTrack remain the source of truth. A Pico engine produces audio; it does not open its own competing media player or declare passage completion. Prefetched audio must not advance the listening cursor. Retain passage highlighting without inventing word timings from code-token counts.

## Delivery sequence and acceptance

1. **Reference and access.** Confirm official Pico access conditions and weight/runtime/voice attribution terms; use accepted official assets or a verified licensed conversion, never bake a Hugging Face credential into Folio. Create original Danish evaluation passages covering dialogue, abbreviations, dates, decimals, long compounds and names. Record exact versions and deterministic diagnostic settings.
2. **Complete waveform proof.** Validate correct token IDs and speaker projection dimensions from the selected Pico artifacts, not Nano constants. Establish an unquantized reference, then compare Q8. Export and run Kanade synthesis plus HiFT without CUDA/Python on the inference target. Check finite 24 kHz PCM, intelligibility and sentence endings before building the download UI.
3. **Ending and cancellation correctness.** Derive all termination IDs from the exact tokenizer/model. A token/time cap, cancellation or detected loop is a failed/incomplete generation, not successful end-of-sentence audio. Retry only in bounded fashion, shortening at known text boundaries if needed; otherwise report the failure and keep the current listening passage. Do not force EOS at a guessed duration to hide missing words.
4. **Android proof.** Measure cold load, first audio, full text-to-PCM time, peak memory and 20-minute thermal behavior. Include both the LM and acoustic stages. Benchmark on Galaxy S10 and Fold7 when available; emulator success does not establish phone speed. Target full-pipeline real-time factor below 0.7 at 1x for useful headroom; 1.5x/3x need below 0.667/0.333 respectively before headroom. These are proposed acceptance targets, not measured results. Buffering remains explicit when generation falls behind.
5. **Integrate and package.** Add the validated backend and settings, then the checksummed downloadable pack and authentic samples. Measure the finished archive rather than publish the reference budget as a download size. Keep model weights out of the base APK; disclose measured additional runtime size.
6. **Release validation.** Exercise uninterrupted passage/chapter transitions, rate changes, pause/resume, process death, screen-off playback, seek/read-from-here, route changes, pack cancellation/resume/removal and backup restore. Assert no overlapping audio, cut-off tails or cursor advancement during generation. Re-run existing English/Danish speech and reader regressions. Mark Pico experimental until physical-device listening and performance acceptance are complete.

Use Danish listening comparisons against current Supertonic as the practical quality test. Neither Pico nor Plapre 3 has an entry on the inspected Artificial Analysis provider board, and a language model's size is not a quality score. [Benchmark reference](https://artificialanalysis.ai/text-to-speech/leaderboard/provider-voice).

## Deferred work

- [ ] **Plapre 3:** higher-resource Danish pack; compare with Pico, test its FP32 recommendation and exact prompt/runtime compatibility, then implement shared acoustic components if justified. Original complete listed data approximately 1.992 GB. [Model](https://huggingface.co/syvai/plapre-3).
- [ ] **Røst v3 Chatterbox 350M:** separate Danish quality/runtime investigation after Pico; audition and measure a complete selected/exported pipeline, rather than assume the full repository is the needed pack. [Model](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m).
- [ ] **Folio Cat Café:** retain the existing proposal for reading/listening rewards, gentle streaks, adoption, decorations and a small incremental loop. Implement durable session accounting before rewards. See [the detailed design](DESIGN-next-voices-and-cat-game.md).
