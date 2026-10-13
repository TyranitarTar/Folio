Implementation update: v0.4.0 bundles the pinned Supertonic 3 INT8 package, English/Danish and ten voices alongside Kokoro. The comparison below records the earlier feasibility investigation. Physical ARM performance and Danish pronunciation still need user audition.

# Danish speech for Folio: feasibility

Research checked 2026-10-04. No Danish model has been integrated, downloaded or benchmarked for this investigation. Android assessments below are engineering judgments based on the published architectures, available runtimes and Folio's code. They are not measured voice-quality rankings.

## Actual language-model-based Danish TTS

### Plapre Pico: compact experimental candidate

[The author's model card](https://huggingface.co/syvai/plapre-pico) describes a Danish LLaMA-based autoregressive model with approximately 118 million parameters, speaker conditioning and 24 kHz output. It lists GGUF variants, including an 83 MB Q4_K_M language model. This figure excludes the Kanade audio decoder, runtime, working memory and caches. The model is listed as CC BY 4.0 and its files require Hugging Face access-condition acceptance.

Assessment: a promising candidate for a phone prototype, but not a ready-to-add voice. A port needs the exact tokenizer, speaker projection, audio-token generation and Kanade decoding. Generic Hugging Face llama.cpp chat examples do not demonstrate the complete speech pipeline. Check quantization against full-precision speech for omissions, repetition and pronunciation.

### Plapre Nano v2: more narration controls, heavier requirements

[Nano v2's model card](https://huggingface.co/syvai/plapre-nano-v2) specifies a 335M Llama language model with voice reference, context, pronunciation and per-word duration controls. It recommends FP32 inference because reduced precision can increase artifacts; it also warns about occasional mis-speaking. FP32 language-model weights alone are approximately 1.34 GB by parameter arithmetic, before codec and working memory.

[The author's serving library](https://github.com/syv-ai/plapre) currently requires Python and a CUDA GPU. Assessment: useful for desktop auditions and as a research reference; a dependable Android port is substantially more work than configuring an existing ONNX voice. Sentence batching and the published sampling settings must be evaluated on continuous book narration.

[Kanade's model card](https://huggingface.co/frothywater/kanade-25hz-clean) documents the separate audio codec and MIT licensing. Decoder-only deployment with precomputed speaker embeddings may avoid unnecessary reference-audio encoding, but needs implementation and measurement.

### Røst / CoRal: Danish narration quality reference

[Røst-v3-chatterbox-350m](https://huggingface.co/CoRal-project/roest-v3-chatterbox-350m), trained by Alexandra Institute in the CoRal project, adapts GPT-2-based Chatterbox Turbo using more than 2,000 hours of Danish speech. The card reports a mean opinion score of 4.01/5 from 20 Danish speakers on a small sample set. This is the authors' evaluation, not our own audition or a head-to-head comparison with Plapre. It lists OpenRAIL licensing and recommends splitting long text into sentences. There is also a [500M multilingual-derived variant](https://huggingface.co/CoRal-project/roest-v3-chatterbox-500m).

Assessment: worth auditioning for pronunciation and prosody. Phone deployment requires the full language model and audio-generation pipeline. A [community Android Chatterbox port](https://github.com/acul3/chatterbox-tts-android) documents a roughly 2.6 GB ExecuTorch model download. It is evidence of an Android approach, not validation of Røst weights, Danish quality or sustained performance on our target phone.

## Practical local neural TTS alternatives

### Supertonic 3: strongest integration lead to audition

[The model card](https://huggingface.co/supertone-oss-archive/supertonic-3) lists Danish among 31 languages, approximately 99M parameters and local ONNX inference. Supertonic uses flow matching rather than an autoregressive speech language model. [Sherpa's Danish documentation](https://k2-fsa.github.io/sherpa/onnx/tts/all/Danish/supertonic-3-da.html) provides an INT8 model package, Kotlin examples and Android engine APKs for v1.13.8. Folio already uses that runtime version and its checked-in Kotlin bindings include `OfflineTtsSupertonicModelConfig`.

Assessment: a relatively direct route to a local Danish prototype through the existing audio/checkpoint infrastructure. Verify full-precision versus INT8 Danish narration and effective sample rate rather than assuming settings match Kokoro. Load one selected AI engine at a time.

[The official repository](https://github.com/supertone-oss-archive/supertonic) was archived on 2026-09-09; official development and support have ended. Code and model weights remain available under their respective MIT/OpenRAIL-M terms. Use the archived model namespace and pinned revisions; a shipping integration needs maintained dependencies and its own fixes.

### Piper talesyntese: lightweight baseline

The Danish [talesyntese-medium model](https://huggingface.co/rhasspy/piper-voices/tree/main/da/da_DK/talesyntese/medium) is about 63.2 MB in ONNX format. Its [voice card](https://huggingface.co/rhasspy/piper-voices/blob/main/da/da_DK/talesyntese/medium/MODEL_CARD) lists one speaker, 22.05 kHz output and CC0 training data. Piper is neural TTS, not an LLM. [Sherpa documents Piper/VITS support and Android models](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html).

Assessment: a practical baseline for resource use and understandable Danish speech. Audition it before judging its suitability for long novels. Confirm the specific converted voice package, phonemizer and redistribution terms; training-data licensing alone is not the complete package license.

## Proposed evaluation and decision

1. Audition Supertonic 3, Piper, Plapre Pico and Røst with the same original Danish passages. Include dialogue, æ/ø/å, compounds, abbreviations, dates, currencies, names and English loanwords. Include a long chapter to expose repeated or omitted text and inconsistent voices.
2. Prototype Supertonic/Piper through Sherpa first. Measure cold/warm first-audio latency, generation time divided by audio duration, peak process memory, storage and a sustained screen-off listening session on the actual ARM phone. Sustained generation must keep ahead of playback at the selected speech speed with bounded prefetch.
3. Treat Plapre Pico as a separate experimental port. Count decoder and cache costs alongside language-model size. Move it into a release only if quality and sustained phone performance justify the complexity.
4. Keep stable EPUB locators and actual played-sample checkpoints. Test pause/resume, process death, voice/language changes, audio interruptions and cache eviction. Speech speed and WPM guided highlighting remain independent.
5. Decide after listening and measurement. Consider optional imported/downloaded voice packs rather than further increasing every APK. Download support would be an explicit new feature; Folio v0.3.0 currently has no Internet permission.

A good offline Danish reader voice is feasible. Practical neural speech has an existing Android path; selecting a genuinely LLM-based Danish voice adds mobile porting and generation-reliability work.
