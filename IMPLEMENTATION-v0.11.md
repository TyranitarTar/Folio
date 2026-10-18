# Folio 0.11: Plapre 3

Implementation in progress, 18 October 2026. Release only after the waveform,
Android playback, recovery and regression checks complete. Physical Flip7
throughput and human Danish listening acceptance must be recorded separately.

## Engine and optional pack

Plapre 3 is a separate optional Danish backend. The original syv.ai model is pinned
to `5300281a08c692a5ad18c9f00a4a47aa3dff9148`; weights remain FP32, as recommended
by its authors. This is not a quantized Nano v1 renamed as Plapre 3. Its own 21,224-token
tokenizer, 960-wide speaker projections, 32-layer cache and five built-in voices
are used. Both `<eos>` and `</audio>` stop generation. Hitting a token/time limit
is an error, never a successful passage or permission to skip text.

The model files contain conflicting rotary fields. The published Transformers
4.57.6 runtime reads `rope_theta=10000` and ignores `rope_parameters=100000`.
The export follows the unmodified 4.57.6 reference. An initial Pico-style 100000
interpretation was rejected after sample repetitions; both experimental reports
are retained in research evidence. FP32 logits and KV caches are compared with
the independent Transformers implementation for prompt prefill and cached steps.

Kanade and HiFT reuse the existing validated synthesis exports unchanged. The
pack is self-contained so removing Pico cannot break Plapre 3. This initially
duplicates acoustic files when both packs are installed. Shared physical model
storage is a possible later improvement, requiring dependency-aware downloads,
inventory and removal. Only the selected engine is loaded.

The pack is downloaded from Folio's GitHub releases. The app needs no Hugging Face
credentials and performs no cloud inference. Identities, exact lengths and SHA-256
hashes are pinned in the signed APK. Large archive/file limits are extended only
for this backend; extraction retains per-file and aggregate bounds. Installation
requires room for both the archive and expanded files. Native inference and the
download UI require a 64-bit process for this model; existing engines retain their
earlier compatibility.

## Narration and performance

The existing producer, bounded PCM queue, presentation-based source checkpoints,
highlighting, seeking and saved listening cursor serve Plapre 3. Playback speeds
0.5–3x reuse source audio rather than changing synthesis. Source identity includes
the exact model, sampling, padding, voice and CPU setting and cannot reuse Pico
offsets. Changing a source repeats the current passage safely. Earlier Pico Auto
cache identities and existing pack identities remain unchanged.

Plapre 3 has independent saved voice/thread settings, included in backups and
preview intents. Auto caps at four; explicit 1/2/4/6/8 choices are available. The
on-device benchmark runs the selected Plapre model and labels its report. It
retains serialized admission, cancellation and speech-service guards. Results
from one model are not displayed under the other model's name.

Whole-prompt prefill reduces repeated inference calls. Idle ONNX pools do not
spin between stages for the new model. These choices do not establish real-time
phone performance. Sentence-boundary passages and conservative edge-only trimming
are retained; no fixed inter-sentence padding is inserted. Prepare chapter ahead
uses the same boundaries and bounded disk cache without moving the listening
cursor. Voice packs include actual pre-download samples.

## Audiobooks and future UI work

The Library home shows an Audiobooks coming-later card. It does not pretend to
import or play audiobook files. Future scope includes local audiobook import,
chapters, resume positions, background/media controls and library integration.
The precise formats and metadata behavior will be designed before implementation.

A joint UI cleanup is explicitly deferred until review with the user. Plapre 3's
controls are added to the existing structure rather than a broad redesign.
Røst v3 and Folio Cat Café remain future tasks.

## Sources and verification

- [Plapre 3 model and requirements](https://huggingface.co/syvai/plapre-3).
- [Upstream inference](https://github.com/syv-ai/plapre), pinned source revision
  `b111239d3b099cfafcb54b3471a8e9e9ba71ae8b`.
- `tools/plapre3/export_lm.py`, `validate_lm.py`, `samples.py`, `make_pack.py`.

Record final archive/APK sizes, hashes, test counts, recognizer observations and
physical-device limits in the release review. ASR is a diagnostic, not a substitute
for Danish listening or a guarantee that a stochastic voice never repeats a word.
