# Folio 0.18.1 · notes, lookup, Café motion and resource use

Requested 16 November 2026. Notes now begin with a chapter-relative timestamp
and sequential C1/C2 chapter label. Enter on a nonempty bullet creates another
timestamped entry using current playback; multiline paste and ordinary paragraphs
remain unmodified. Opening/scrolling does not save an empty context line, seek,
start or pause audio. Existing text and legacy recovery files are preserved.

Native note scrolling now owns its drag gestures and suppresses forced cursor
reveal while the user browses earlier text. Viewport restoration is independent
of caret restoration, so keyboard/layout changes do not always return to the end.
Typing, search and explicit selection moves still reveal the intended caret.

Reader selection retains Ordnet and adds Google Translate's website targeting
English. Source language uses publication metadata when available, otherwise
automatic detection. Selected text is URL-encoded and opened only on that action.

The Café uses Android's visible-window KEEP_SCREEN_ON flag, released on leaving
the room, covering it with another app page or backgrounding. It creates its
WebView only for the foreground room. AndroidView onRelease destroys that view,
bridge, JS timers, listeners and canvases; returning reloads local assets and
validated discoveries/preferences, starting Miso safely on the rug. Brief Café
action/settings sheets suspend rendering without rebuilding the room. Loss of
window focus also suspends the renderer. No global WebView timer pause is used.

Scheduling now sleeps between frames: up to 24 fps for movement, 8 fps for quiet
ambient animation, 4 fps with ambience off, and 1 fps when reduced motion is idle.
Pause cancels both the timeout and pending animation frame. A fixed room layer
is cached; only small foliage, water/toy elements and the cat are composited on
subsequent draws. Cache resolution follows the actual displayed room size,
retaining 2× detail when needed. The canvas itself fits the room crop, avoiding
repainting wide empty margins. These changes reduce repeated rendering work;
they do not establish a measured battery saving on a real phone.

Canvas backing size follows actual bounds × device pixel ratio without the old
3× ceiling. Environment props/materials/tiles are re-exported at 2× resolution
from the existing larger originals, preserving logical cells, anchors, scene
geometry and palette identity. The shell and room cache retain that source detail.
Miso remains intentionally native 64×64 pixel art with crisp sampling and 1.30
display scale. Higher device resolution cannot add details to those original
character pixels. Larger caches use more memory while the room is open and are
released when its view is destroyed.

Movement uses direct visibility paths and smoothed eight-neighbor detours, with
analytic segment/expanded-furniture intersection tests so thin corners cannot be
skipped by coarse samples. Eight visual facings accompany arbitrary-angle travel.
New front/rear/profile run cycles supplement existing isometric gaits; the old
289 frames / 34 clips and protected runs remain unchanged. See
[sprite prompts, source and review](game/eight-direction/README.md).

Actual Fold7 temperature/battery drain cannot be confirmed from the emulator.
No physical phone was connected during implementation. Verification distinguishes
visible-room draw counts, absence of live Café views offscreen, and emulated
large/high-DPI rendering from physical-phone power/thermal measurements.
