# Folio 0.17.1 — audiobook reliability and player follow-up

**Implementation authorized 13 November 2026.** Android changes are implemented
and undergoing final qualification; see [implementation notes](IMPLEMENTATION-v0.17.1.md).
The original assessment below remains the design record. Broader physical-device
measurements and the separate Studio companion follow-up are not claims of this
Android release. Smart AudioBook Player parity review is deferred to a future
version in [the comparison notes](SMART-AUDIOBOOK-COMPARISON.md).

Assessment: 13 November 2026. Current local Android baseline is 0.17.0,
versionCode 23. 0.17.1 is the appropriate next patch version; reserve code 24
when implementation begins. This document records evaluation and planned work,
not completed implementation, a new APK, or permission to publish a release.
Use the user's current preference: Sol High, no subagents.

Animal Crossing belongs to another project and is excluded. No Café redesign,
new voice model, or broader model research is part of this release.

## 1. First priority: reliable positions and intentional playback

Observed in the current source:

- AudiobookService checkpoints every ten seconds and on pause, stop, task
  removal and destruction. AudiobookStore writes a separate atomic cursor file
  and rejects writes older than the last saved wall-clock observation.
- Optional auto-rewind exists and defaults off: normally five seconds after
  a pause of at least one minute when enabled. This can explain a small
  deliberate backward move, but the user's reported loss is not diagnosed.
- Ordinary library tile opening requests autoplay=false. The explicit play
  action requests true. AudiobookActivity re-reads that intent during activity
  creation, so a retained true intent is a concrete recreation path to test.
- Opening a player starts/prepares its service even for paused viewing.
  requestStart schedules the previous checkpoint asynchronously before loading
  the new target; ordering and rapid reopen/switch behavior need reproduction.

Planned work:

- [ ] Reproduce exact-position loss before claiming a cause. Cover leave/return,
  Pause, Stop, switching books, rotation, activity recreation, process death,
  pending seeks, chapter/file boundaries and FolioBook revision replacement.
- [ ] Keep playback's live position authoritative while its service exists.
  Serialize/flush saves at transitions; prevent older or provisional startup
  values from overwriting confirmed positions. Verify write order independently
  of wall-clock changes. Do not depend on onDestroy being called.
- [ ] Audit cursor restoration/clamping against cached versus decoded duration,
  track identity and source availability. Surface save failures clearly.
- [ ] Bound loss after an unannounced process kill to an explicit small interval;
  aim for no more than two seconds of wall time between ordinary checkpoints,
  measuring disk cost first. Account for playback speed in audio-time tolerance.
- [ ] Opening the app, a book, or a restored activity stays paused unless audio
  was already playing. Preserve ongoing background playback when returning.
  Explicit Play/headset Play may start audio; consume a play intent once rather
  than replaying it after recreation. Keep call-interruption resume conditional
  on playback having actually been interrupted.
- [ ] Keep optional auto-rewind distinguishable from the durable saved cursor;
  show its setting and include its applied amount in diagnostics/history.

Acceptance: saved/returned track and time agree within codec seek tolerance;
intentional seeks are retained, rotation never restarts paused audio, and abrupt
termination has a measured bounded loss rather than an unexplained rollback.

## 2. Player simplification, notes and listening history

- [ ] Remove the chapter-seek lock state, lock icons, Unlock/Lock actions and
  "Chapter seeking locked" message. Keep whole-book progress read-only and
  chapter progress on the main player. For chapter dragging retain destination
  preview and Apply/Cancel, without a preliminary unlock step.
- [ ] Rename every "Chapters & position" entry to "Chapters"; position controls
  are already on the main player. Keep elapsed/total/remaining time consistent.
- [ ] Add Notes alongside Chapters and Bookmarks in a compact tools row, with
  a menu entry if narrow screens cannot fit the row. Add/edit/delete a note;
  capture book, track/time, chapter and optional linked passage when created.
  Typing/saving a note must not seek or change playback state. Preserve notes
  across restart, rescan, snapshot replacement and migration. Reuse linked-text
  annotation identity when suitable; ordinary audiobooks also need notes.
- [ ] Restore Listening history as dated sessions, accessible from the player
  menu rather than another permanent prominent tab. Show date, start/end time,
  starting/ending chapter and position, actual listening time and speed changes.
  Opening/viewing the player must not count as listening. Pauses and seeks do
  not earn listening time; chapter transitions need not split a session.
- [ ] Keep existing position-recovery records separate in purpose. They contain
  timestamps but are capped at 50 points and record pause/seek events, not full
  sessions. Do not invent historic session durations from those sparse points.
  Session persistence needs bounded growth, interrupted-session recovery and
  deliberate retention/clear behavior. Reading a history entry never seeks.

## 3. Faster opening: measure, then remove avoidable work

