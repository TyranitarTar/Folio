# Pico continuity and obsolete voice storage

Evaluation: 17 October 2026. Baseline: published Folio 0.10.0, source commit
`c1f20855c5d278aff046fb058f15956ebfde9f49`. This document is a follow-up design,
not a claim that all changes below have shipped. The padding treatment, cache
identity and timing checks now have a bounded implementation in
[0.10.1](IMPLEMENTATION-v0.10.1.md); storage cleanup and broader speed work remain
planned. Model pack v1 remains immutable.

## Report and verified behavior

The user reports large pauses between Pico passages on a Galaxy S10 and about one
second between passages on a Galaxy Flip 7. These are physical-device observations;
the project does not yet have device timing traces. Older plans refer to a Fold 7;
do not silently substitute that device for the current Flip 7 report.

The existing producer does run ahead of playback. `SpeechService.fillAudioBuffer`
launches ordered synthesis jobs; `warmAudioBuffer` initially waits for 12 seconds
of audio at the selected playback speed. Subsequent passages are generated on the
native worker while AudioTrack plays on its separate worker. Completed clips enter
`AiAudioPlayer.queueNext` before the current passage finishes. The service permits
up to 32 lookahead passages, with a separate 32 MiB / 180 second PCM budget and a
conservative reservation for synthesis. Jobs await the preceding generation, not
the preceding playback. Increasing the queue's passage count alone is unlikely
to solve this report.

Pico initialization is retained across passages. `PicoEngine.prepare` skips its
model verification and initialization when already prepared; `localEngine` reuses
the current backend. No fixed one-second sleep appears in the natural handoff.
Configurable sentence/paragraph pauses can deliberately disable continuous
queueing, but both default to zero; their actual values on the phones are unknown.

Pico currently returns a complete, untrimmed waveform after natural EOS and
Kanade/HiFT decoding. Each passage is bounded to one sentence and normally 160
characters. Longer sentences can be split further, and normalization adds terminal
punctuation to fragments. This can add an unnatural cadence at split boundaries.

## Measured acoustic contribution

An offline analysis of the existing native Android WAV and nine desktop QA WAVs
uses 20 ms RMS windows. At -50 dBFS the native clip has 1.22 seconds of leading
quiet audio and 0.32 seconds trailing. The nine desktop clips have 0.12–1.42
seconds leading and 0.16–0.78 seconds trailing. At -55 dBFS the native leading
interval is 0.82 seconds and its trailing interval remains 0.32 seconds.

This is evidence of low-level audio at clip boundaries, not a speech detector or
proof that every sample in those intervals can safely be discarded. Nevertheless,
even perfectly adjacent PCM clips retain their recorded quiet intervals. This is
a concrete contributor consistent with the Flip 7 report, independent of whether
the synthesis queue starves. It does not rule out additional playback gaps.

Reproduce with the standard-library-only `tools/pico/analyze_boundaries.py`.
The local report is `tools/pico/results/pico-boundary-analysis.json`; it records
input WAV hashes, frame counts and threshold-dependent intervals. It performs no
network access and does not edit audio.

The saved emulator clip took 4.974 seconds to generate for 5.92 seconds of complete
audio (RTF 0.8402). This is not an S10 or Flip 7 benchmark. Removing quiet padding
also shortens playback time, so throughput must be measured again after any trim.
If generation takes longer than the available playback time on average, a finite
buffer eventually empties. A larger startup buffer only postpones that problem.

The prior ordered 1.5× playback test generates both Pico clips before starting
AudioTrack. It proves ordering and guards against early completion, but does not
prove sustained cold synthesis keeps up. The service test checks passage progress
and resume after a speed change, not a long run of acoustic handoff gaps. Those
coverage limits must be addressed before calling Pico continuous on these phones.

## Proposed implementation and acceptance order

1. Add local timing diagnostics: synthesis start/end, cache read/write/pin time,
   source duration, buffered playable seconds at the selected rate, first PCM
   submission and presented passage completion, plus starvation/underrun events.
   Store durations and opaque passage IDs; recording book text is unnecessary.
   Distinguish queue starvation, an output handoff gap and quiet audio inside PCM.
2. Run a cold-cache, real-service Danish test of at least 20 distinct passages.
   Verify that later generation starts before the current passage finishes and
   that completed lookahead is actually submitted before that finish. Report the
   fraction of ready handoffs, maximum/median starvation and edge quiet intervals.
   Cover 1× and 1.5×; higher speeds require proportionally more synthesis headroom.
