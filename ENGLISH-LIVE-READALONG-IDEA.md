# English live speech-to-text / read-along — idea evaluation

30 October 2026. Evaluation only, excluded from 0.16.1. No model download,
recognition runtime, microphone permission or cloud service added.

Feasible in principle for local English recordings. Existing FolioBooks already
follow supplied EPUB/audio cues without recognition. For an ordinary audiobook,
recognition would generate approximate captions; following the original book
also requires a matching EPUB and a separate conservative text-matching step.
Reading aloud into the microphone is a third use case and would need a separate
permission and design. The working interpretation here is following audiobook
playback inside Folio, not recording a person or another app.

## Options

| Route | Assessment | Main limitations |
| --- | --- | --- |
| Studio English matching → FolioBook | Best existing route for accurate source text and low phone workload; already implemented | Preparation on the PC; uncertain timing/edition matches still need review |
| Optional offline English recognizer on Android | Plausible future prototype for local audiobook captions, then EPUB matching | Extra model/runtime, CPU/RAM/battery/heat, recognition errors, startup delay and seek recovery |
| Android SpeechRecognizer | Useful for short speech tasks, unreliable as a universal continuous-book backend | Service/device differences, possible cloud use; audio injection and segmented sessions depend on implementation |
| Cloud streaming | Technically possible, poor default for Folio's offline workflow | Uploads private audio, recurring cost, connectivity and explicit opt-in requirements |

Sherpa-onnx publishes Android streaming English examples, including a small 20M
Zipformer and larger streaming models. Its documentation flags lower accuracy
for the small model. This is evidence of availability, not a measured Folio/Flip7
performance claim. Our existing sherpa TTS integration does not establish that
the current native bridge exports the required ASR interfaces. Check exact
runtime compatibility and model-specific distribution terms before selecting a pack.

Android documents that SpeechRecognizer is not intended for continuous recognition
and may send audio to a server. On-device service availability can be queried.
EXTRA_AUDIO_SOURCE (API 33+) accepts supplied PCM audio, but an implementation
may ignore it and open the microphone instead. Do not silently fall back to mic
capture or promise support across Folio's Android 8+ minimum.

## Suggested later prototype, if requested

Decode local audio to mono PCM separately from MediaPlayer playback and recognize
a bounded look-ahead window at the recording's original speed. Timestamp results
against the media clock and expose only words/passages whose audio has started.
At 2×/3× listening the recognizer must sustain that many source-audio seconds per
wall-clock second, or use a prepared cache; a 1× demonstration proves neither.
Cancel stale work on seek/book switch and retain checkpoints by source hash,
model revision and configuration. Music, names, accents, silence and abridgements
must yield uncertain/gap states instead of invented text or forced EPUB matches.

Start with approximate captions and an optional matching EPUB. Evaluate sentence
following before word highlighting. Test a 10–20 minute representative clip and
then sustained playback on the actual target phone: startup/caption delay,
timestamp error, transcription quality, memory, heat/battery, 1×/2×/3×, Bluetooth,
screen-off, pauses, chapter/file transitions and seeks. Pick acceptance thresholds
before claiming a live feature. No phone benchmark was run for this evaluation.

Sources checked 30 October 2026:

- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android audio-source and segmented-session options](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
- [Sherpa-onnx Android streaming ASR models](https://k2-fsa.github.io/sherpa/onnx/android/apk.html)
- [Sherpa-onnx source](https://github.com/k2-fsa/sherpa-onnx)
