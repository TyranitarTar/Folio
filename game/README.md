# Folio 0.18 · Miso's room

The two laptop ZIPs are copied here without modification. SHA-256 copy checks
are recorded in transfer-checksums.json. Only IMPLEMENTATION is extracted, into
Folio_Miso_Handoff/. The full 953 MB archive stays available for historical art
and provenance; neither archive is bundled in the Android app or source ZIP.
LAPTOP-HANDOFF-2026-11-15.txt is the supplied continuation handoff.

The authoritative source is the revised-in-place
Folio_Miso_Handoff/pixel-cat-experiment/production/playful-room-playable-v2/.
Its standalone index.html and generation prompts are preserved. The active
Android integration lives in app/src/main/assets/cafe/ and CafeRoomScreen.kt /
CafeRoomView.kt, rather than modifying or replacing the standalone reference.

Run `python game/build_cafe.py` from android-reader to verify all 234 transfer
manifest entries and stage the 27 unchanged runtime files. The host page,
host.js and director.js are Folio-owned. provenance.json records copied hashes.
The complete artwork pipeline is retained in the extracted handoff for future
sprite work; a source checkout can run/build Folio with just its staged assets.

Run `node game/check_cafe.cjs` with ROOM_CANVAS_MODULE pointing to an installed
@napi-rs/canvas module (development only). It exercises shipped engine/director/
host behavior with Canvas, including lifecycle and cleanup. Supplied checks are
under the handoff project's source/ directory. Android tests are
CafeRoomPreferencesTest and Cafe019InstrumentedTest. Existing café storage and
reward tests still cover the preserved earned/test profiles.

Room saves contain validated discovery counts and preferences, not airborne
positions or in-flight plans. Navigation/recreation starts Miso safely on the
rug. Routine selection persists; routine step/session position does not.
Manual commands select manual mode; a command during a movement waits for its
safe completion. Stop ends at a movement boundary. Reset to rug is an explicit
testing control and preserves discoveries. Opening settings/action sheets
suspends the room while the menu is visible. Pause also stops its render loop.

The current room is fixed. No garden, economy, furniture editor, additional cats
or new stories were added. Native 64×64 master art, palette, 1.30 display scale
and protected run/sprint clips remain byte-identical to the handoff.
