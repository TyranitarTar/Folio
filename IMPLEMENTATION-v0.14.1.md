# Folio 0.14.1 — Reading and fast audiobook libraries

25 October 2026. Android version code 19. Follow-up to the user's original
0.14 list and the Smart AudioBook Player comparison. Release gates and measured
evidence are in `output/Folio-0.14.1-validation.json`.

## Delivered

- Reading is the visible e-book section name. Internal Books preference/state
  keys remain compatible. The redundant global Read + listen link is removed;
  useful per-title narrated-book actions remain.
- Reading and Audiobooks share `LibraryCoverTile` and `LibraryTitleRow`, with
  comfortable/compact grids, list mode, cover badges/progress and generous play
  targets. Both use toolbar search and Filter/Sort/Display settings. Audiobook
  preferences are separate and included in typed settings backup.
- Matching title/Library headers for the reading page and audio player. System
  Back leaves full-screen reading directly; an open sheet closes first. A direct
  library request collapses reader/player activity stacks and selects Reading
  or Audiobooks as appropriate. Background audio continues, with library
  pause/resume/stop controls.
- A visible Audiobooks toolbar rescan visits saved roots, discovers additions,
  reports progress/results and supports cancellation. Without a root it opens
  the add-folder controls. Source access failures explain relinking.
- Ordinary root audio is indexed by persisted SAF URI, without media copying,
  content hashing or synchronous decoder metadata reads during discovery.
  Names/order/provider metadata are already available from directory queries.
  Duplicate-discovery and cached-ID checks use sets rather than repeated list
  scans. Existing copied editions keep their media and positions.
- Durations are cached afterwards in a cancellable background job that yields
  to active narration. First playback also records decoded duration. Unknown
  totals are labelled; incomplete duration metadata cannot prematurely mark a
  last chapter read. A linked title survives missing-folder access so its saved
  cursor is available for repair. Explicit relinking of matching linked titles
  and ordered track names retains identity/cursor/speed/bookmarks.
- Linked book IDs are stable per source folder. Added tracks remap cursors and
  bookmarks by URI, and cursor files include a short source-track digest so an
  interrupted catalog/cursor update cannot attach a position to a shifted index.
  An active/paused loaded playlist is not rearranged during refresh. Missing or
  renamed tracks keep the old entry for explicit repair/removal rather than
  silently losing marks. Other books can still be discovered in that scan.
- FolioBook ZIPs and explicit file/one-book-folder imports retain extraction and
  private offline copying. Linked source audio must remain available at its
  selected folder/provider. Removing a linked entry preserves original files.
- Page changes reuse verified resource geometry. Concurrent duplicate probes
  are coalesced; new content/layout still verifies. Count publication precedes
  optional DOM boundary work. Finding the previous printed word at a chapter
  boundary walks backward from its anchor instead of scanning the full document
  prefix for every anchor. Page-turn callbacks retain their latest page index.
- Shorter voice cards combine preview/main actions, collapse technical detail
  and keep removal accessible. No voice weights/native speech sources changed.

## Evidence and limits

The focused emulator suite covers shared library settings, full-screen exit,
continuous playback after returning to the library, real decoder/seek/track
behaviour, copying selected text, guided pause, and independent settled page
counts. The new folder case exercises the existing authored SAF tree, zero
copied audio bytes, direct original-URI playback, duration caching, rescan and
position/bookmark preservation. Host tests additionally exercise indexing with
a nonexistent provider: the index path must not open media at all. A relink
round-trip preserves the catalog ID and saved state.

Final folder screening: one added two-track title alongside two cached books
indexed in 105 ms, then rescanned in 84 ms, with zero copied media bytes. This
is a small emulator fixture, not a physical-phone or large-library benchmark.

Pagination screening: 40 callbacks reused one verification, rather than
starting more probes. Independent settled resource counts matched `[1,5,9,4]`;
the final emulator run recorded 2,411 ms cold activity/count startup and 1,221 ms warm
startup. These include activity startup and are diagnostics, not a controlled
before/after speed comparison. Exact cold totals still require rendering unknown
resources, especially after typography/viewport changes. Large books, unusual
images/TOCs and S10/Flip7 speed remain further acceptance work.

The host suite completed 184 cases with 183 passing and one skipped, with no
lint errors. All 17 focused emulator cases passed. The in-place debug upgrade
from 0.14 preserved all 36 checked data files. The final signed release upgrade
preserved all 11 checked release-app files, using the same signing certificate.
No physical phone is modified in this session. Android 35 emulator checks do
not certify battery, heat, Bluetooth or provider performance on a phone.

SDK 35 filesystem behaviour is covered on the emulator. One Windows host
persistence test uses Robolectric SDK 28 because SDK 35's POSIX rename-over-base
AtomicFile behaviour differs from the Windows host; production storage checks
are unchanged.

See [the comparison and next useful player features](SMART-AUDIOBOOK-COMPARISON.md)
for auto-rewind, folder artwork, history, queues, configurable skip intervals
and future large-catalog/seekable-bundle work. Static reference disassemblies
and the supplied APK are kept outside public source packages.
