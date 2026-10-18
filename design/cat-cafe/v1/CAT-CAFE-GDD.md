# Folio Cat Café

Design draft 1 · 18 October 2026 · proposed feature, not part of the released 0.11 APK.

## Product decision

Build a small, optional book café that grows through reading and listening. The
reader remains the main product. The café provides a pleasant place to return
after a session, rather than an activity that competes with the book.

The first release should have one illustrated room, six permanent cat companions,
twelve decorations, a daily goal and a gentle weekly reading calendar. Use preset
furniture positions. A freeform building game, accounts, trading, multiplayer,
advertising and paid currencies would make the first version unnecessarily large.

**Promise:** “A little reading. A little company.” A five-minute session should
feel worthwhile. Someone returning after a month should be welcomed by the same
cats and possessions, without bills, deterioration or a backlog of chores.

## Audience and experience

- Existing Folio readers who enjoy collecting cats and decorating a cozy space.
- People building a reading habit, including people who primarily listen.
- Readers using older phones or computationally demanding offline voices.
- People who want the reader without a game: Café can be disabled completely.

The desired visit lasts roughly 30–60 seconds: see what was earned, meet a cat,
place something, return to a book. Longer decoration sessions remain possible
without increasing reading rewards. This duration is a design target to validate,
not a measured behavior.

## Design pillars

1. **Reading creates the progress.** Pages, WPM, playback speed and rapid taps do
   not multiply rewards. Reading, guided reading and presented narration qualify.
2. **Cats are company.** They never starve, get ill or leave because of inactivity.
3. **Progress is durable.** Every reward and purchase is a local transaction.
4. **The book stays quiet.** No popups, reward sounds or game animations interrupt
   a passage. No game code changes the reading or listening locator.
5. **Small and offline.** No account, network or background simulation is required.

## The complete first loop

1. Enable Café from the Library card or More. Choose a personal 5/10/20/30-minute
   daily goal; default 10. Explain that both reading and listening count.
2. Read or listen normally. A session accumulates eligible time quietly.
3. On a natural return to Library/Café, show a small session receipt. Never force
   the reader to exit the book to claim it. Rewards are already saved.
4. The first five eligible minutes permanently welcome Miso and grant 12 starter
   yarn. A linen cushion costs 12, so the first visit supports a real choice.
5. Reading earns treats; lifetime reading unlocks visiting cats. Inviting an
   eligible cat spends treats once and permanently adds it to the album.
6. The furnished café earns a little yarn while away. Settle it on the next café
   visit, with an eight-hour cap. Yarn buys decorations for fixed room slots.
7. A goal celebration and weekly calendar acknowledge consistency. Nothing is
   taken away when a day is missed.

Visitors are previews, not expiring offers. Biscuit can sit on the windowsill
before adoption, with a clear “Visitor” label. An unavailable invitation explains
both the lifetime reading requirement and the treat cost. No random paid boxes.

## Economy v1: explicit starting values

These values are a tunable proposal, not a shipped or playtested balance. The
machine-readable source is [catalog.json](catalog.json); theme/economy constants
are also recorded in [tokens.json](tokens.json).

| System | Initial rule | Purpose |
|---|---|---|
| Treats | 1 per complete eligible minute | Reading funds adoption |
| Remainder | Keep unused milliseconds across sessions | Short sessions still contribute |
| Daily bonus | +5 treats at 10 eligible minutes, once per local date | Small reward independent of chosen goal |
| First companion | Miso after 5 lifetime eligible minutes, free | Meaningful first session |
| Starter yarn | +12 once with Miso | First decoration immediately affordable |
| Idle yarn | 6/hour initially, then 8/hour and 10/hour | Modest cosmetic progression |
| Idle cap | At most 8 hours per settlement | No continuous process or week-long exponential payout |
| Comfort level 2 | Own 4 unique decorations | Small upgrade, no idle compounding |
| Comfort level 3 | Own 8 unique decorations | Stops at a predictable rate |
| Goal choices | 5, 10, 20 or 30 minutes | Personal preference, not reward difficulty |
| Reading day | At least 5 eligible minutes | Stable calendar independent of goal changes |

Read beyond a personal goal and time still counts normally. There is no daily
reading reward cap, speed multiplier, penalty or paywall. Changing the personal
goal changes the ring and goal message; it does not mint a new bonus, alter prior
calendar days or change cat unlock thresholds. Record the goal used for each day.

