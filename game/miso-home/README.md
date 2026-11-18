# Miso’s home · Folio 0.19.0

Production Godot 4.7.2 room, integrated in the Android application. Open
`project.godot` and press F6/F5 to run the same room on a PC. The approved PC
prototype remains separately preserved in `game/godot-cafe`.

Tap floor for direct travel, furniture for contextual activities, and the bottom
buttons for all 21 activities, Miso, and room settings. Back closes a menu, then
returns to Folio. The close camera follows Miso in portrait and landscape.
Six behavior modes and five movement speeds are available. Every action is free.

Miso has five authored walking viewpoints and three mirrored counterparts,
covering all eight directions while moving at arbitrary angles. Complete poses
cover feeding/drinking from both approaches, scratching, grooming, curled rest,
sleep, play, jumping, and crawling through the tunnel. The 28 animation clips
include 60 new complete drawings alongside the approved master and SW walk/idle.
Choose 128 native pixels (default) or 96 at the same apparent size. Nearest
sampling preserves pixel edges; the interface renders at display resolution.

The original room kit is reused. A height-aware GPU mask handles furniture,
platforms, the box front and tunnel interior. Ambient cutouts animate the original
plant/toy artwork; native particles add sunlight dust and affection hearts.
Reduced motion and ambient controls are available. Hearts are a feedback effect,
not a hand-petting character animation. Garden, upstairs and new stories are
future content.

## Android lifecycle and state

`CafeActivity` is nonexported and runs in the dedicated `:cafe` process. Navigation
loads the room on demand. Back, switching apps or leaving the activity destroys
the engine; this process may exit without ending the reader or narration service.
The room keeps the screen awake only while foregrounded. Audio input is disabled
and the room uses Godot’s Dummy audio driver because this room has no soundtrack.

`CafeGodotState` migrates previous room settings/discoveries into an atomic local
JSON document. Counts are bounded and merged monotonically. World position,
airborne state and action queues are deliberately transient; reopening places
Miso safely on the rug. Existing café wallet, chapters, backups and the previous
renderer remain available through “Tidligere café og gemte data.”

Animation scheduling is capped at 30 fps while active, 20 in menus, 8 resting,
and 2 when paused or at rest with reduced motion. No rendering engine remains
running after the activity closes. These lifecycle checks do not establish
physical-device battery or temperature figures.

## Build and verify

Run `python game/miso-home/build.py --review` from the Android project with Godot
4.7.2 available (`--godot` overrides its path). Add `--art` to reproduce native
PNGs from preserved originals; this requires Pillow. The build imports resources,
checks all 441 ordered activity transitions, exports
`app/src/main/assets/cafe-engine/miso-home.pck`, and optionally captures actual
renderer screenshots. Then run `build.ps1` for the Android app. CI uses the checked
pack and Maven Godot dependency, so it does not require a Godot editor installation.

Android tests cover migration, real menu scrolling, restart, all 21 activities,
landscape, reduced motion, early autonomous start, mid-jump background destruction,
and uninterrupted audiobook playback. The debug-only same-UID test bridge stores
status in memory. It is not registered in the signed release.

Use a working GLES3 driver. The old Windows emulator SwiftShader path could not
compile stock Godot shaders; the test emulator uses its host GPU. Always inspect
rendered pixels, not just a ready callback.

## Artwork and provenance

New raster artwork was created with the built-in image generation tool from the
approved Miso master. [Production prompts](art-source/production/PROMPTS.md)
link each prompt and preserved original. `export_art.py` uses fixed cells,
thresholded transparency and a constant sheet registration. Grounded curled/crawl
poses have explicit root metadata; walking bob and airborne jump registration are
preserved. No palette quantization or per-frame silhouette fitting is used.
The old 64-pixel originals and source handoff are retained separately.

Godot is MIT licensed; see `LICENSE-GODOT.txt`. Existing room artwork retains its
original provenance in Folio’s classic café assets.
