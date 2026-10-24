# Folio 0.14 implementation and verification

24 October 2026. Android version 0.14.0, version code 18.

## Delivered behavior

- Shared All / New / Unread / Read library filters. New means never started;
  Unread includes unfinished books. Manual read/unread overrides preserve the
  actual reading/listening cursor. Legacy filter preferences migrate once.
- A searchable/sortable audiobook library, linked EPUB covers and labelled
  Listen/Resume actions. Files, one-book folders, .foliobook imports and saved
  library sources are under the separate **+ Add audiobooks** sheet.
- Saved SAF roots: one folder per book, nested CD/track folders, natural relative
  path ordering, refresh checkpoints and missing-folder relinking. Unchanged
  sources skip reimport when metadata permits. Changed recordings retain their
  previous edition/positions. Forgetting a source retains imported audio.
  Bounds: 200 book folders, 256 tracks/book, depth 8, 50,000 scanned entries.
  Providers with missing metadata use the slower content-checked import path.
- Permanent centre Café destination. Compact tabbed Filter/Sort/Display settings;
  no duplicate café item under More. Narrow-screen labels/tabs stay on one line.
- Reader footer shows book and rendered chapter page counts using the existing
  layout cache and measured DOM boundaries. Chapter counts do not estimate from
  words. Unknown boundaries show a calculating/locating message. Image-only
  pages and unusual TOCs still need wider-book checks; do not promise universal
  print-page equivalence. Font/layout changes use existing invalidation.
- Selection actions: Copy, online Danish dictionary via an encoded Ordnet query,
  Highlight, note and narration. The external lookup needs a browser/network;
  no dictionary corpus was bundled. Full saved quotes are shown/exported with
  their stable locators. Book text stays unchanged.
- Guided Resume/Start side by side, live focus-mode preview, uniform multiword
  colour including spaces. Retired countdown/practice/gradual-increase options
  normalize off. Menus, typography and selection pause guided reading.
- Read-aloud entry actions and diagnostic/prepare-ahead actions share compact
  rows. Thread settings are under Advanced. Existing reader stop/skip controls,
  speech identity and saved-position behavior remain in place.
- Advanced Plapre passage target, lookahead and startup-buffer settings affect
  live and prepared speech. Defaults retain previous grouping. Source locators
  and chapter limits remain attached to every passage.
- Diagnostics: thread/group tests, configurable words/preload/rate, phases,
  milestones, elapsed time and saved comparable reports. The optional audible
  trial uses real generation concurrently with AudioTrack playback, and reports
  generation/playback overlap, applied rate and queue waits. It has audio focus
  and cancels when backgrounded. The separate simulation stays explicitly
  labelled; queue callback waits are not acoustic gaps/Bluetooth latency.
- Compact expandable voice cards keep samples/install/remove/use accessible;
  device-demand advice distinguishes recommendations from unverified guarantees.
  Pico remains available. No new speech weights or native inference code. Twelve native libraries are
  byte-identical to 0.13; four Pico wrappers differ only in GNU build IDs. Their
  executable/data bytes and corresponding C++ sources are unchanged.
- Word totals lead book statistics; secondary chapter distribution/details expand.
- User-selected **2 — Bookmark Cat** icon, with adaptive/legacy/themed variants.

## Data and release checks

Passed: **178 Android unit tests (one additional test skipped)**, lint with zero errors, **17 Android 35
emulator cases**, the signed release upgrade, real SAF root discovery/refresh,
and playback of the authored Danish Pico export. Studio passed 38 unit tests,
38 tests from its extracted source archive, eight packaged queue executions,
hidden startup and real Pico selection/regeneration/export checks.

The first emulator attempt was obscured by a System UI stall under build load;
it was rerun after restart. A later home test tapped before Compose scrolling
settled; waiting for the scroll fixed the automated interaction. The test SDK
was staged explicitly offline after the sandbox-denied hidden Maven cache
caused Robolectric to fall back to Android stubs. No app behavior was weakened
to bypass these checks. Removed 305,657,876 bytes of duplicate staged test jars.

Unit tests, recorded-audio/device-emulator tests, upgrade and package evidence are
recorded in `output/Folio-0.14.0-validation.json`. Unit checks cover full quotes,
chapter boundaries, passage coverage, manual status and typed settings backup.
The in-place debug 0.13 to 0.14 upgrade preserved all 28 checked files, including
six EPUBs, library records, reader state, audiobook metadata and preferences.
It did not uninstall, clear data or touch a physical phone.

Studio 0.3's authored Danish Pico export also checks real chapter selection,
cached-audio reuse and selective passage regeneration. The original EPUB bytes
remain identical. Models, private books, generated user audio and signing keys
are excluded from public source archives.

## Verification limits

The Android 35 emulator is functional evidence, not S10/Flip7 speed, thermal,
battery or Bluetooth certification. Final physical-device narration/listening,
large-font/foldable visual review and unusual provider relinking/revoked grants
remain manual acceptance items. The older Studio desktop window was not taken
under computer control; its hidden startup/packaged worker checks are functional,
not visual review. The Miso story/animation redesign remains a future todo.

No GitHub publication or public in-app update discovery was performed for this
local release. Existing update infrastructure is unchanged. The desktop GPU
study and prototypes are documented separately in Studio's PERFORMANCE-0.3.0.md;
no complete GPU-only speech backend is silently enabled.
