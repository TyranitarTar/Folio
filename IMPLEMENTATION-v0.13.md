# Folio 0.13: recorded audiobooks and linked EPUB narration

The home screen has **Books** and **Audiobooks** tabs. Hybrid books appear in
both, linked by the checksum of their unchanged original EPUB. Reading history
moves under **More**, keeping the bottom bar at five destinations when Café is on.

## Audiobook player

Import local audio files together, an audiobook folder, or a desktop `.foliobook`
package. Folder and file imports use natural filename ordering. Selected files
are copied into private app storage so playback does not depend on a retained
cloud-provider permission. Files are decoded by Android; supported containers
include MP3, M4A/M4B, Ogg and WAV. Device codec support still applies.

The player provides track/chapter navigation, a seek slider, ten-second rewind,
thirty-second forward, play/pause/stop, 0.5–3× speed, audio bookmarks and sleep
timers. It shows track and whole-book times, including remaining listening time
at the chosen speed. MediaSession, notification/headset controls, audio focus,
headphone-unplug handling and a playback wake lock support screen-off listening.

Only the decoder's actual completion callback advances automatically to the next
track. Explicit seeks pause playback and wait for the decoder's seek completion
before resuming. Changing speed cannot use an estimated timer to truncate a track.
Stale prepare/seek/completion callbacks are rejected by session generation.
Recorded playback and live TTS stop each other before playing.

Listening progress and speed use small per-book atomic cursor files. Checkpoints
also run on seeks, pause, track changes and stopping. Process death can lose the
last uncommitted interval; foreground UI, imported audio and saved state are
independent. Bookmarks and the audio catalogue use atomic metadata saves.

## Linked books

The first desktop format is a versioned **companion bundle**, not a rewritten
EPUB. `manifest.json` comes first, followed by the original `book.epub` and one
compressed audio file per generated chapter. The format stores actual audio cue
times, source XHTML paths, optional IDs/selectors and quote context. See
[the format contract](design/desktop-audiobooks/FORMAT-v1.md).

Importing a companion reuses an existing EPUB with the same hash, preserving its
annotations and reader state. **Read book** in the audio player follows its
recorded passage. Book details provide a recording button. The reading page has
recorded-audio controls, sentence highlighting/following and **Listen from here**
on the page or text selection. Repeated quotes use their context; ambiguous or
unrecorded passages are rejected rather than sent to an arbitrary sentence.
Completed recordings take precedence over older previews unless the user is
already playing a selected recording. All recordings remain selectable in Audio.

Preview imports are labelled in the library and player. A short recording is not
represented as the complete book. Word-level forced alignment is not implemented;
highlighting follows generated sentence cues.

## Desktop generation

The public source includes `tools/narration`. It
supports Danish Røst 3 and English Chatterbox Turbo through a separate CPU Python
environment. Pinned model snapshots, local file checksums, consistent narrator
conditioning, recoverable sentence WAVs, chapter MP3 encoding and resumable jobs
keep inference off the phone. Models and their Python runtime are not in the APK.

The supplied Fourth Wing EPUB was inspected locally and contains Danish body
text, so Røst is the matching engine. That book, its extracted text and generated
audio remain private and are excluded from public source and GitHub releases.
An actual 24-cue CPU trial produced 143.5 seconds of speech at a measured 5.54
seconds of computation per second of audio. Its provisional whole-book estimate
was about 65 hours on this host while other checks were running. This is a
throughput observation, not a guarantee or a completed audiobook. A second
bounded trial uses the author's Danish Mic reference. Local ASR diagnostics can
flag possible pronunciation problems but do not replace listening review.
Fake-synthesizer tests verify packaging and resume mechanics, not voice quality.

## Storage, reference and limits

Audio storage is separate from ordinary EPUB backups and generated TTS caches.
Keep original audio or the `.foliobook` for reimport. Removing a recording deletes
its listening bookmarks but retains the linked EPUB and its reading annotations.
Import uses bounded streaming, ZIP path/duplicate/entry checks, metadata and cue
validation, staged files and cancellation cleanup. Current limits are 4 GiB per
audio import/bundle, 256 MiB per track and 200 MiB for the original EPUB. Ordinary
EPUB ZIP protections remain unchanged.

The supplied Smart AudioBook Player APK was inspected as a feature reference for
familiar playback controls. Folio's player is original Kotlin code; no APK code,
assets or third-party app binary is distributed with Folio.

Future work includes standard EPUB Media Overlay interchange, embedded chapter
tables inside single-file M4B books, portable audio-state backups, a richer choice
of recordings per book, optional smart rewind, volume tools and Android Auto.
The current track list covers multi-file books and generated chapter tracks.

## Verification

New checks exercise repeated-quote matching, preview coverage and recording
selection; Android tests use real decoded tracks for paused seeking, 1.5×/3×
completion timing, persistent progress/bookmarks, hostile ZIP rejection and the
Books/Audiobooks/player UI. A separate optional test imports a locally generated
private recording without embedding it in test assets. Final build and acceptance
results are recorded in the release validation report. No physical-device
performance claim is made for this release.