- [ ] Record cold/warm timings for catalog ready, player first visible,
  metadata ready, MediaPlayer prepared and restored seek complete.
- [ ] Show cached title, cover, chapter and saved time immediately; distinguish
  "Opening audio" from an actual error and avoid indefinite silent loading.
- [ ] Move optional cover/full chapter metadata work out of the critical path;
  retain only metadata needed for safe cursor restoration. Reuse valid metadata
  and the active player; avoid repeated full scans, remuxes or re-imports.
- [ ] Check large FolioBook cue lists, large catalogs, M4Bs and slow folder
  providers separately. Make any new cache identity/revision aware and bounded.

No loading-speed promise is made from source review alone. Use the same real
files and device for before/after cold and warm measurements.

## 4. Make the performance test represent usable narration settings

Already present: target-word choices (Default/10/20/30/35), preload choices
(1/2/4/8), playback speed, optional real concurrent generation/playback, progress
bars, phases, milestone counts, elapsed time, saved reports and an Apply action.
These exist for Pico and Plapre 3; the request warrants a usability/correctness
follow-up, rather than claiming all of them are missing.

Current mismatches: production lookahead supports 1–32 passages and has its own
startup-buffer seconds; the benchmark supports at most eight preloaded passages,
does not model that startup setting, and uses repeated authored prompts rather
than the production source passage builder. Loading and generation milestones
are coarse; word targets are approximate and constrained by sentence/character
boundaries. The selected test configuration is not a full automatic settings
sweep. Production currently exposes these choices under Read aloud.

- [ ] One shared configuration for production and testing: target words per
  generated chunk, maximum lookahead passages and startup audio buffer seconds.
  Clearly distinguish chunk size, buffer capacity and actual startup fill.
- [ ] Label requested versus actual word counts. Use the same passage-building
  rules and varied authored text; retain sentence/character and PCM/RAM bounds.
  Do not imply more buffering fixes generation slower than playback indefinitely.
- [ ] Offer a quick test of current settings and an optional bounded comparison.
  Apply the complete tested configuration, with clear effective limits if memory
  prevents filling the requested buffer. Avoid an enormous settings matrix.
- [ ] Show loading/verification/warm-up/generation/prebuffer/live-playback/saving
  phases, elapsed time and useful within-phase passage progress. Native model
  loading gets an activity indicator and milestones, not an invented percentage.
- [ ] For Plapre 3, show a clear loading/first-audio message and available native
  preparation stages. Keep cancellation safe and distinguish loading time from
  warm sustained throughput. Do not claim it becomes viable on low-RAM phones.
- [ ] Report startup delay, actual buffered seconds, throughput at chosen speed,
  measured queue waits and memory pressure. Keep modeled versus live results
  distinct; app callbacks do not measure acoustic/Bluetooth gaps.

## 5. Studio live FolioBook output — separate companion follow-up

Studio 0.6 already publishes verified incremental snapshots: narration at complete
chapter boundaries; matching after global matching reaches valid alignment
milestones, with later publications throttled. Files are built temporarily,
validated, then atomically replace the last good export. Android 0.17 can import
newer same-edition revisions while preserving cursor/bookmarks/quotes, but it
refuses replacement while that edition is active in its player. There is no
automatic live desktop-to-phone file transfer or hot audio-file swap.

- [ ] Make latest playable revision, generated audio versus timing coverage,
  last export time and Open latest export visible in the Studio queue.
- [ ] Evaluate bounded partial-chapter snapshots so the first usable narration
  arrives sooner than the end of a long chapter. Never package unfinished WAVs
  or fabricate links. Retain resumable source chunks and stable cue identity.
- [ ] In Folio, offer a safe Update this edition flow: checkpoint, stop/release
  the affected player, validate/stage/swap, restore the mapped position paused.
  Preserve notes and sessions as well as existing annotations. Failed, stale,
  mismatched or unmappable revisions keep the last good playable edition.
- [ ] Keep file transfer explicit for this follow-up. Automatic discovery/sync
  requires a separately scoped transport and provider decision.

## Implementation and qualification order

1. Reproduce position/autoplay failures; fix persistence and playback intent.
2. Simplify chapter controls and labels; add notes and true sessions with migration.
3. Measure/improve loading; align benchmark controls/progress with production.
4. Qualify Android; handle the Studio partial-chapter/export UI work separately.

Use focused policy/storage tests plus real player lifecycle/relaunch checks;
cover normal audio, multi-file audio, chaptered M4B and FolioBooks, including a
newer same-edition update and failed update. Test paused/playing states, speed,
Bluetooth/call interruptions, large text, rotation, low storage and process loss.
Signed upgrade from 0.17.0 must preserve library, positions, existing bookmarks,
quotes, settings and the new data. Build/lint/release checks apply when coding
starts; this assessment alone does not require a rebuild or benchmark run.