3. Introduce conservative, Pico-specific edge-padding treatment after waveform
   generation. Detect candidate quiet regions, preserve attack/release margins
   and never trim interior sentence pauses or quiet consonants using a hard
   duration cut. Audition all five voices, short/long Danish passages, numbers,
   soft onsets and breathy endings. Measure acoustic boundaries separately from
   AudioTrack continuity. Keep source-sample checkpoint semantics intact.
4. Give changed audio processing its own cache identity. Preserve an already
   pinned waveform/checkpoint until a safe passage boundary or explicitly repeat
   that passage with the existing recovery message. Never reuse an old sample
   offset against shortened PCM, overwrite the immutable model archive, or throw
   away a user's listening position to make trimming easier.
5. If synthesis is the bottleneck, profile language-model and acoustic stages,
   thread counts and thermal behavior on both phones. Evaluate grouping short
   sentences within measured safe generation limits to amortize repeated prompt
   and decoder work. Retain word/chapter navigation and bounded memory. Avoid
   simply raising the 500-code safety bound or creating long uncancellable clips.
   Adapt startup buffering to measured effective throughput with visible status;
   explain when the selected device/speed cannot sustain live local generation.
6. Require actual-device listening acceptance on the S10 and Flip 7. Regression
   checks must include no overlap/cutoff, navigation/read-from-here, pause/resume,
   speed changes, chapter sleep, Bluetooth and unchanged Kokoro/Supertonic behavior.

There is no need to concatenate into an ever-growing MP3. The existing persistent
AudioTrack PCM FIFO already provides ordered continuous output without MP3 encoder
overhead or an unbounded audiobook file. The fixes belong in audio preparation,
producer throughput and handoff measurement.

## Obsolete voice versions: confirmed storage gap

Current directories are `noBackupFilesDir/local-voices/<version>` and transfer
state is `noBackupFilesDir/voice-transfers/<pack-id>`. `VoicePackStore.remove`
removes the catalog's current version and that pack's transfer directory only.
Installation safely stages/backs up an existing destination, then removes its
`previous` backup. A newly named model version has a different destination, so an
older version directory is not automatically discovered or removed. Legacy copies
prepared from older APK-bundled voices use this same versioned model area and can
also survive. This is a code-path finding; the amount of obsolete data on either
physical phone has not been measured.

Successful downloads already delete their archive, extraction staging is removed
in `finally`, and cancelled/retryable downloads intentionally keep a resumable
partial. The generated PCM cache is separately bounded to 64 MiB with active clips
pinned. These categories need separate labels rather than treating all storage
outside the latest Pico folder as junk. Current Kokoro and Supertonic packs remain
intentional installed options.

Planned storage feature:

- Inventory active model versions, older known versions, downloads/partials,
  rollback/staging directories, previews and generated audio, with byte totals and
  a visible reclaimable total. An app upgrade alone is not a model upgrade.
- Persist an installed-pack manifest/registry with pack ID, version, component
  paths/hashes and ownership. Reconcile known legacy versions using explicit
  identities. Unknown folders are reported; name guessing is not deletion proof.
- On successful replacement verification, offer removal of superseded owned
  versions. Preserve every installed current engine, any retained rollback version,
  shared component still referenced by another pack, and all reading/listening data.
- Use the existing operations mutex, download cancellation/drain and native worker
  drain, then recheck active playback/preview/preparation before deleting. Resolve
  and constrain paths to app-owned storage. Never remove a loaded model.
- Provide separate “Remove old voice versions”, “Discard partial downloads” and
  “Clear generated audio” actions, showing expected recovered space. Keep resumable
  downloads unless explicitly discarded or subject to a clearly defined retention
  policy. Normal voice-pack removal should include that pack's known old versions.
- Test interrupted upgrades, rollback recovery, legacy installs, all current packs
  installed together, active narration/preview, concurrent downloads, shared future
  components and repeated cleanup. Verify book annotations and checkpoints survive.

Development conversion files under workspace `tools/pico` are separate from phone
storage and are excluded from the APK/source package. Do not delete reproducibility
artifacts or the only source copy while addressing Android storage.

## Resume point

The published 0.10.0 APK and Pico v1 download already exist. Continue from that
baseline; rebuilding the model pack or repeating the initial conversion is not
required. The production in-app 0.9→0.10 installation UI test remains unfinished;
the previous run could not complete its automation. APK signature/version checks,
CI and the recorded emulator tests are separate completed evidence. Next work is
the continuity diagnostics and safe storage feature above, followed by a new app
version and device acceptance.
