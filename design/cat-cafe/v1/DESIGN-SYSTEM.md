# Folio visual template · Cat & book

Draft 1 · 18 October 2026. Companion: [game design](CAT-CAFE-GDD.md),
[tokens](tokens.json), [catalog](catalog.json), [interactive template](index.html).

## Direction

An adult, quiet reading app with an optional cozy game. Warm paper, dark forest
green, ivory pages, a ginger cat and occasional terracotta. Editorial serif titles
sit beside familiar Android sans-serif controls. Illustrations belong in Café,
book covers and the app mark; actual reading pages stay calm and configurable.

The new mark combines an open book, two cat ears, closed eyes and a curled tail.
The master is editable SVG. Android assets are separate proposed resources, not
an unreviewed replacement in the released app. Adaptive foreground geometry is
scaled around the center for circular, squircle and rounded-square masks. A simple
single-color silhouette supplies themed icons. Do not add the word Folio inside
the launcher icon; the launcher already supplies the app label.

## Color and type

All values live in `tokens.json`. The light/dark sets are paired intentionally;
artwork is not a substitute for readable controls. Forest primary fills use white
text in light mode; pale-green primary fills use dark text in dark mode. Decorative
ginger/gold are not small-text colors. Muted text still needs normal contrast.

| Role | Light | Dark |
|---|---|---|
| App paper | #F8F5EC | #18251F |
| Surface | #FFFFFF | #22342B |
| Main text | #243B32 | #EBEFE3 |
| Secondary text | #5F6E63 | #BBC8B8 |
| Primary action | #244C41 | #B8D6BA |
| Primary text | #FFFFFF | #173327 |
| Sage surface | #E5ECDF | #304B3A |
| Focus range | #D5E6C8 | #496344 |

The HTML reference uses Georgia and system sans-serif so it works offline. These
are stack choices, not newly bundled font licenses. Keep the EPUB reader's font
chooser and user-selected font. Proposed UI sizes: 32sp display, 28sp screen title,
20sp section title, 16sp body, 14sp labels, 12sp captions. Support system scaling;
do not cap accessibility font size to preserve a mockup layout.

Use a 4dp spacing base: 4/8/12/16/24/32/48. Screen padding 20–24dp; controls at least
48dp. Small controls radius 12dp, cards 20dp, sheets 28dp. Borders express grouping,
not a decorative box around every row. Reserve strong primary fill for the main
action. Status always has text or an icon as well as color.

## App structure and complete screen set

Keep Library / History / Browse / More; add the middle Café destination only when
the game is enabled. This preserves the existing reader architecture and avoids
turning the Library into a game dashboard. More contains Café settings and always
allows re-enabling the game. Audiobooks stays a Coming later Library section.

| Surface | Primary task | Necessary states |
|---|---|---|
| Library | Resume a book; import/manage EPUBs | Empty, loading, populated, search/filter, unavailable file |
| Book detail | Metadata, chapter list, progress, read/listen | New, in progress, finished, missing book |
| Reader | Read with compact accessible controls | Paused, preparing engine, buffering, playing, stopped, error |
| Reading statistics | Page/chapter position and word distribution | Pagination pending, estimated, current layout cached |
| History | Return to a recent reading session | Empty, grouped dates, replay/open book |
| Café | See progress; meet cats; decorate | Disabled, first visit, earned receipt, visitor, idle earnings |
| Cats | View permanent companions and unlocks | Locked, eligible visitor, affordable, unaffordable, owned |
| Decorate | Preview, buy and place one item | Shop, owned, preview, slot replacement, insufficient yarn |
| Browse | Find/import books | Source selection, searching, results, downloading, failure |
| Voice packs | Compare and manage offline voices | Not downloaded, preview, queued, progress, paused, checking, ready, storage cleanup |
| More | Preferences, backup, updates, version history | Ordinary settings, migration/recovery, update discovery/download/install |

The prototype supplies navigable examples, not backend implementations of import,
network search, speech synthesis, model downloads or the reward ledger. Their
simulated actions are labelled. Existing shipping features must be retained when
porting the theme; decorative mocks must not replace real error/recovery behavior.

