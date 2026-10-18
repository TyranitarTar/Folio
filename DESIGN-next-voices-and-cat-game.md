# Folio: next voices and a reading cat game

**18 October 2026 update:** Pico and optional Plapre 3 are now implemented in released Folio versions. The original research below is historical. Cat Café now has a dedicated [full design document](design/cat-cafe/v1/CAT-CAFE-GDD.md), [visual template](design/cat-cafe/v1/index.html) and [design system/icon kit](design/cat-cafe/v1/README.md). These are design proposals; the game and new theme are not in the released APK.

Original evaluation, 14 October 2026; priorities updated 16 October 2026. The user has selected Plapre Pico as the next implementation, with evaluation first. Plapre 3, Røst v3 Chatterbox and the cat game are deferred todos. See [Pico feasibility and delivery plan](DESIGN-plapre-pico.md). This document does not claim those features are already in the APK.

## Benchmark basis

Use Artificial Analysis as the external quality reference, pinning the exact version and arena. The Provider Voice Arena reflects supplied voices; the Controlled Voice Arena uses matched reference voices. Elo scales from the two arenas are independent and must not be compared numerically. Neither the inspected boards nor the published methodology establish Danish quality or Android inference speed. [Provider leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard/provider-voice), [controlled leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard/controlled-voice), [methodology](https://artificialanalysis.ai/methodology/text-to-speech).

Provider-voice snapshot: Breeze TTS 2 1221 ±14; Fish Audio S2 Pro 1118 ±12; Kokoro 82M v1.0 1063 ±11. These are preference Elo scores with 95% intervals, not percentages. Hosted Chatterbox HD and Qwen-Audio-3.1-TTS-Plus must not lend their ratings to downloadable Chatterbox or Qwen3-TTS variants. A model absent from the board has unknown benchmark standing, not a score of zero.

## Models worth investigating

| Candidate | Language target | Published data / size caveat | Engineering decision |
|---|---|---|---|
| More Kokoro voices | English | Existing Folio pack's metadata documents 54 voice embeddings; UI exposes only Emma/Heart. | Verify speaker mapping and audition more English voices. Little or no extra model data. This changes voice choice, not model benchmark standing. |
| Pocket TTS | English | 100M CPU-oriented streaming model; includes codec/voice state. Final Android pack size not measured. | Best practical new English phone prototype. Not listed on inspected AA board, so no claimed benchmark superiority. |
| Piper talesyntese-medium | Danish | Published ONNX approximately 63 MB, plus metadata/runtime. | Lightweight Danish baseline through the existing class of runtime. Audition before claiming narration quality. |
| Plapre Pico | Danish | Corrected actual-byte totals including the separate HiFT vocoder: Q8 approximately 0.776 GB; Q4_K_M approximately 0.736 GB. Raw reference components, not Android archive sizes. | Selected next implementation, starting with a complete waveform proof and Q8 evaluation; see the dedicated plan. |
| Plapre 3 | Danish | Approximately 1.992 GB of published FP32 LM, support files, full Kanade and separate HiFT components. Converted pack unmeasured. | Deferred todo after Pico. Compare actual Danish narration and phone performance; investigate shared codec/vocoder installation. |
| Røst v3 Chatterbox 350M | Danish | Repository about 4.05 GB, including alternative generators; actual selected/converted pack is unmeasured. | Strong Danish audition lead, followed by an Android export feasibility spike. No AA ranking for this fine-tune. |
| Fish Audio S2 Pro | English / Danish advertised | 4B semantic plus 400M fast autoregressive components and codec. Several-GB deployment; exact chosen conversion unmeasured. | Benchmark-backed bilingual research candidate, initially desktop reference. Fold7 experiment only after an efficient complete port exists. |
| Breeze TTS 2 | English, Chinese | Upstream checkpoint repository 7.68 GB; current reference runtime requires CUDA and recommends 12 GB GPU memory. | Highest-priority quality reference among this verified open-weight shortlist. Large Android port/quantization task; unsuitable as a promised near-term phone feature. |

Sources: [Pocket TTS](https://huggingface.co/kyutai/pocket-tts), [Piper Danish files](https://huggingface.co/rhasspy/piper-voices/tree/main/da/da_DK/talesyntese/medium), [Plapre Pico](https://huggingface.co/syvai/plapre-pico), [Kanade codec files](https://huggingface.co/frothywater/kanade-25hz-clean/tree/main), [Røst card](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m), [Røst files](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m/tree/main), [Fish S2 Pro](https://huggingface.co/fishaudio/s2-pro), [Breeze card](https://huggingface.co/BreezeBlue/Breeze-TTS-2), [Breeze files](https://huggingface.co/BreezeBlue/Breeze-TTS-2/tree/main).

Breeze weights/self-hosted outputs carry research/non-commercial conditions; Fish weights have a research license with separate commercial licensing. Plapre and Pocket require upstream access-condition acceptance. Before distributing any converted pack, verify the exact model, voice/reference, runtime and redistribution terms. This is a packaging requirement, not a request for approval during this evaluation.

NVIDIA Magpie 357M is another possible English experiment, but the board's February variant and current July weights differ, and its score is close to Kokoro with overlapping intervals. Danish is absent from the published supported-language list. It does not currently justify priority as a demonstrated quality upgrade. [Model card](https://huggingface.co/nvidia/magpie_tts_multilingual_357m).

## Fold7 acceptance

### Danish-required low, middle and high resource tiers

Updated requirement: Danish is mandatory in every tier; a single good English voice is sufficient. Keep the existing Kokoro model as one shared English pack. Its current measured download is 0.336458 GB and installed data 0.371766 GB. Selecting only Emma does not eliminate the main 0.325561 GB model; the entire current style table is only 0.028201 GB. Reducing the English voice list therefore does not proportionally reduce model storage.

GB here means decimal gigabytes (1,000,000,000 bytes). Totals exclude the current 0.061046 GB APK, future additional runtime code, generated speech caches and temporary installation space. Only the existing packs have measured archive sizes; raw upstream component totals are used as conservative budgets for unbuilt packs. Compression/export could change them substantially.

| Resource tier | Required Danish choice | Danish download / budget | Shared English download | Fresh combined budget | State |
|---|---|---:|---:|---:|---|
| Low / practical | Supertonic 3 INT8 | 0.124 GB measured | 0.336 GB measured | 0.461 GB measured | Already released; combined installed model data 0.517 GB. |
| Middle / experimental | Plapre Pico INT8, Folio ONNX synthesis-only conversion | 0.529 GB measured archive; 0.622 GB installed | 0.336 GB measured | 0.865 GB measured fresh download | Implemented in the 0.10 candidate; initial native, playback and checkpoint checks pass. Physical-phone performance and listening acceptance remain required. |
| High / Danish specialist experiment | Røst v3 Chatterbox 350M | approximately 3.0 GB selected upstream components; allow up to 4.05 GB for full upstream repository | 0.336 GB | approximately 3.3 GB selected / 4.4 GB full-reference budget | Final converted archive and Fold7 performance unmeasured. |

If Kokoro is already installed, the additional transfer is the Danish column only. Tiers should share installed packs rather than download a second English copy. Switching tiers must preserve listening locators and safely repeat/regenerate incompatible audio. Runtime checks and model version keys are still required for new engines.

Corrected Plapre arithmetic, 16 October: actual Q8 GGUF 0.126283 GB + tokenizer/projection/configuration 0.003386 GB + full Kanade and configuration 0.562715 GB + separately loaded HiFT 0.083390 GB = 0.775773 GB. Q4_K_M gives 0.736416 GB. Earlier figures omitted HiFT and used rounded model-card labels as decimal GB. Start with Q8; decoder-only exports could change the footprint, but are not yet measured. [Complete inventory and sources](DESIGN-plapre-pico.md).

The later 0.10 implementation selected INT8 ONNX, corrected its rotary configuration and removed unused audio encoders. Its finished archive is 528,869,334 bytes; installed files are 621,778,031 bytes. Together with the existing Kokoro archive, fresh downloads total 865,327,595 bytes, excluding the APK. The earlier full-reference arithmetic above is retained as provenance and is superseded for the actual Folio Pico pack. [Implementation](IMPLEMENTATION-v0.10.md).

Røst's repository lists T3 1.92 GB, two alternative generators of 1.06 GB each, and voice encoder 0.0057 GB. One selected generator would put these components near 2.986 GB before small files, if the chosen inference path needs only that generator. The complete repository is about 4.05 GB. Neither number is a tested compressed Android archive. [Røst files](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m/tree/main).

Alternative benchmark-backed high track: Fish Audio S2 Pro advertises Danish and English and could provide both in one pack, but its original released semantic/fast-model weights are 9.124 GB, its codec 1.87 GB and tokenizer/configuration add about 0.013 GB: approximately 11.0 GB original data. No precise smaller Android download can be promised without a complete converted/quantized codec and model pipeline. This remains a separate research option, not the recommended initial phone pack. [Weight index](https://huggingface.co/fishaudio/s2-pro/blob/main/model.safetensors.index.json), [complete files](https://huggingface.co/fishaudio/s2-pro/tree/main).

Piper Danish remains a possible ultra-light fallback: approximately 0.063 GB original ONNX, plus required support data. Supertonic already supplies a small working Danish option, so additional Piper integration should be justified by listening/performance differences. English-only candidates do not satisfy a Danish tier on their own.

These are resource/integration tiers, not a verified Danish quality ordering. Artificial Analysis remains the quality reference where the exact model is listed, but does not supply a comparable ranking for these Danish specialists. Audition before naming a larger pack “premium.”

Samsung specifies Snapdragon 8 Elite for Galaxy and 12 GB RAM, or 16 GB in the 1 TB model. This is a useful stronger-device target. Phone system RAM is not equivalent to desktop CUDA VRAM, and a model fitting in RAM does not establish usable narration speed. [Samsung specifications](https://news.samsung.com/global/samsung-galaxy-z-fold7-raising-the-bar-for-smartphones).

Keep neural data in optional packs. First verify existing Kokoro/Supertonic on her device, then benchmark a complete new pipeline with the same original English/Danish passages. Compare voice preference blindly, clarity, numbers/names, omitted/repeated words, sentence endings, first-audio delay and long-session stability. Record full download/installed size, peak process memory, actual CPU/GPU delegate and warm/cold generation rate.

Real-time factor = generation seconds / generated audio seconds. Continuous 1× requires below 1; 3× requires below one-third, with additional headroom for decoding, disk and output. Test 20-minute sessions at 1/1.5/3×, screen off, pause/resume, route changes, and fold/unfold transitions. Do not assume the NPU accelerates a CPU ONNX model automatically. Ship only packs whose voice quality and sustained speed justify their storage and integration costs.

## Proposed game: Folio Cat Café

Reading or listening earns fish treats. Treats attract named cats; cats help furnish a small book café. Furniture upgrades unlock more visiting cats, room variations and cosmetic collectibles. A short reward visit after a session creates a reason to return tomorrow.

Suggested initial scope: one room, six cats with short personalities/animations, roughly twelve decorations, a daily reading goal, a weekly goal and a simple upgrade chain. Illustrative daily goal: ten eligible minutes. Goal choices can be 5/10/20/30 minutes. Rewards are based on time and consistency rather than WPM or typography-dependent pages. Reading, guided reading and actual narrated playback all qualify.

Streaks can use a seven-day calendar, 3/7/14/30-day cosmetics, earned rest-day protection and a five-days-per-week alternative. Cats remain adopted and comfortable after missed days. Permanent achievements retain total reading and longest streak. An optional friend feature can come later; initial progress stays offline and does not require accounts or public rankings.

Two currencies make the incremental loop legible: reading earns fish for adoption/unlocks; furnished cats produce a modest amount of yarn for decoration. Idle yarn is calculated on reopening, capped (for example eight hours), rather than running continuous background jobs. Reading remains the main way to unlock new content; game tapping does not advance reading progress. Avoid making full-book completion necessary for early rewards.

## Session accounting and reader integration

Eligible manual reading uses foreground text-view time with a generous inactivity policy; the app cannot prove comprehension or attention. Guided reading counts only while advancing. Narration counts actually presented audio, including background listening, excluding buffering, silence timers, model/sample previews and paused time. Use one active-session ledger to avoid duplicate rewards across mode changes, multiple surfaces and restarts. Playback speed must not multiply reward minutes.

Store awarded progress transactionally and checkpoint it; use monotonic session time plus a persistent local-day record for streaks. Define timezone/day-boundary behavior and recover conservatively from clock changes. Future backups must merge achievements/session rewards without duplicating currency. This deserves explicit migration and replay tests.

Place Café beside Library/History/Browse/More or as an optional home card, after evaluating navigation space. During reading, a small optional sleeping-cat indicator can expose session progress; rewards appear when the session ends. No game dialog should interrupt TTS, move the reading locator or stop buffering. On the unfolded Fold7, an opt-in small café side panel is possible; fold/unfold must preserve the current passage and audio sample.

Use lightweight Compose/Canvas or sprite animation with a static/reduced-motion option. Avoid a new heavyweight game engine. The game must not compete with TTS for CPU or run animation work when hidden. Exact asset size depends on art and will be measured before release.

## Proposed delivery

1. Evaluate and implement Plapre Pico as an optional Danish pack using the dedicated delivery plan. Retain Kokoro English and Supertonic. Prove full audio generation and phone performance before advertising a pack.
2. Deferred todo: Plapre 3 Danish evaluation/integration, sharing acoustic components where validated.
3. Deferred todo: Røst v3 Chatterbox 350M Danish quality/runtime investigation.
4. Deferred todo: one cat room and a durable reading/listening reward ledger; then daily goals, adoption, decorations, a small incremental loop and gentle streaks. Expand rooms and optional Fold7 layout only after reader/narration performance remains stable.

Other models above remain research references rather than selected implementation work. Additional English engines are not a priority while Kokoro meets the user's needs.

This document does not claim any newly researched model has been downloaded, auditioned or benchmarked on a physical phone, and does not change the released APK.
