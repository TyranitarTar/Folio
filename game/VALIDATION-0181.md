# Folio 0.18.1 validation · 16 November 2026

Signed Android release: versionName 0.18.1, versionCode 28. The final release
validator checks the APK, signing certificate, complete packaged room assets,
corresponding source archive and recorded tests before publication.

229 JVM test cases (one skipped), no failures or errors; Android lint has no
errors. Six emulator tests cover the real room, lifecycle, window keep-awake,
pause/resume, unloaded views on navigation/background/covered pages, preferences,
all room actions, landscape, long-note scrolling/restoration, timestamps and the
existing notes migration/copy/undo/redo/audio-isolation flow. A seventh test
repeats room lifecycle/rendering at a simulated 1920×2160, density 480 display;
the original emulator display overrides are restored afterward.

The shipped JS host check exercises loading, DPR 3/4, single owned scheduler,
pause/visibility/cleanup, routines, queue/cancel, feeding, tunnel and support
routes. Movement checks cover 72 arbitrary-angle clear paths, eight facings,
59 densely sampled obstacle detours and a thin-corner regression. The original
action simulation, adapted to shipped assets, covers 21 actions, 441 ordered
transitions and 625 jumps with zero walking collisions. Real renderer mask checks
cover both bowl sides, supported platforms, elevated naps, window and tunnel.

All 289 original frames and 34 original clips compare exactly with the preserved
baseline. Added native drawings produce 310 frames and 40 clips with the same
64×64 canvas, [31,55] root, 16-color palette and 1.30 display scale. New loops have
agent visual review; physical-phone/user art acceptance remains outstanding.

A signed 0.18.0 → 0.18.1 emulator installation preserves existing durable data
byte for byte before first launch, without uninstalling or clearing data.

Visible-room diagnostics verify at most 20 idle paints over two seconds and zero
live room views after leaving, covering or backgrounding it. No physical phone
was connected: these checks do not confirm actual Fold7 temperature, excessive
battery use or a measured battery saving. The larger display is a simulation,
not a claim that this exact hardware configuration matches a Fold7.
