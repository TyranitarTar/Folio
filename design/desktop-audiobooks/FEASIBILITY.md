# Desktop-generated narration for Folio

Assessment: 19 October 2026. This is a future feature design; Folio 0.12 does not
yet import or play synchronized recorded narration. No model weights, GPU drivers
or new speech runtimes were installed for this assessment.

## Recommendation

Generate a book on the PC, then let Android play the finished recording while
following the text. This removes live inference and its buffering limits from
the phone. Start with Danish Røst 3 and compare English Chatterbox Turbo using the
same desktop engine. Keep Kokoro for immediate English reading on the phone.

Use **EPUB 3 Media Overlays** for the portable book: XHTML, images, compressed
chapter audio and SMIL cues in one EPUB. The standard already maps text fragments
to timed audio clips. It avoids inventing another ebook container. Readers without
overlay playback can still display the text. [W3C specification](https://www.w3.org/TR/epub-33/#sec-media-overlays)

Also design an optional narration companion pack bound to the original EPUB's
SHA-256. It can attach audio to an existing Folio book without rewriting its text
or changing its library ID. A newly exported narrated EPUB has different bytes,
and inserting text spans may move EPUB CFI paths; existing annotations cannot
simply be assumed to transfer. Both exports should use the same internal cue
index. The standard EPUB export is the first interoperability prototype; the
companion export addresses existing libraries.

## Your PC and Røst 3

Read-only hardware inspection found a **Ryzen 7 5800X** and **Radeon RX 5700 XT**
on this Windows host. Røst's documented API supports CPU inference, making CPU
generation a reasonable first test. It is a Danish Chatterbox-Turbo derivative;
its name does not mean it is Plapre 3. [Røst model card](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m)

The RX 5700 XT is absent from AMD's current official ROCm compatibility matrix.
Its usual 8 GB VRAM capacity alone does not establish PyTorch compatibility or
that an entire inference pipeline fits. Treat GPU acceleration as an unverified
engineering experiment, rather than a working Windows option. CPU is the
baseline. Unofficial gfx1010 builds, translation layers or an ONNX/DirectML port
would each require separate operator, correctness and speed testing; none was
tested here. [AMD ROCm compatibility](https://rocm.docs.amd.com/en/latest/compatibility/compatibility-matrix.html)

There is no measured generation speed yet. Offline rendering need not run in
real time: a real-time factor of 0.5, 1 or 2 would produce a ten-hour audiobook in
five, ten or twenty hours respectively. Those are arithmetic scenarios, not
predictions for this PC. Measure a short sample before committing to a whole book.

## Desktop model shortlist

Approximate **decimal GB of selected model files**, checked against public
repository metadata on the assessment date. These are desktop downloads, excluding
Python/PyTorch, working memory, temporary recordings and optional ASR. Parameter
counts alone substantially understate some complete downloads.

| Model | Language in this plan | Download | Assessment |
|---|---|---:|---|
| Røst 3, Chatterbox 350M | Danish | ~2.99 GB | First Danish trial; OpenRAIL weights. Sentence-sized rendering, consistent reference voice. |
| Chatterbox Turbo, 350M | English | ~2.99 GB | First English trial: same engine as Røst, MIT weights. |
| F5-TTS v1 Base + Vocos | English | ~1.40 GB | Smaller alternative; reference audio and transcript. Weights are CC-BY-NC 4.0, despite MIT code. |
| Qwen3-TTS 0.6B Base | English | ~2.51 GB | Second engine to compare; includes ~0.68 GB speech tokenizer. Apache 2.0. |
| Qwen3-TTS 1.7B Base | English | ~4.54 GB | Heavier quality challenger; includes tokenizer. More memory/work, not a proven quality win. Apache 2.0. |

Sources: [Røst files](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m/tree/main),
[Chatterbox Turbo files](https://huggingface.co/ResembleAI/chatterbox-turbo/tree/main),
[F5-TTS files and license](https://huggingface.co/SWivid/F5-TTS),
[Vocos files](https://huggingface.co/charactr/vocos-mel-24khz/tree/main),
[Qwen 0.6B files](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base/tree/main),
[Qwen 1.7B files and languages](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-Base).
Qwen's documented ten languages do not include Danish; do not advertise it as a
Danish replacement without separate evidence.

Røst and English Turbo repositories also include a legacy decoder; downloading
every file costs about 4.05 GB each. The current official Turbo loader uses the
meanflow decoder. Pin compatible revisions and select required files deliberately.
Matching SHA-256 metadata shows about 1.07 GB of decoder/voice-encoder data can be
shared between these two models: approximately **4.90 GB combined**, instead of
5.97 GB, before runtime dependencies. Share by verified content, never by filename
alone. [Official loader](https://github.com/resemble-ai/chatterbox/blob/master/src/chatterbox/tts_turbo.py)

The companion `model-metadata-2026-10-19.json` records revisions and component
sizes. Public repositories were ungated at inspection; these models do not need
your Hugging Face API token for a public download. Redistribution of a model pack
still needs its own license review and attribution before a GitHub release.

The user's preferred [Artificial Analysis speech arena](https://artificialanalysis.ai/text-to-speech/arena)
is useful when the exact model/version appears. Its current dynamic ranking table
was not exposed in the readable response, so this assessment assigns no invented
Elo ranks. English arena preference does not establish Danish pronunciation or
ten-hour consistency. Compare blind samples from our actual runtime as well.

## Generation pipeline

1. Keep the input EPUB immutable. Extract chapters and spoken body text, excluding
   navigation and repeated running material. Preserve source anchors and a
   reversible mapping for number/abbreviation normalization.
2. Select one narrator and pinned model/settings for the whole book. Split at
   sentence boundaries with bounded lengths. Cache voice conditioning where the
   engine supports it. Long paragraphs must not become unbounded model inputs.
3. Persist completed chunks using text, voice, settings and model-revision hashes.
   Resume interrupted jobs, retry individual failures and estimate remaining time
   from measured throughput. Keep temporary storage bounded and visible.
4. Trim excess boundary silence conservatively. Preserve consonants, breaths and
   deliberate punctuation pauses. Join PCM chunks before encoding one compressed
   track per chapter; do not append independently encoded MP3 files as a queue.
5. Derive sentence cues from actual rendered sample counts. Verify timestamps
   after encoding, including encoder delay. Word-level highlighting requires
   alignment; words-per-minute estimates are not accurate audio timestamps.
6. Export EPUB 3 SMIL plus chapter tracks, or the companion pack. Validate every
   text reference and clip interval; detect missing/duplicate sentences and
   missing chapters. Listen to representative dialogue, names and chapter joins.

Røst's authors explicitly recommend splitting long text into sentences. A useful
first trial is a three-to-five-minute Danish passage followed by one full chapter,
not an unattended full-book job. Include dialogue, numbers, names and punctuation.

## Folio changes required

The bundled Readium toolkit contains an EPUB SMIL parser, but that does not give
Folio a finished synchronized audiobook player. Add playback from the actual
media clock, reliable sentence highlighting/following, text-to-audio seeking,
saved source anchor plus track/time, sentence/paragraph navigation, playback
speeds, Bluetooth/lock-screen controls and background service behavior. Switching
between reading and listening must preserve the passage. Use presented audio for
café rewards and avoid duplicate counting alongside ordinary reading.

Current import limits also block realistic narrated books: `EpubValidator.kt`
allows 200 MiB compressed, 256 MiB expanded and 32 MiB per non-text resource;
`LibraryStore.kt` and `BookDownloads.kt` have 200 MiB book limits, and
`LibraryBackup.kt` has a 200 MiB per-book limit. Introduce audio-aware limits,
streamed extraction/playback and explicit disk-space checks while retaining
archive traversal, entry-count and decompression protections. Revise backup size
handling and test interrupted imports. Do not merely remove ZIP limits.

At constant mono bitrate, ten hours adds approximately:

| Audio encoding | Added book storage |
|---|---:|
| 64 kb/s | 0.288 GB |
| 96 kb/s | 0.432 GB |
| 128 kb/s | 0.576 GB |

These exclude the original text/images and container overhead. Uncompressed
24 kHz, 16-bit mono would be 1.728 GB for ten hours, so keep PCM temporary and
encode chapter by chapter. The phone downloads the finished audio, not the
desktop model. Select bitrate after listening tests, rather than assuming the
smallest option is transparent.

## Proposed milestones and acceptance

- **Proof:** CPU Røst sample and one chapter; measure elapsed/audio seconds, peak
  RAM, retained pauses, pronunciation and narrator consistency. GPU experiment
  remains optional and separate. Compare English Turbo, then Qwen/F5 if warranted.
- **Desktop exporter:** resumable chapter jobs, progress/storage estimates,
  selective regeneration, pinned manifests, standard EPUB Media Overlay output.
- **Android prototype:** import narrated book and seek from text; no audible gaps
  at sentence boundaries, no early completion at 1.5–3×, repeatable resume after
  rotation/restart/backgrounding and headset interruptions.
- **Library integration:** attach narration to existing books without losing
  annotations; companion checksums, export/restore, removal of audio independently
  of the EPUB, and downloadable narration in the planned Audiobooks section.
- **Release gate:** full chapter and long-session tests on actual target hardware
  when available. Do not claim RX 5700 XT acceleration, Android word alignment or
  universal third-party EPUB overlay support based on a desktop sample alone.
