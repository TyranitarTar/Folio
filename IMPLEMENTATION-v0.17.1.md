# Folio 0.17.1 — audiobook reliability and tools

13 November 2026. Android version 0.17.1, code 24. Qualification evidence is
recorded separately in `output/Folio-0.17.1-validation.json` when complete.

## Playback and positions

The baseline recreation test reproduced unintended autoplay: an Activity opened
with an explicit Play intent restarted a subsequently paused recording when
recreated. The activity now consumes the intent once, and restored activities
open paused while leaving already-running background audio alone.

Cursor saves capture a process-local increasing observation number before IO
dispatch. Older writes cannot replace newer observations, including a newer
deliberate backward seek; ordering does not depend on wall-clock advancement.
Switching recordings waits for the captured previous save before loading its
replacement. Periodic saves are two seconds apart, with explicit saves on pause,
stop and relevant transitions. Pending seeks are retained during shutdown.

Saved positions are no longer prematurely clamped by cached duration metadata;
MediaPlayer's prepared duration bounds the actual restored seek. No single cause
for the user's reported rollback has been established; this release hardens the
identified paths and tests lifecycle/process-loss cases. Codec seek tolerance
still applies. Optional auto-rewind remains an explicit existing setting.

## Player tools

Chapter progress remains on the main player, with destination preview and
Apply/Cancel for chapter dragging. The preliminary seek lock and its duplicated
buttons/message are removed. The chapter list is consistently named Chapters.

Notes sit beside Chapters and Bookmarks. A note captures its creation position
and optional passage; writing/editing does not move playback. Text and captured
editor context survive activity recreation. Notes can be edited or deleted.

Listening history in the player menu records new dated sessions with beginning
and end positions, confirmed playing wall time and speed changes. Viewing alone
does not count. Pause/stop close a session; buffering and deliberate seeks do not
count as listening time. Historical recovery points are kept separately, without
inventing session durations from those old records. Interrupted sessions retain
their last saved state. Limits: 100 notes of up to 2,000 characters and the latest
200 sessions per book; history can be cleared separately.

Notes/sessions use a bounded, atomic per-book `journal.json`, separate from the
large cue catalog and tiny cursor. A delayed session tick cannot reopen an already
closed session or overwrite greater recorded listening time. Same-edition updates
stage/remap journals inside the existing rollback transaction. An ambiguous note
mapping retains the previous edition, as for bookmarks. History that cannot be
mapped retains its original timestamps with an explicit original-recording label;
old session evidence does not prevent a valid audio update. When a linked playlist is split, notes and sessions
confined to one track follow that file; a private split-journal archive retains
cross-file session evidence rather than fabricating per-book listening durations.

## Opening and speech diagnostics

Optional metadata/artwork extraction no longer blocks the first MediaPlayer
preparation. Cached book details and existing loading indicators remain visible;
missing metadata is filled in the background. Opening-stage timings are logged
locally under `FolioAudioTiming`. Device/provider-dependent speed gains are not
claimed without equivalent before/after measurements.

Read aloud and performance tests share word, lookahead and startup choices.
The diagnostic text passes through the production sentence/character-boundary
builder, so actual word counts are shown rather than implying exact targets.
Quick mode tests current threads; an optional comparison measures 1/2/4/6/8.
The tested chunk/lookahead/startup configuration can be applied to narration.

Live tests generate varied passages while real audio plays. Startup uses a target
of buffered playback seconds and a maximum lookahead of 1–32. PCM reservations
use the production memory/time budget; already-consumed clip references are
released. This is a bounded trial, not proof of indefinitely sustained playback.
Measured callback queue waits remain distinct from acoustic or Bluetooth gaps.

Pico/Plapre 3 loading exposes file checking, tokenizer/voice preparation, native
model/decoder loading and first-audio stages. Native loading uses an activity
indicator rather than an invented percentage. Milestones, elapsed time and
cancellation remain available. No model, native voice runtime or driver changes
are included.

## Scope

Smart AudioBook Player feature-parity review is saved for a later release.
Animal Crossing is another project and excluded. Café/model research is unchanged.
Studio partial-chapter publication, export visibility, safe active-player refresh
and automatic file transfer remain companion follow-ups; no Studio feature
release is claimed here. Existing completed-chapter snapshots still work.

Physical-phone acceptance remains necessary for long sessions, Bluetooth/call
interruptions, provider latency, battery and thermal behavior. Automated emulator
checks do not establish phone voice throughput or measured acoustic continuity.