Treat rewards use `newTotalWholeMinutes - previouslyAwardedWholeMinutes` from the
durable cumulative ledger, not separate rounding per fragment. Currencies are
nonnegative 64-bit integers. Fixed-point remainders represent fractional yarn.

### Cat roster and pacing

| Cat | Appearance / character | Lifetime minutes | Treat cost |
|---|---|---:|---:|
| Miso | Ginger tabby; owns the warm reading chair | 5 | 0 |
| Ink | Black; stays near the bookshelves | 20 | 10 |
| Biscuit | Cream; investigates every new chapter | 60 | 30 |
| Luna | Silver; late-night company | 120 | 60 |
| Juniper | Calico; sleeps beside the biggest plant | 240 | 100 |
| Atlas | Brown tabby; curious about every book | 480 | 160 |

At twenty eligible minutes per day, the final lifetime threshold is reached after
24 reading days. Treat spending can delay an adoption; it never removes its
eligibility. These are deterministic illustrations of the proposed balance,
not retention predictions. Six cats can be owned; only a few need appear in the
room together. An album shows every owned companion and its favorite room spot.

Affinity comes later: unique reading days can unlock a small story or collar.
Avoid affection meters that decay and avoid requiring separate petting chores.

### Decorating and idle progress

Twelve decorations cost 12–96 yarn. Categories include chair textiles, tableware,
plants, lamps, rugs, shelves, wall art and window bedding. Ownership is permanent.
Purchases are unique; placing or replacing an owned item is free. Replaced items
return to inventory. Comfort depends on unique items owned, not equipped items,
so moving furniture cannot change the economy or require a particular aesthetic.

One slot can contain one selected item. A knitted blanket and a linen cushion
share the chair slot in v1; their previews explain that only one will be placed.
Starter furniture is free, does not count toward comfort and cannot be sold.

Idle yarn begins with Miso. Before a comfort-changing purchase, settle time at
the old rate, then apply the new rate. An eight-hour absence at level 1 yields
48 yarn; a 48-hour absence also yields 48. There is no interest, cat-count
multiplier or offline loop. The return message says “Your café made 48 yarn while
you were away,” with the cap explained in details. Yarn may also settle during a
long open café session, but no foreground timer is necessary: calculate on actions.

## Goals, streaks and compassionate return

Use a seven-day reading calendar as the primary habit display. Mark days with at
least five minutes. Show the chosen daily goal separately. Offer “Read 5 days this
week” as a weekly target; weeks with fewer days keep their history and rewards.

Consecutive days can be a secondary, opt-in statistic. Display lifetime reading
days and longest run permanently. Initial cosmetic milestones can be cumulative
3/7/14/30 qualifying days, so a missed date cannot erase progress. There is no
paid freeze, midnight countdown, punitive red warning or “your cat misses you”
notification. Earned rest-day tokens and more complicated streak rules are
deferred until ordinary calendar accounting is proven and the user wants them.

Return copy: “Welcome back. Miso saved your chair.” Goal copy: “A little time
well spent.” A missed day uses a neutral empty calendar cell. Notifications are
off by default. If added later, use a user-chosen, quiet reading reminder; never a
deadline to collect idle rewards.

## What qualifies as reading

The app cannot prove attention or comprehension. Explain this honestly. This is
a local motivational feature, not a competitive economy, so avoid invasive
tracking, eye detection, camera permissions or aggressive anti-cheat checks.

| Mode | Count | Exclude |
|---|---|---|
| Manual reading | Foreground, visible book content during an active reading interval | Library/settings time, screen locked/off, background time, inactivity beyond 10 minutes |
| Guided reading | Actual advancing reading intervals, bounded by foreground eligibility | Paused/finished state, navigation taps without reading |
| Narration | Real wall time during which book audio is presented, including background listening | Engine loading, buffering, paused time, voice samples/previews, silent preparation, silence after playback ends |

A page tap, scroll, text navigation or deliberate resume renews manual activity.
The inactivity window is generous to accommodate slow readers; it does not require
regular tapping while narration is audibly playing. If a manual interval times
out, rewards pause quietly; the reader stays in place and the existing ten-minute
screen-awake policy remains independent. No comprehension claim is made.

Narration rewards elapsed listening time, not source-audio duration: 20 minutes
of source speech played at 2x earns approximately 10 eligible minutes, excluding
gaps. Seeking/replaying genuine audio is allowed; repeatedly seeking without
audible presentation earns nothing. Muted playback and exact audibility are
imperfect signals on Android; do not claim to prove that the user heard it.

