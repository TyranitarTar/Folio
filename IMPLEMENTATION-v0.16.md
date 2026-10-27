# Folio 0.16.0 — audiobook player and library

27 October 2026. Version code 21, same application/signing identity as 0.15.0.
The Smart AudioBook Player reference informed the navigation priorities; Folio
uses its own code and artwork. No reference APK assets/disassemblies are shipped.

## Listening

The book timeline is a read-only progress marker. Elapsed / total recording time
is separate from remaining listening time, calculated as remaining audio divided
by the current speed. Unknown durations are labelled as loading. Four transport
buttons move back/forward 10 or 60 seconds across known track boundaries while
preserving the paused state. Play/Pause is central; speed and Stop remain nearby.
The title is not repeated as a filename. Linked spoken text remains visible.

Chapter progress is smaller and lower down. Adjust opens a proposed seek;
dragging does not move playback, and Apply commits it. Chapters and bookmarks
are hidden sheets opened deliberately; selecting a destination asks for a jump
confirmation. Previous positions remain in an overflow recovery action to undo
an accidental seek. Sleep timer, adding bookmarks and playback settings are
secondary menu actions. Returning to the library keeps background playback.

Bluetooth & headset settings are available from More and the player menu:
headset/media-session Play/Pause, next/previous as seconds or chapters, skip
intervals, disconnect pause, temporary focus pause versus duck, resume after
temporary interruptions, sleep preset and optional rewind after longer pauses.
Permanent audio-focus loss stays paused. Manual Pause/Stop cancels automatic
resume; default headphone disconnect also pauses. No call-log, phone-state or
notification-listener permission is requested. This controls recorded audio;
it does not replace the independent synthesized-speech engine settings.

## Libraries and shared positions

eBooks/Audiobooks share shelf selectors and management, status swipes and fixed
2–4 column grids. Old 5/6-column preferences migrate to four. Pager contents
align at the top rather than centring short grids vertically. Toolbar spacing
is reduced while action targets remain usable. List Play buttons are 56 dp.
Full-width long-press rows include manual New/Reading/Finished status, shelves,
open/read, split-file correction where applicable and deletion. Redundant More
display/shelves actions, sample-book actions, duplicate audiobook overflow
settings and per-book New badges are removed. FolioBooks have a small headphone
marker in grid and list layouts.

Recorded listening checkpoints map the actual matched cue into the EPUB's
Readium resource weights and update its saved reading cursor. Observed-time and
session checks reject stale audio writes after newer manual reading. Opening
the EPUB can restore the newer linked listening passage. Unaligned audio gaps
do not invent a text position. Browsing an EPUB alone does not silently seek the
recording; explicit Listen from here remains the reverse navigation action.
Audio-time and text-position percentages are intentionally different measures.

## Sources, artwork and removal

New imports retain their granted original document URIs. Delete confirms the
specific files and removes originals only where the provider grants write/delete
access. Keep original files removes the Folio entry/private copies instead.
Older copied imports cannot rediscover an unrecorded original location safely.
Shared source files and root directories are protected. EPUB originals are
content-hash checked before deletion. A per-book receipt retains successful
file deletions for retry after a provider fails; bookmarks/catalog remain until
source deletion succeeds. Provider-side deletion cannot be rolled back.

An additional explicit checkbox can delete a verified book subfolder and its
remaining contents. It refuses roots, shared entries, unrelated recordings and
ambiguous nested series folders. Verified folder deletion needs Android 10+ and
provider support; individual source-file deletion remains available otherwise.

For a series incorrectly treated as one playlist, Split files into separate
books preserves per-file bookmarks, shelves, speed and the active cursor. A
saved root rule prevents the next scan from merging those files again. It is a
deliberate correction, not a guess that every MP3 track is a separate book.
Artwork discovery prefers title-matching, cover/folder/front/book images, then
an unambiguous single image. Image inputs are bounded at 16 MB and downsampled.

## Verification and remaining acceptance

Release qualification is recorded in `Folio-0.16.0-validation.json`, including
JVM tests, lint, isolated emulator tests, signing/native parity and upgrade
checks. Authored fixtures cover read-only progress, four skips, protected chapter
navigation, playback-speed remaining time, shared cues, focus transitions,
full-width menu actions, grid alignment and source/provider deletion failures.
Existing M4B, root scan, page-cache and library regression cases are retained.

Physical Bluetooth/call behavior, S10/Flip7 performance and the user's exact
Mars Trilogy/cover layout still need device/source acceptance. Recognition
quality and generated-audio throughput are Studio concerns, not certified by
these player checks. Exact uncached page totals still require layout of unknown
resources; no guessed page count is presented as an exact total. Cat Café and
voice models remain unchanged.
