# Miso · first-cat story and playable-slice proposal

18 October 2026. This proposal now has a playable 0.12.0 Android implementation.
See [implemented rules, rewards and test mode](../../IMPLEMENTATION-v0.12.md).
Read the [full Danish script](MISO-STORY-DANISH.md), also embedded in `CafeStory.kt`.
The opening sample below records
the original story direction. Other cats remain future expansion.

## Selected presentation scope

Short animated interactions and visual-novel scenes: existing room background,
expressive 2D portraits, a few poses, light movement, Danish narration and player
choices. No extensive 3D cinematics or separate illustrated set-piece sequences.
Use original Folio characters and artwork. The creative mix is cozy café life,
character relationship chapters and expressive companion scenes.

## Story recommendation: Misos store plan

Miso appoints himself the café's head cat. He inspects the chair, supervises
reading and tries to keep everything in order. Unfortunately, his interpretation
of helping includes collecting bookmarks, sitting on important pages and getting
tangled in whatever he was meant to tidy.

The comedy gradually reveals his small worry: he thinks he needs to be useful to
deserve his place. The story ends with a shared reading ritual and the realization
that his company is enough. Keep the stakes warm and small. No abandonment threat,
punishment, hunger or suggestion that missed reading days hurt him.

Miso's personality: confidently officious, easily distracted, quietly affectionate.
He communicates through expressions, movement and cat sounds. Danish narration
and the player's responses carry the dialogue. This is a proposed voice, open to
revision before final scripting; it does not require a talking-cat voice model.

## Six short chapters

All player-facing titles, story prose, choices and interaction captions are Danish.
Each chapter should have a complete small beat, a choice with an immediate reaction,
and a clear ending. Text advances at the reader's pace; no dialogue-choice timer.

| Stable scene ID | Danish title | Story beat | Small animation / choice |
|---|---|---|---|
| miso_01 | En meget vigtig kat | Miso claims the reading chair and appoints himself supervisor. | Proud pose; offer space beside you or negotiate with “chefen”. |
| miso_02 | Bogmærketyven | Missing bookmarks turn out to be Miso's attempt to keep everyone's place safe. | Paw hides a bookmark, then reluctantly returns it. |
| miso_03 | En hjælpende pote | Miso decides to welcome guests. His formal greeting becomes a very enthusiastic paw offer. | A tentative paw, then both paws; respond playfully or gently. |
| miso_04 | Den store plan | He assembles a special reading corner, convinced that a head café cat must make everything perfect. | Inspect, head tilt, proud tail; choose a cushion or help arrange the spot. |
| miso_05 | Når det går i kludder | His preparations become a harmless tangle of yarn and bookmarks. He retreats, embarrassed. | Ears lower; invite him to sit beside you or help untangle things together. |
| miso_06 | Din plads er her | You read together. The corner does not need to be perfect, and neither does he. | Miso returns a bookmark and curls up beside the book; unlock a permanent relaxed idle pose. |

Choices change a line or reaction and can be replayed. There is no hidden correct
answer, relationship penalty or need to spend more treats to repair a choice.
Start with a mostly linear arc so all six chapters form a satisfying complete story.

## Opening sample, in Danish

### En meget vigtig kat

Du har lige fundet din bog, da du opdager, at lænestolen er optaget.

Miso ligger midt på hynden. Den ene pote hviler på armlænet. Halen bevæger sig
langsomt fra side til side, som om han allerede er i gang med et vigtigt møde.

Han åbner det ene øje.

**Valg:**

- »Må jeg også være her, chef?«
- »Jeg tror, du har fundet min plads.«

**Hvis du kalder ham chef:**

Miso retter sig en lille smule op. Det var åbenbart den korrekte titel.

Han ser fra dig til bogen og tilbage igen. Derefter flytter han sin pote et par
centimeter. En meget generøs indrømmelse.

**Hvis du nævner din plads:**

Miso ser ned på hynden. Så ser han op på dig.

Han lægger hovedet på skrå. Det ligner ikke en undskyldning. Mere en invitation
til at genoverveje din opfattelse af situationen.

**Fælles fortsættelse:**

Du sætter dig forsigtigt. Miso rykker lidt til siden, lige nok til at I begge kan
være der.

Da du åbner bogen, lægger han en pote på den nederste del af siden.

»Så du holder øje med, hvor langt jeg er kommet?«

Et kort »mrrp« er hele svaret.

Du læser den første linje. Miso lukker øjnene igen, men poten bliver liggende.

Det lader til, at caféen har fået en læsechef.

**Scenens slutning:** Miso blinker langsomt og lægger hovedet til rette.

## Three repeatable treat redemptions for the first slice

| Danish action | Animated response | Personality / progression |
|---|---|---|
| Giv en godbid | Looks at treat, leans in, eats, gives a contented tail flick. | Ordinary affection; cheap and always worthwhile. |
| Giv pote | Proudly offers one paw; an occasional authored variation offers both. | His overly serious greeting from chapter three. |
| Hent bogmærket | Pounces, briefly hides the bookmark, then delivers it. | Playful callback to his attempted “help”. |

Keep the actions short, roughly a few seconds, with clear costs before redemption.
Exact prices and unlock thresholds remain tuning decisions. Successful treat
giving and redemptions contribute to Miso's cumulative story progression. Count
redemptions and treats given; never require an unspent balance or reward hoarding.
No expensive must-buy interaction and no requirement to repeat one specific action.

Story unlocks remain available. The unlock notification is quiet; the player
chooses when to read a scene. Replay does not debit treats or grant the same reward
again. Save the redemption and progression independently of animation completion:
an interrupted animation must neither lose the purchase nor charge it twice.

## In-app test mode: required with the first implementation

Entry: **More → Cat Café → Test mode**. An ordinary user-facing option; no USB,
ADB, developer settings or manual progression grind is required.

Use a clearly labelled **TEST CAFÉ** with its own save/profile. Normal reading
rewards, currency, cat ownership and story completion remain separate. Leaving
test mode returns to normal state. Never make a test flag silently unlock normal
progress.

Required controls:

- Open any of Miso's six chapters immediately, regardless of unlock state.
- Replay each of the three redemption animations directly.
- Give unlimited test treats or add a chosen small amount to test affordability.
- Set Miso's redemption count and cumulative treats given, then evaluate story
  unlocks using the same rules as normal mode.
- Preview locked, newly unlocked and already-read chapter states.
- Reset only the test café, including purchases, counters and scene choices.
- Pause, skip and replay scenes; test reduced motion and interrupted animations.

The selector should exercise the real scene and animation player, not duplicate
mock screens. “Open any chapter” is a test bypass; “Evaluate unlocks” must still
run the real progression rules. Clearly distinguish those controls.

## First-cat implementation boundaries

Deliver one room, one cat, the three redemptions, the six short Danish chapters,
expressive portraits, resumable/replayable scenes and the isolated test mode.
Use existing reading/listening progress carefully; the durable café reward ledger
is still a prerequisite for earned production currency.

No new TTS model is needed. Default scenes to text and quiet animation. If story
audio is added later, start it explicitly and coordinate audio focus with book
narration. Do not interrupt an audiobook passage to announce a cat scene.

Before expanding to another cat, verify: a redemption charges once; interruption
does not lose its lasting progress; chapter replay is free; test mode never writes
normal state; all story text/choices are Danish; and entering/leaving Café keeps
the reader's source position intact.
