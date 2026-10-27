# Future: existing audiobook + EPUB → synchronized FolioBook

**Next-session plan, saved 25 October for 26 October:** expose preparation as
Studio's **Combine EPUB + audiobook** mode, alongside **Create narration from
EPUB (text to speech)**. Include an assisted, exportable diagnostic/review package
for ChatGPT and add English recognition/alignment alongside Danish. Automatic
background processing is feasible and already exists in the standalone Danish
worker; uncertain correspondence still needs review. A chat is not required to
keep a prepared conversion running. No upload or new implementation is requested
by this planning item. See [Studio checklist](../../../tools/studio/TODO.md),
master E11/E12, and the separate current full Danish job.

25 October 2026. Android 0.15 work is finished. The user has now authorized
desktop recognition/alignment evaluation and benchmarks on the existing complete
Pico Silvercloak – Profetien recording and its embedded, hash-matching EPUB.
Other Studio work remains deferred. This does not restore cancelled phone TTS research.

Current preference: **CPU-first speech recognition**, with optional partial GPU
acceleration only if needed later. Do not run full-GPU benchmarks during this pilot.
The existing FolioBook has 54 completed chapters, 11,717 passages and about 17.03
hours of audio. Original cue times are evaluation ground truth only; the reverse
matcher must use independently extracted EPUB text and audio, not copy old cues.
Benchmark inputs/transcripts stay under output/FolioAlignment-data/private.

The first CPU comparison and independent prologue pilot are complete. See
[measured results](../../../tools/alignment/BENCHMARK-2026-10-25.md) and
[prototype scope](../../../tools/alignment/README.md). The full 17-hour book has
not been realigned; 27/182 prologue passages still require review. Existing audio
and EPUB bytes are reused unchanged in the experimental preview.

## Feasibility

Feasible in principle. The task is audio/text alignment: preserve an existing
narrator recording and find when the supplied EPUB's passages are spoken.
Use stable sentence/paragraph/word locators, not rendered screen lines, which
change with fonts and page size. No new TTS synthesis is required.

The strongest initial case is an unabridged recording and matching text edition
in the same language. Different translations, abridgement, introductions,
credits, repeated phrases, music and narration deviations require gap handling
and review rather than forcing every EPUB sentence onto some audio interval.

## Proposed later prototype

- [ ] R01 Select audiobook file(s) and an EPUB; retain both originals and exact
  EPUB identity. Choose language and verify that these are matching editions.
- [ ] R02 Extract ordered EPUB sentences/paragraphs and stable locators. Use
  audio chapter metadata where available and bounded speech/audio windows.
- [ ] R03 Evaluate direct forced alignment of known text, and ASR-assisted
  coarse matching followed by forced alignment. ASR finds corresponding passages;
  forced alignment supplies audio boundaries. Transcription alone is insufficient.
- [ ] R04 Evaluate Danish Hviske, Røst ASR/CTC and WhisperX-compatible alignment
  candidates; select by actual timestamps/source coverage, not transcription WER
  alone. Keep optional CPU execution and separately test this desktop's AMD path.
- [ ] R05 Match audio chapters to EPUB chapters monotonically, then align small
  overlapping windows. Preserve gaps and detect repeated/omitted/extra text
  without allowing one mismatch to shift the rest of the book.
- [ ] R06 Begin with sentence/paragraph cues. Evaluate word timings only after
  sentence matching is reliable. Never divide clip duration evenly to invent cues.
- [ ] R07 Add a review surface for uncertain matches and manual anchor correction;
  show matched/unmatched coverage and sample playback with the linked EPUB text.
- [ ] R08 Checkpoint/resume alignment; cache work by audio hash, EPUB hash,
  model revision and configuration. A new model should not overwrite a good edition.
- [ ] R09 Export original EPUB + original narrator audio + cues as a FolioBook,
  avoiding audio re-encoding/splitting where compatible. Assess current format
  size/track/cue limits and chapter mapping before promising zero-copy packaging.
- [ ] R10 Pilot one matching chapter first. Measure runtime/RAM/VRAM, boundary
  error, coverage, mismatches and pause/seek/highlight recovery in Folio. Include
  a deliberately nonmatching/abridged case that must warn instead of silently align.

## Model naming and verified references

Plapre is syv.ai's TTS family. The company's speech-recognition family is
**Hviske**. Røst also has separate ASR models; these are different checkpoints
from the Røst v3 Chatterbox narrator already used in Studio.

- [Plapre repository](https://github.com/syv-ai/plapre): identifies Danish TTS.
- [Hviske v5 Tiny](https://huggingface.co/syvai/hviske-v5-tiny): Danish ASR;
  the card explicitly says no timestamps. It needs a separate alignment step.
- [Hviske v5.3](https://huggingface.co/syvai/hviske-v5.3): Danish ASR candidate.
- [Røst v2 wav2vec2 315M](https://huggingface.co/CoRal-project/roest-v2-wav2vec2-315m):
  Danish ASR/CTC candidate; check exact processor/vocabulary alignment compatibility.
- [WhisperX](https://github.com/m-bain/whisperX) and its
  [alignment implementation](https://github.com/m-bain/whisperX/blob/main/whisperx/alignment.py):
  forced alignment and language-specific models, including a Danish default.

Sources checked for planning on 25 October 2026. These establish available
components, not proven Folio integration, model choice, speed on RX 5700 XT or
accuracy on an actual book. Recheck exact runtime/model/distribution terms when
the user chooses to start. No model or runtime was installed for this evaluation.
