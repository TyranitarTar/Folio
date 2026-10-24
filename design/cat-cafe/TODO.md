# Folio Cat Café · implemented baseline and future todos

The first Miso slice is implemented in 0.12.0. See
[play, storage and test-mode details](../../IMPLEMENTATION-v0.12.md).
The remaining multi-cat work below is future expansion.

## Miso rework — user feedback, 20 October 2026

- [ ] Rework Miso's presentation and writing. The user rejected the current
  animations and plain, unexpressive text; the implementation does not deliver
  the requested Persona-inspired visual-novel experience. Treat this as a
  substantial redesign, not minor polish of the existing poses and dialogue.
- [ ] Make a reviewable sample scene first: expressive portraits, intentional
  staging, short character animations and meaningful Danish dialogue with a
  clear scene purpose, personality, tension and payoff.
- [ ] Rework treat animations into appealing, action-specific performances with
  anticipation, timing and follow-through; avoid generic pose swaps.
- [ ] Review the sample with the user before expanding the rewrite. Preserve
  saves, earned treats, unlocked stories and the isolated test mode.
- [ ] Future work only. The current 0.13.1 session focuses on desktop GPU
  performance; do not start the café redesign during those tests.

## First playable cat: Miso

- [x] Limit the first implementation to Miso: short redemption animations and
  visual-novel portraits/scenes. Extensive 3D cinematics and illustrated set-piece
  sequences are outside the selected scope.
- [x] Develop the proposed Danish story arc **Misos store plan**, with six short
  chapters, expressions, player choices and three repeatable treat redemptions.
  See [Miso's story and first-cat scope](MISO-FIRST-CAT.md).
- [x] Include **More → Cat Café → Test mode** in the app: open any chapter, replay
  every animation, use test treats, set progression counters and reset the test
  café without manually earning unlocks. No USB/developer setup required.
- [x] Isolate test mode's save/profile from normal earned progress, currency and
  story completion; use the real scene/animation players in both modes.

## Cat interactions and treats

- [ ] Add cute interactions with individual cats, reflecting their personalities.
- [ ] Add small story tidbits and short story segments discovered through those interactions.
- [ ] Let players give a cat a few treats for a delightful one-off action: a playful
  trick, a book investigation, a little performance or another memorable surprise.
- [ ] Make treats enjoyable to spend freely. Do not reward large saved balances,
  create hoarding bonuses or require saving for an expensive interaction.
- [ ] Review the proposed treat costs for adoption so spending treats on fun does
  not delay meeting new cats. Keep story and interaction rewards lasting after
  the treats are spent; avoid pressure to ration treats or optimize payouts.

## Danish cat stories and redemption progression

- [ ] Give every cat a complete, original story arc, told in unlockable segments.
  Take inspiration from Persona 5's character relationship arcs and Love and
  Deepspace's character-focused episodic storytelling, adapted to cozy cat
  companionship. Develop original characters, dialogue and plots.
- [ ] Write all Cat Café stories and story dialogue in Danish, including the
  story-segment titles and narrative interaction text.
- [ ] Treat spending should work like Twitch channel-point redemptions: choose a
  clearly priced interaction, give the treats, and the cat performs that action.
- [ ] Play a short, action-specific animation when giving treats or redeeming an
  interaction. Provide a reduced-motion alternative and keep narration uninterrupted.
- [ ] Track progression separately for each cat: successful redemption count,
  cumulative treats given and relevant interaction milestones. Use these to unlock
  the next story segments; exact thresholds and combinations need future design.
- [ ] Count completed redemptions toward lasting story progress, not the current
  unspent treat balance. Spending should bring the next scene closer, never feel
  like losing progress. Make the next unlock's requirements clear.
- [ ] Keep unlocked segments available to read again. Offer newly unlocked scenes
  at a convenient pause rather than interrupting a book or narrated passage.

The first slice uses simple original vector artwork and motion. Future polish:
more expressive portraits, varied tricks, gentler transitions and joint UI review.
Add explicit café-database corruption recovery that preserves the damaged file
before any reset; the existing separate JSON export/restore handles normal backup
and replacement. Reading streak presentation, extra cats and décor remain future.