If reading and narration overlap, take the **union** of eligible wall-clock
intervals. Never sum them. Priority for display can be narration, then guided,
then manual, but one second earns at most one second. Folding, rotation and
switching surfaces cannot duplicate a session. Reader position and café rewards
are separate stores; game errors must not interrupt speech.

## Durable ledger and transactions

Implement a local ledger before the game UI starts awarding anything. Use SQLite
transactions in a separate, versioned café schema; link optional book IDs only
for statistics. Deleting a book must not revoke cats or lifetime rewards.

Proposed entities:

- `activity_slice(id, session_id, boot_id, monotonic_start, monotonic_end,
  local_day, timezone_id, mode, eligible_ms, book_id_optional)`.
- `day_progress(local_day, eligible_ms, goal_minutes, daily_bonus_awarded)`.
- `reward_event(id, source_key UNIQUE, kind, amount, created_at)` and a reconciled
  wallet. Source keys include complete minute ordinal, local-day bonus and welcome
  reward, so retries cannot duplicate currency.
- `owned_cat(cat_id PRIMARY KEY, acquired_event_id)`.
- `owned_item(item_id PRIMARY KEY, acquired_event_id)` and `room_slot(slot_id,
  item_id)`.
- `cafe_state(schema_version, enabled, lifetime_ms, remainder_ms,
  yarn_remainder, last_idle_wall_time, last_idle_monotonic, last_boot_id,
  comfort_level)`.

Session checkpoints persist periodically and on pause, seek, stop, route changes,
surface changes and process/background transitions. Start with a five-second
checkpoint interval as a candidate to profile; batch writes and avoid blocking
the audio thread. Commit slices and their rewards atomically. After a crash,
credit only acknowledged eligible time; do not infer the unobserved gap.

Purchase transaction: settle idle accrual, check unlock/currency/ownership again,
debit, insert unique ownership, and update the selected slot together. Rapid taps,
double callbacks and app restarts must produce one purchase, not a negative wallet.
Use a no-op or an “Already owned” result on duplicate purchase IDs.

### Time, timezone and offline rules

Use monotonic time within a boot for interval duration. Split an interval at local
midnight for daily records; do not credit either side twice. Persist timezone and
local date with each credited slice. A timezone change affects future intervals
only; never recalculate old daily bonuses or move completed rewards between days.

Idle accrual uses monotonic time when the boot ID matches. Across reboots, use a
nonnegative wall-clock delta capped at eight hours. A backwards clock gives zero
new idle time until a new baseline is recorded; never subtract currency. A large
forward jump can give at most the cap once, not repeated claims from the same
settlement. Do not advertise a tamper-proof offline economy: clock manipulation
can still affect calendar display and should not trigger punitive data loss.

### Backup and restore

MVP restore **replaces** café state with a selected snapshot after confirmation;
do not merge wallets. Include schema version, catalog version, ownership, room,
days, ledger identifiers, remainders and preferences in Folio backup. Restore
resets the idle baseline to now to prevent retroactive idle claims. Unknown future
cat/item IDs are retained in storage but rendered as unavailable placeholders.
Keep the prior café snapshot available for recovery. The book library and speech
positions retain the app's existing recovery behavior.

Merging two devices, cloud sync, importable reward codes and spending across
devices require a different conflict model and are explicitly outside v1.

## Screen design and flows

**Library:** subtle Café progress card after Continue reading, dismissible. Default
Library still centers on books. Audiobooks remains labelled Coming later.

**Navigation:** keep Library/History/Browse/More. When Café is enabled, add Café as
the middle fifth destination. Disable it to restore the four existing destinations.
More always contains a way to re-enable it. No mandatory game onboarding on upgrade.

**Café:** room illustration first; compact treats/yarn balances; today's minutes
and goal; a seven-day calendar; Meet cats / Decorate actions. The primary action
returns to the last book. A visitor preview and earned receipt can appear below,
not over the room or current text. No music by default.

**Cats:** all six with owned/visiting/locked states. Detail shows personality,
lifetime requirement, treat price and a single invite action. An owned card offers
favorite-spot selection when that feature is available, not another currency sink.

**Decorate:** category or slot list, owned/shop tabs, affordable/unaffordable states,
static preview before confirming a purchase. Preview is reversible. Purchases show
currency and placement effects together. No arbitrary drag placement in v1.

**Reader:** the selected compact player with explicit Stop, sentence/paragraph
navigation and always-active spoken following. Four-word guided focus uses one
solid contiguous highlight, including spaces. A small optional session receipt
appears only after returning from reading. No floating cat over book text.

