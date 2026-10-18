# Folio 0.12 · Miso's first playable café

This release implements one cat and one original room. It does not add a new
voice model. Existing optional voice packs, source bookmarks and speech output
remain in the reader.

## Entry and play

Open **More → Cat Café**. The optional café starts with twelve welcome treats.
Once enabled it also appears as a Café navigation tab. Disabling it retains the
wallet and story; enabling it again does not award another welcome balance.

Miso has three redemptions: **Giv en godbid** (1), **Giv pote** (2), and
**Hent bogmærket** (3). A short, silent vector animation follows the purchase.
Pause, resume, skip and free replay are available. Reduced motion shows a still
pose. No additional animation runtime, video files or network artwork is needed.

**Misos store plan** contains six complete Danish chapters. Every chapter has two
choices, immediate reactions and a shared ending. Choices have no penalty.
Scene/page/choice are saved as the reader advances. Closing a scene or restarting
Folio retains its place; use **Fortsæt kapitlet**. Completed stories can be read
again free. Completing the sixth chapter gives Miso his relaxed resting pose.

| Chapter | Completed interactions | Treats given in total |
|---|---:|---:|
| En meget vigtig kat | 0 | 0 |
| Bogmærketyven | 2 | 2 |
| En hjælpende pote | 5 | 5 |
| Den store plan | 9 | 10 |
| Når det går i kludder | 14 | 17 |
| Din plads er her | 20 | 25 |

All three actions contribute. Unlocks depend on spending and participation,
not a saved balance, one required trick or a daily streak. Unlocks are permanent.
A purchase's currency and counters commit together, before its animation starts.
Interrupting an animation cannot reverse the lasting progress or charge again.

## Reading rewards

One treat per cumulative active wall-clock minute, with partial minutes retained.
There are no speed multipliers or chapter-length estimates. Simultaneous reading,
guided reading and listening count as one interval.

- Manual reading: reader resumed, window focused, book navigator ready, menus and
  controls hidden. Refresh attention by touching the reader at least every two
  minutes. This is a conservative activity estimate, not proof of comprehension.
- Guided reading: the foreground reader can remain eligible while its guide runs.
- Local narration: advancing presented-PCM callbacks keep the listening signal
  alive. Generation, chapter preparation, downloaded samples and voice previews
  supply no reward signal. Buffering without audio advancement expires it.
- Android TTS: accepted start/range callbacks supply the signal. Done, pause,
  errors and service destruction clear it. A 30-second timeout bounds a missing
  completion callback. System-engine callbacks are an estimate of playback.
- Monotonic time, a maximum two-second tick gap and a new process session prevent
  offline/sleep/clock-change catch-up. Adjacent eligible samples are required.
  Short transitions are conservatively omitted; this is not an exact stopwatch.

Time commits in batches of up to fifteen seconds. Session sequence numbers make
batch retries idempotent. Abrupt process termination may lose the last uncommitted
fraction (under fifteen seconds); it cannot award that interval twice. No hunger,
missed-day penalties or currency decay exists.

## Separate test café

**Café settings → Testtilstand** is available without developer setup. The welcome
screen also has a direct **Prøv alt i testcaféen** entry. This profile is separate
from real treats, reading rewards and story completion.

- Open all six chapters regardless of locks, using the real story player.
- Preview the three real animations without a purchase.
- Exercise normal progression with Locked / Halfway / All open presets, custom
  interaction/spending/balance counters, and an All read state.
- Preview reduced motion, pause, skip, replay and interrupted scenes.
- Reset only the test profile after an explicit confirmation.

Test mode is not a production unlock flag. Leaving the café returns to normal
mode; test story positions remain stored in their own profile.

## Storage and backup

`cat-cafe.db` is a separate, versioned SQLite database. Transactions cover wallet,
counters and unique purchase receipts. Reader/library databases are not changed
by café actions. Normal and test profiles have separate receipt namespaces.

Use **Café settings → Eksportér café** for a small JSON backup. Test data is
excluded. **Gendan café** explicitly replaces the normal profile rather than
adding balances together. The bounded document is fully validated before any
mutation; the test profile and books are retained. Reading batches are coordinated
with restoration. Book ZIP backups remain independent and explain this in Storage.

## Presentation and next work

The new cat-and-book icon includes adaptive, round and monochrome resources.
Artwork and story are original. Café scenes are silent and never request audio
focus or alter an EPUB/guided/listening bookmark.

This is the first playable slice, using simple vector poses and authored motion.
Additional cats, varied tricks, décor, longer story animation and gentle streak
presentation remain future work. Tune prices, story pacing, art and portrait motion
after actual play. Joint reader UI cleanup and audiobooks are still separate todos.

Future voice research: smaller Danish downloads than Plapre Pico, and larger
English models that may improve on Kokoro. Compare quality, GB download/install
size, memory and sustained phone speed before choosing or implementing anything.
