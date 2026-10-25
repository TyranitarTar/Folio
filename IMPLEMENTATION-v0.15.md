# Folio 0.15.0 — compact libraries and chaptered audiobooks

25 October 2026. Version code 20. Studio and Cat Café changes are deferred.
Current voice packs and native speech runtimes are retained.

## Changes

- Shared swipeable All / New / Reading / Finished library pages, keyed so each
  page keeps its own scroll state. New means not started, Reading means started
  and unfinished. Preferences migrate by schema; manual completion flags and
  saved positions retain their internal compatible representation.
- Shelves move into the Reading title selector. The extra shelf/filter rows
  are removed; navigation uses a compact 64 dp row with a legible Audiobooks
  label and one system-inset layer. Import controls remain under +. Audio has
  its three-dot menu as well as toolbar search/settings/rescan.
- Embedded and conventional folder covers are reduced to bounded cached
  thumbnails and reused in the library/player/mini-player. The metadata pass
  fills duration, artwork and author after discovery, yielding to playback.
  Selected-track metadata is prepared before opening audio, so M4B chapters
  are available for immediate navigation.
- Audiobook folders beneath author/series containers are discovered as separate
  books, CD/disc folders stay together, standalone M4Bs stay separate, and
  overlapping roots reuse matching provider/document identities. Supported
  formats agree across discovery/import/playback. Unsupported audio is reported;
  one bad bundle does not prevent unrelated books from being indexed.
- M4B metadata supports Nero chapter lists and referenced QuickTime text tracks
  through a bounded random-access reader. Audio payloads are skipped. Chapter
  names/times are metadata, not new physical audio files. Chapterless/unsupported
  metadata falls back to file/track navigation. This is not a claim of support
  for every unusual edit-list, fragmented or vendor-specific M4B variant.
- Listening combines artwork/title, prominent transport, chapter selection,
  separate chapter/book seek bars, speed/sleep/bookmarks and a bounded recovery
  history. Whole-book seeking maps back to the original file and offset. Seeking
  while paused stays paused; unknown duration ranges stay disabled/pending.
- Rewind/forward intervals are configurable and shared with notification and
  headset skip controls. Optional automatic rewind after a longer pause is off
  by default. Saved seek/pause history is separate from permanent bookmarks.
- New recordings enter Reading after actual playback starts. Their automatic
  Finished status follows actual final-file completion, not the former final
  one-second threshold or a seek to the end. Explicit manual overrides remain.
- Version history is reached through About Folio. Check/download/progress/
  verification/install share a top update area above release notes. Existing
  checksum, signing-key and Android installation handoff remain.

## Compatibility and checks

Existing physical track indices and millisecond offsets are retained. Added
chapter/artwork/history/completion fields are optional in the existing catalog;
older records and private imports remain readable. Audio playback settings are
included in typed settings backup. Ordinary source audio remains linked; explicit
imports and FolioBooks retain private copying. Imported whole-book audio can use
the existing overall 4 GB bound instead of the old 256 MB per-file bound.

Verification details and exact counts are recorded in
`output/Folio-0.15.0-validation.json` at packaging time. Tests include authored
AAC M4Bs with known chapters/artwork, both chapter metadata layouts, malformed
metadata bounds, nested folder discovery, status migration/filter semantics,
bookmark/history remapping and real Android decoder/UI checks. In-place upgrade
evidence distinguishes byte-preserving installation from subsequent legitimate
metadata caching. Emulator checks do not establish physical S10/Flip7 speed,
Bluetooth, heat or battery performance. No phone is modified during these checks.

The final broad suite reports 28 cases, with its optional private recorded-book
case skipped when no input is supplied. That case was then explicitly run with
an existing authored Pico export and a separate positive playback/linking proof:
28 distinct cases are verified across both invocations. Earlier navigation-only
test failures and their fixes remain in audit logs. No new synthesis was needed.

## Explicit later work

The user deferred Studio and Cat Café development and cancelled smaller Danish
models, additional Kokoro voices and heavier phone-native Danish/Røst research.
No new model is downloaded or integrated here. Larger library/provider acceptance,
an offline-copy repair flow, deferred/seekable bundle architecture, measured
catalog-storage changes and a deliberate next-book queue remain follow-ups.
Joint UI and physical-device acceptance remain distinct from implementation.

The new reverse-Studio idea is planning only:
[existing audiobook + EPUB alignment](design/desktop-audiobooks/REVERSE-ALIGNMENT-TODO.md).
It does not add ASR or alignment code/models to this APK.

## Format reference

The M4B reader is an original bounded Kotlin implementation. Chapter structures
were checked against the primary
[FFmpeg MOV demuxer reference](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mov.c).
The supplied Smart AudioBook Player APK remains a static reference; no proprietary
player code is copied or bundled. No new runtime dependency is added.
