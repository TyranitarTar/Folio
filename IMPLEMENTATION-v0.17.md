# Folio 0.17.0 implementation notes

The chapter position control appears on the main player below Current passage. The toolbar and chapter bar share one immediately toggled seek lock; transport, speed and menus remain usable. Unlocking exposes a proposed position, which still requires Apply. Chapter list jumps are also protected by the same lock.

Current passage settings are available from the passage card and player menu. The per-recording offset ranges from −30 to +30 seconds with a half-second slider and 0.1-second nudge buttons: positive values display passages later. Reset restores zero; Apply persists and Cancel discards the draft. The saved audio cursor, sleep boundaries and recovery history use actual playback time. The displayed cue is independently resolved across known track durations and gaps; saved quotes use the displayed cue's own track and start time. Older catalogs default to zero and normal reimport of the same recording preserves the setting.

Automated checks should include unit tests and the audiobook instrumentation suite. Physical-device behavior and accessibility should also be checked when hardware is available.

Studio snapshot imports accept additive manifest v1 edition and revision fields. Each complete staged ZIP is checked against its EPUB and audio hashes before publication. A newer revision for the same edition replaces its files with a durable rollback receipt; an interrupted swap or first import is recovered at the next catalog load. Same-revision data is checked against the retained manifest fingerprint and audio digests. Existing cursor, bookmarks, history, shelves, speed, timing offset and linked EPUB are retained. Updating an active edition requires stopping playback first. Older revisions are ignored, and conflicting identity or changed chapter order is rejected. For alignment-only updates, playback, bookmark and history times remain unchanged; quote targets follow uniquely matched passages. For changed audio, used playback, bookmark, history and quote positions map through unique source-passage identities, preserving their bounded offset within the passage. Ambiguous or unmappable used positions reject the update and preserve the previous edition. Quote targets are stored with the audiobook without rewriting EPUB annotations. The app checks declared audio digests, not independent acoustic equivalence between encodings; revisionId is treated as an opaque revision identifier because cross-language numeric canonicalization is not specified.

Incomplete editions show the available passage count when supplied, or the number of available audio files. Chapter coverage distinguishes incomplete audio from a complete recording with incomplete passage links. Finishing currently available partial audio does not automatically mark the book Finished, and adding chapters clears an automatic finish state. The chapter sheet exposes the same seek lock as the player. Snapshot and player instrumentation covers revision replacement, active playback, corrupt input, rollback, settings persistence and lock behavior; actual execution results should be recorded with release validation.

## Final local qualification — 31 October 2026

The signed release is 0.17.0, version code 23. The final Gradle build passed
unit tests, lint, debug, instrumentation and signed release assembly. JVM totals:
215 tests, 214 passes, one existing platform skip, no failures/errors. Lint has
no errors. All 24 selected emulator cases passed (six focused and 18 regressions).
The regression test was updated to open the chapter sheet through its fully
visible toolbar menu rather than clicking a partially visible bottom button.

Three actual Studio-exported, playable MP3 snapshot fixtures verified partial
completion, appending a chapter, changed-audio cursor/bookmark/quote remapping,
older-revision handling and real playback. Corrupt/conflicting/unsafe snapshots,
active-playback refusal and interrupted-import recovery also passed. No private
book, real model or generated user audio was needed for these fixtures.

A signed in-place emulator upgrade from 0.16.1 retained all 14 checked durable
library/settings files byte-for-byte, with no uninstall or data clear. The
signing certificate, sixteen native libraries (apart from GNU build IDs) and
voice-pack catalog match 0.16.1. The APK contains no model weights. Physical-phone
Bluetooth, accessibility, sustained listening, heat and battery acceptance remain
open. Detailed final artifact checks are in validate_release_v017.py and the
release validation JSON. Public GitHub discovery is verified separately after
publication.
