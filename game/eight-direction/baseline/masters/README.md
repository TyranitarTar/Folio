# Miso - canonical master v1

This freezes the **neutral standing idle from the current room demo**. It is a starting baseline, not a new drawing. Both PNGs contain identical artwork: a transparent 64x64 native cell and its exact nearest-neighbor 8x enlargement. The opaque silhouette occupies 48x50 pixels, including the tail; the ground anchor is [31,55]. The 16-color palette and bitmap checksum are recorded in `character-spec.json`.

## Always reference the master

For every new Miso pose generation, use the built-in image-gen tool with both master PNGs supplied explicitly as `referenced_image_paths`. Read `MASTER_PROMPT.txt` and append the action requested. Never use the latest walking/grooming output as the reference for the next generation. Changing a pose should not change the design.

The image generator is not a rig: supplying a master improves consistency but cannot lock pixels or guarantee proportions. Generated frames remain drafts. Visually compare head, torso, paws, tail, markings, pixel density and camera against the master. Reject a redesigned pose rather than stretching or pasting it back into shape.

For a new facing, derive a neutral turnaround from this master first. Once that neutral view has been reviewed, save it as a frozen directional master and derive that direction's animations from it, retaining the original front master as an identity reference. Animation outputs never become masters automatically.

## Native conversion

Keep the same grid and a single documented sampling scale. Do not crop each character to its silhouette and independently resize it to fill a cell: that makes sitting and shorter poses chunkier. Cell size alone does not establish consistency. Register by translation, compare at 1x and 6x/8x, review all four legs, and inspect an isolated animation loop before room integration.

`pixel-cat-experiment/AGENTS.md` records this policy for future work in the project. A new master version requires an explicit character-design change from the user; preserve this version when creating a replacement.
