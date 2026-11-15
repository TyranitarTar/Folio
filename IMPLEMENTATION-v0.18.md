# Folio 0.18.0 Â· Cat CafÃ© companion room

Requested 15 November 2026. CafÃ© now opens the reviewed sprite-based Miso room
from the laptop handoff. The canvas occupies 60% of the available cafÃ© content
below its small title bar. Remaining controls scroll on short/landscape screens.
Android navigation and the audiobook mini-player retain their existing space.

The offline Canvas engine/renderer and 27 runtime files are copied from
playful-room-playable-v2. No character or environment art is regenerated.
The room retains 289 native cat frames, 34 clips, 21 activities, five speeds,
64Ã—64 source cells/root [31,55], uniform 1.30 scale, and height-aware masks.
All graphics, metadata and engine/renderer bytes have staged provenance hashes.

Folio's director adds random exploration and four repeating authored routines:
morning, play, climbing/rest and all-activity tour. It leaves seven simulated
seconds between activities, suppresses immediate random repeats, and bounds the
engine's diagnostic history to 256 entries for long sessions. Commands switch
to manual mode and use the engine's queue/landing/wake contracts. Settings
changes do not discard pending actions. The native menu exposes every activity,
safe descent, stop at a step boundary, and return to the rug.

One WebView owns one RAF loop and ResizeObserver. Android lifecycle, native
menu visibility and document visibility suspend simulation and RAF; resuming
resets elapsed-time tracking. Compositing is capped at 30 fps, with backing
pixels derived from actual bounds and DPR capped at 3. Disposal removes bridge,
listeners, canvases and WebView; renderer-process failure offers a new instance.
The native frame and controls use the app theme; the art keeps its room colors.

The WebView intercepts only bundled HTTPS-origin cafÃ© resources, rejects
navigation and traversal, blocks network loads, and disables file/content URL
access and DOM storage. Its bounded JSON bridge returns status, furnishing picks,
discovery counts and load errors. Room preferences/counts use cafe-room-v1,
independent of cafÃ© SQLite and reader state. Invalid counts/enums are ignored.
Only stable discovery counts persist; position/actions restart safely on the rug.

The previous cafÃ© is accessible via room settings â†’ Tidligere cafÃ© og gemte data.
Earned/test profiles, treats, reading rewards, story state and existing
export/restore are preserved. Free room tests never spend treats or unlock old
chapters. New Tales/Persona-inspired vignettes/social events remain future scope.
Existing story UI stays as a legacy view of saved content.

Reduced motion follows the system animation scale or a cafÃ© preference. It
disables ambient furniture motion and reduces idle redraws; explicit cat actions
remain available. Pause stops all room simulation and repainting. No room audio
or speech commands are introduced, so the room does not take audio focus.

Validation results are recorded in game/VALIDATION.md after the final build.
The original handoff's art shimmer and approximate occlusion/collision volumes
remain limitations. Emulator checks cannot establish physical Galaxy S10 frame
rate, battery cost, thermal behavior or user acceptance of the art/motion.
