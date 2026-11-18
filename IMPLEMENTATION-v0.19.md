# Folio 0.19 · Godot Cat Café

Implementation authorized 17 November 2026 after user approval of the PC Godot
prototype. Target versionName 0.19.0 / versionCode 29. The approved design and
128-pixel master are frozen; the original 64-pixel source and the PC proof remain
preserved. Production project: `game/miso-home/`.

Required scope: production Android host reached directly from Café navigation,
safe process isolation and background destruction, persistent validated room
preferences/discoveries migrated from 0.18.1, full eight-facing movement, the
existing 21 activities and safe platform/tunnel transitions, native in-game
menus and effects, close camera, early autonomous start, reduced-motion controls,
free test actions, access to prior saved café data, and a signed upgrade.

The garden, upstairs and narrative/social events are later content additions.
Physical-phone battery/temperature claims require physical measurements; no
engine migration alone establishes lower power use.

Acceptance: art review at native/game scale, deterministic headless navigation
and all ordered activity transitions, real Godot renderer screenshots, Android
entry/back/background/reentry and audio coexistence on both 1080-wide profiles,
state migration/restart checks, JVM tests, lint, signed release/upgrade validation,
corresponding source packaging. Record actual completed evidence below before
publishing. Do not label partial animation sets or unfinished actions complete.


Implemented behavior
--------------------

The Android app opens `CafeActivity` directly from Café navigation. It hosts
Godot 4.7.2 in `:cafe`, using the same room as the PC source. Back closes the
current menu first; the next Back returns to Folio. The engine finishes when
backgrounded or covered by another activity. Its audio driver is disabled so
reader narration remains in the main process. The window keeps the display awake
only while resumed. The previous café and classic renderer are retained.

The native room includes all 21 actions, six modes, five paces, contextual menus,
a scrolling action list, warm evening light, ambient plant/toy motion, sunlight
and affection particles, pause and reduced motion. The camera stays close in
portrait and landscape. It converges without rounding lock so resting work drops
to eight frames per second (two with reduced motion); active work is capped at 30.

`CafeGodotState` migrates validated old preferences and discovery counts into a
small atomic JSON document. Counts merge monotonically. Actor position, airborne
state and queued actions are transient, so reentry starts safely on the rug.
The larger-pixel choice survives JSON number decoding and restart. Original
wallet, stories and backups remain in their existing store.

The 128-pixel approved master and original room kit are reused. Native 96/128
exports share apparent scale. The production library has 28 clips, five authored
walking viewpoints plus three mirrored facings, and 60 new complete activity
and direction drawings. Whole-cell export preserves identity and authored motion;
explicit grounded roots prevent curled/crawling poses from floating. The shader
masks Miso against furniture height, box frontage and tunnel interior. No runtime
sprite reconstruction, palette reduction or old-atlas replacement is involved.
New artwork used the built-in image-generation tool; exact prompts, original
outputs and native reviews are in `game/miso-home/art-source/production/`.

Godot’s MIT and bundled component notices are included in the APK and accessible
from Folio’s Licenses screen. The APK retains all four supported CPU architectures.

Verification completed before device qualification
--------------------------------------------------

- Deterministic planner: all 21 actions, 441 ordered pairs, 950 jumps, safe queued
  commands/cancellation/descent and collision-free floor movement; all eight
  headings across 72 angles.
- Actual GPU review of both densities, eight viewpoints, feeding from both sides,
  scratching, grooming, play, sleep on raised platforms, box/tunnel masking and
  mid-jump frames at the step, perch and bookshelf.
- 233 JVM tests: 232 passed, one existing skipped test; lint has no errors.
- Debug, instrumentation and signed release builds succeed. Runtime pack hashes
  match across the signed and debug APKs. Physical-device power is not measured.


Android and signed upgrade evidence
----------------------------------

The 1080 × 2520 emulator passed all four native integration tests, including every
activity. The 1080 × 2280 emulator passed the three overlapping checks. Together
they cover navigation, touch scrolling, preference migration and restart, Back,
landscape, reduced motion, idle rendering and continuous narration when the room
closes mid-jump. The real signed APK installed over 0.18.1 without removing the app
or clearing storage. Five durable files, including the classic room preferences
and café database, were byte-identical before first launch. Opening Café migrated
the prior room state, rendered the new room, and released the engine on Home.

The broader notebook regression run passed three tests. The maximum-length case
preserved edited text across rotation but found the caret outside the landscape
viewport. The notebook editor, storage and editing sources match 0.18.1.
