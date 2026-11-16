# Eight movement facings · 0.18.1

The built-in image_gen tool generated a small 2×2 proof, then six complete run
drawings each for front, west/profile and rear. East mirrors west; the four
existing isometric facings retain their original approved drawings/timing.
The result displays eight directions while motion itself can follow any clear
straight segment, with collision-safe detours around furniture.

Exact input-role/pose/grid prompts are proof-prompt.txt and run-*-prompt.txt.
Targets, untouched generated originals, native exports, review contact sheets
and normal-speed GIFs stay here. The canonical identity inputs always include
both original SW masters; each full cycle additionally includes both reviewed
native/enlarged neutral references for its own facing plus the fixed target.
baseline/ preserves the original atlas/metadata and immutable master references.

build_sprites.py exports full equal cells to 64×64, removes the generated faint
alpha halo before sampling, maps to the original 16-color palette and applies
one documented whole-sheet vertical registration per facing. There is no
silhouette fitting, limb reconstruction or change to the existing 289 frames /
34 clips. The new atlas has 310 frames and 40 clips; run_s/n/w are six drawings
each and idle_s/n/w use the previously reviewed neutral references.

Agent visual review: the proof and all 18 native drawings retain recognizable
adult Miso proportions, complete cats, four anatomical legs with natural overlap,
cream features and tabby markings. Alternating reaches, compression and suspended
phases are readable. Fine coat/outline shimmer remains; these new loops have
agent review, not physical-device or explicit user art acceptance. Do not promote
a generated motion frame to a new identity master. Preserve run_sw, sprint_sw and
sprint_nw unless the user explicitly requests changing them.

Runtime data is under app/src/main/assets/cafe/. Re-running build_sprites.py
appends from the preserved baseline deterministically, retaining every previous
frame/clip. New facings use speed-adjusted playback; original isometric gaits and
all jumps/interactions keep their existing source-phase contracts.