**Settings:** enable Café, daily goal, reduced motion, optional session summaries,
hide currency/goal indicators while reading, backup/recovery and a clear explanation
of eligible minutes. Language follows Folio; cat names can remain stable identifiers.

Empty, first-session, eligible visitor, unaffordable, already owned, disabled,
offline, clock-change, restore and ledger-error states are required, not polish
reserved for later. On ledger error show “Reading progress is safe. Café rewards
could not be saved yet.” Only say reading progress is safe after its save succeeds.

## Art, animation, accessibility and performance

Use original flat, lightly textured book-café art: forest green, cream pages,
ginger cats and restrained terracotta. The app mark is a cat curled around an open
book. The supplied SVG room is a composition draft, not a final animation sprite
sheet. Each cat needs idle, blink and sleep poses initially; walking, feeding,
dozens of directional sprites and an independent pet runtime are unnecessary.

Use native Compose/Canvas/vector or a small sprite atlas. Draw only visible cats;
keep a static fallback. Animations run only while Café is visible and stop when
hidden, backgrounded or when reduced motion is enabled. Start with brief occasional
blinks and a very slow breathing pose; no particle showers or constant 60fps room.
No polling service, periodic worker or wake lock for idle earnings.

Target budgets to measure before shipment: under 5 MB compressed for initial café
art; no extra model or game engine; no café animation work during narration in the
reader. These are acceptance budgets, not current APK measurements. Benchmark on
Flip7 first and perform a regression on an older S10 when a device is voluntarily
available. Phone debugging is not a product requirement.

Provide 48dp touch targets, text scaling, contrast-aware light/dark themes, TalkBack
labels, non-color state labels and reduced motion. A screen reader gets a concise
room description plus a normal list of cats and furniture; tapping tiny painted
objects cannot be the only way to navigate. Sounds are optional and must never
take narration's audio focus. Set visual focus without autoplay audio.

## Implementation sequence and exit criteria

| Milestone | Deliver | Must prove before continuing |
|---|---|---|
| A: Design review | This template, icon, rules and catalog | User likes the theme, optional game and reward rules |
| B: Quiet ledger | Eligibility slices, daily records, reward transactions; debug diagnostics | No double credit across modes/restarts; no speech or locator regression |
| C: First room | Opt-in, Miso, starter yarn, one purchase, backup/restore | First session works end to end; replay/duplicate purchase safe |
| D: Full small game | Six cats, twelve items, idle settlement, calendar | Economy pacing, clock changes and poor-device performance acceptable |
| E: Polish | Original animation, sound options, localization, optional milestones | Accessibility, offline and long-session narration acceptance |

Do not assign fixed app versions until the design review. A new APK/game rollout
is separate from this design delivery. Ship the ledger and one-room loop before
adding new model runtimes, large rooms or social features in the same change.

### Required meaningful checks

- 30 seconds manual + 30 seconds guided earns one minute, including after restart.
- 60 seconds of overlapping reading and audio earns 60 seconds, not 120.
- Two minutes of 2x source speech presented in one real minute earns one minute.
- Loading, preparation, sample previews, pauses and buffer gaps earn zero.
- A genuine background listening interval counts with the screen off.
- Stop/resume, seeking, Bluetooth reroute and repeated callbacks do not replay rewards.
- First welcome, complete minute and daily bonus replay are idempotent.
- Boundary at midnight and timezone/clock changes do not duplicate daily events.
- A 48-hour absence pays only eight hours; opening twice does not pay twice.
- Comfort changes settle the old rate before starting the new one.
- Repeated purchase taps cannot debit twice; replacing decorations is free.
- Restore replaces, resets idle baseline and preserves unknown catalog IDs.
- Cat Café disabled leaves the four-tab reader and narration behavior intact.
- Voice generation, reading position and playback remain unaffected by a café DB failure.

## Decisions for the next review

Recommended defaults are already represented in the prototype: optional fifth
tab, six named cats, two currencies, lifetime unlocks, fixed room slots and a
weekly calendar. Review the warmth/age of the art, whether the idle layer is fun
enough to justify the second currency, and whether all six cat names feel right.
If two currencies feel busy, cut idle yarn entirely and fund decorations through
reading treats; do not add more systems to explain the existing ones.

The prototype is deliberately a sandbox with labelled simulated rewards. It is
not an implementation of this durable ledger and cannot validate retention,
attention, economy pacing or phone performance.
