# Folio 0.10.1: Pico passage padding

Pico's generated waveform included long, low-level decoder padding before and
after speech. Passing those samples unchanged into an otherwise continuous PCM
queue caused audible waits between passages. This update treats the waveform
edges before caching and playback; the existing background producer and ordered
AudioTrack FIFO remain in use.

`PicoPadding` measures 10 ms RMS windows with a -48 dBFS activity threshold,
lowered to 32 dB below the loudest window for quiet recordings. It retains 200 ms
before detected activity and 120 ms afterward, avoids insignificant cuts and very
short results, and preserves the interior waveform byte for byte. It does not
speed up the model, concatenate MP3s, crossfade words or remove interior pauses.
This is conservative energy-based cleanup, not word alignment or a full speech
activity classifier. Physical-device listening acceptance remains necessary.

Pico's source-cache identity now includes `edge-padding-v1`. Old padded cache
entries cannot silently reappear in new playback. A pre-update audio bookmark
cannot apply its old sample offset to a shorter waveform: the existing recovery
path repeats that current passage once and displays the recovery message. Book
positions, annotations and other engine cache identities are retained. Playback
speed changes continue to share the same processed source waveform.

The Pico model version, checksums and GitHub download URL are unchanged. This is
an app update and does not require downloading the voice pack again. Kokoro,
Supertonic and Pico packs all come from the app owner's GitHub Releases; the app
does not fetch model weights from Hugging Face or require a Hugging Face token.

New verification covers edge removal on the original 0.10.0 native reference,
quiet recordings, soft attacks/releases, unchanged internal pauses and cache-key
migration. An opt-in, bounded in-memory timing trace records generation, queue
submission and presented audio boundaries without book text or audio. A new real
service test uses 20 distinct Danish passages and a cold audio cache to check that
generation overlaps playback and prepared lookahead enters the player before the
previous passage ends. Per-phone sustained speed remains unmeasured.

This release does not implement obsolete model-version cleanup. Its confirmed
storage gap and safe ownership/rollback design remain explicit priorities in
`TTS-roadmap.md` and `DESIGN-v0.10.1-speech-continuity-and-storage.md`.

## Recorded validation and limits

- 140 JVM tests passed, with no failures, errors or skips; Android lint has no
  errors. All 16 native libraries and the voice-pack catalog are byte-identical to
  0.10.0. The APK retains the existing signing certificate and increments to code 13.
- Four Pico Android checks cover native cancellation/recovery, queued 1.5× output,
  real service resume/progress and all five Danish voices. Three other checks cover
  Kokoro cold synthesis, Supertonic speed/checkpoint behavior and pre-download
  samples. A sample check initially lacked its network opt-in flag and was skipped;
  its explicit rerun passed. A real 20-passage service check and a grouping
  experiment bring the distinct completed Android checks to nine.
- The native reference shrank from 5.92 to 4.69 seconds. Its -45 dBFS quiet edges
  changed from about 1.24/0.32 seconds to 0.22/0.13 seconds. All five voices produced
  valid processed audio; a separate sentence lost 0.28–1.30 seconds by voice.
  Quiet-energy thresholds and local recognition spot checks are not human listening
  acceptance or proof of perfect pronunciation.
- In the 20-passage cold-cache run, eight native generation jobs began inside
  individually presented passage intervals, and 12 of 19 future passages were
  queued before their predecessor ended. The median handoff wait was effectively
  zero, but subsequent buffer starvation reached 5.74 seconds. Generation took
  136.83 seconds for 51.61 seconds of processed source audio (RTF 2.65) under that
  emulator/host load. The test proves background generation; it does not establish
  sustained gap-free Pico playback on a Galaxy S10 or Flip 7.
- A warmed AB/BA experiment used identical 19-word / 108-character text as three
  sentences or one combined request. Separate requests averaged 10.46 seconds;
  grouping averaged 9.94 seconds, approximately 5% faster. Individual comparisons
  ranged from 0.3% to 10% faster. Grouped audio lasted 8.10 seconds against 8.33
  seconds separately. Two short trials cannot establish a general speed gain.

Production grouping is unchanged in 0.10.1. A later design can target roughly
20–35 spoken words at nearby sentence boundaries, with a strict duration/input
limit and preserved navigation/highlights. Screen-wrapped lines must not define
audio identity. Grouping alone is not established as a remedy for sustained slow
synthesis; profiling acoustic decoding, CPU thread counts and a bounded
prepare-ahead option remains relevant.