## Compact reading controls: accepted direction

Respect the selected Compact player. Bottom controls expose previous unit,
pause/resume, next unit and **Stop**. A secondary row offers speed and navigation
unit. Sentence and paragraph movement are direct options. Settings sheet contains
voice selection, playback speed, prepare ahead, page start and read-from-here.

Starting/resuming speech shows Loading engine / Preparing speech / Buffering
audio, with Cancel. Once playable, close the sheet back to the reading page. If
loading fails, retain a readable error and retry control. Always follow and
highlight spoken passages; do not reintroduce switches for those behaviors.
Changing speed must preserve source audio and listening position.

Guided defaults in this proposal are the user's four-word moving window at
350 WPM, with paragraph navigation. Preserve choices per mode. Presets include
150/200/225/250/275/**300**/350/400/450/500. Offer word groups, rendered line and
custom window sizes. A moving range has one uniform background **including
spaces**, without a separately darker current word. Paint range rectangles across
wrapped lines rather than shading each word with a gap.

Tap Page x of y for word count, chapter counts and chapter distribution. Page
numbers remain layout dependent; changing fonts/orientation invalidates only
affected pagination data. Show calculating/cached/estimated states accurately.
The template's example page count is sample data, not an implemented paginator.

## Café components

- A legible room scene with a normal semantic cat list; painted objects are optional
  shortcuts, never the only controls.
- One daily progress row and a seven-day calendar, not a wall of competing metrics.
- Compact treats/yarn balances with labelled details. No purchase of currency.
- Cat cards with owned/visitor/locked text, lifetime requirement and price.
- Decor preview with explicit cost and placement slot, then confirm/cancel.
- Calm return receipts; no modal confetti or sound while a book is open.

## Light/dark, mobile and larger screens

Phone baseline 360–430dp. At 320dp, wrap balances and compact control labels;
never shrink touch targets below 48dp. Let sheets scroll and keep their dismiss
action reachable. Use navigation insets rather than painted Android system bars.

At larger widths, use list/detail layouts for Library and catalog screens. Keep
reading line length controlled by the reader's margin/column settings. Café can
show room and collection side by side. A tablet/foldable view must preserve book
locator, spoken sample and café transaction state across resize. Galaxy Flip7 is
the priority phone, not a justification for a permanent desktop-style side panel.

Reduced motion disables room animation and optional highlight transitions. Dark
mode dims art using authored art variants in production, not a blanket inversion.
The current room illustration is a warm daytime composition draft in both themes.

## Interaction, copy and accessibility

Use Android Back to close a sheet before leaving a destination. Returning from
Café should retain the same book and spoken cursor. Audio continues through Café
navigation unless a voice preview explicitly pauses it. Café never steals focus.
Do not start a sound or animation when switching template screens.

Focus states remain visible. Use TalkBack names, real buttons and labelled form
fields. Reflect checked/selected/disabled states semantically. Body-text contrast
target 4.5:1; essential larger text 3:1. Validate actual composited states in both
themes, including disabled buttons, progress labels and selected chips.

Copy is short and candid: “Preparing speech…”, “3 minutes read today”, “Biscuit is
visiting”, “12 more treats needed”, “Audiobooks · Coming later”. Do not describe
audio as playing during buffering or say a model works offline before installation.

## Handoff and acceptance

1. Review the look, icon small-size recognition, five-tab option and reward rules.
2. Translate tokens/components into existing Compose theme and screens in a
   separate implementation change. Retain existing data/recovery and media behavior.
3. Add adaptive launcher assets through the Android resource pipeline, including
   API/version fallbacks, before replacing the current icon reference.
4. Build the café ledger before offering real currency, then one-room content.
5. Check 320/360/430dp, large font, dark theme, keyboard/TalkBack, reduced motion,
   landscape/fold resize and full in-app update/data retention.

Prototype tests verify local interactions and layout. They do not prove Android
accessibility, on-device performance or a functioning reward-accounting backend.
