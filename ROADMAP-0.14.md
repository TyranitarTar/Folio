# Folio 0.14 — reader and interface overhaul

Implemented for 0.14 on 23–24 October 2026. The checkboxes below record
implementation; acceptance evidence and remaining device/SAF edge-case checks
are listed in [IMPLEMENTATION-v0.14.md](IMPLEMENTATION-v0.14.md).
Studio 0.3 queue controls, real GPU evaluation and packaging checks completed
before final Android acceptance. Existing speech weights/runtimes are retained.

**25 October review:** checked implementation is not completed user acceptance.
The user still reports slow page calculation and requests easier library exit,
matching reader/audio headers, the visible section name **Reading**, and removal
of the redundant global **Read + listen** row. Voice-panel density and physical
phone/foldable acceptance also remain open. The item-by-item audit and explicit
follow-up checklist are in [REVIEW-0.14-FOLLOWUP.md](REVIEW-0.14-FOLLOWUP.md).

## Reading position, selection and saved passages

- [x] Show both **Book: page x of y** and **Chapter: page x of y**. Both counts
  must reflect the current typography and pagination without reintroducing slow
  whole-book recalculation. Cache/invalidate by layout and chapter as appropriate.
- [x] Restore **Copy** in the long-press text-selection actions. Verify normal
  Android selection, multiline passages and selection across a page boundary.
- [x] Add **Look up in Danish dictionary** to selection actions. A Danish
  dictionary exists: [Den Danske Ordbog / Ordnet](https://ordnet.dk/ddo/), verified
  20 October. Begin by evaluating an encoded selected-word link and a clear
  external/online lookup action. Check current URL behavior before implementation.
  Investigate a licensable offline dictionary separately; website access is not
  permission to bundle its entries. Do not promise an unverified offline corpus/API.
- [x] A highlight must save the **actual selected passage plus its stable book
  locator**, remain available after restart, and be reachable from saved
  passages/highlights. Verify edition/layout changes do not silently move it.

## Guided reading

- [x] Place **Resume** and **Start** side by side at the top.
- [x] Replace the verbose “keep the text in context” explanation with a small
  visual example for the selected focus mode. Show what word/window/line modes
  actually highlight, including the continuous solid multiword window.
- [x] Remove the start countdown control, practice session, “increase target by
  25” action and the associated explanatory note.
- [x] Pause guided reading when opening the reader menu, typography/font panel
  or other modal settings. Check existing behavior before changing it; keep the
  exact passage and require a deliberate resume rather than running invisibly.

## Read aloud panel

- [x] Move **Resume listening** and **Read from here** to the top, side by side.
- [x] Put **Performance test** and **Prepare chapter ahead** side by side.
- [x] Remove the prominent **Plapre Pico inference threads** block. If the thread
  setting remains useful, move it to advanced settings as one compact row; do not
  let it dominate the narration screen.
- [x] Remove the helper copy about “all options work offline / Android speech
  uses installed English voices” and “pause resumes at the saved point in the
  audio.” Retain clear engine/loading/error states without these repeated notes.
- [x] Keep persistent reader playback/navigation controls usable; simplify
  grouping rather than hiding essential pause/stop/previous/next controls.

## Audiobooks, navigation and first-open guidance

- [x] **21 October user feedback:** chapter one of the generated `.foliobook`
  plays well in Folio. Preserve that playback while redesigning discovery and
  navigation; this feedback does not yet verify every seek/resume/import case.
- [x] Make **Audiobooks a proper library**, visually consistent with Books and
  the applicable Mihon library patterns: cover-led browsing, titles, progress,
  search/sort and status filters. Avoid making its home screen an import/setup
  form. Review a compact screen design before implementation.
- [x] Move audiobook file/folder import, source setup and related management
  controls into a dedicated **Import / Add audiobooks** screen, reached from a
  bottom **+** button matching the Books library. Keep the library's primary
  space for browsing and resuming existing titles.
- [x] Add consistent **All / New (not started) / Unread / Read** filters to
  **both Books and Audiobooks**. Review the exact distinction during design:
  proposed New = not started; Unread = unfinished, including in-progress titles;
  Read = completed/marked read. Keep in-progress titles easy to find. These
  semantics are provisional; do not silently treat New and Unread as synonyms.
- [x] Improve navigation into recorded audio: the current small headphone icon
  is too obscure/awkward. Draft a prominent labelled **Listen / Audiobook** action
  and clear reading/listening navigation with generous touch targets. Include
  a distinct, legible audiobook icon with a text label in the navigation design;
  this is separate from the six app-launcher icon proposals below.
- [x] Review where hybrid `.foliobook` editions appear across Books/Audiobooks
  so reading and listening are discoverable without redundant imports or losing
  their existing saved positions.
- [x] Carry forward the unfinished persistent audiobook **root-folder library**:
  choose a folder once, discover its books and nested CD/track folders, refresh
  changes, and retain access and listening position. Carry forward missing-folder
  relinking from 0.13.1; align its earlier status-filter proposal with the shared
  Books/Audiobooks filters requested above.
- [x] Remove the note “listening has its own saved position / browsing the book
  does not move it.” Preserve that behavior while simplifying the interface.
- [x] Make **Cat Café the permanent centre destination in bottom navigation**;
  remove the duplicate destination from More. Review consistency across screens.
- [x] Briefly show the configured tap-zone/page-navigation overlay for **one
  second when entering a book, enabled by default**. Make it informative and
  non-blocking; check touch-through, readability and accessibility/reduced motion.

## Compact voices, settings and library layout

- [x] Replace the long, busy voice-pack page with compact cards/rows: model name,
  language, concise quality description, download/installed size, installation
  state and a short device-demand badge. Put extended details behind expansion.
- [x] Keep sample playback and the main install/remove/select action immediately
  accessible. Show runtime/loading/download progress in place without stretching
  every card into a large settings form. Protect active models during removal.
- [x] Explain model quality as a supported comparison, not an invented benchmark
  score. Include relevant recommended settings and hardware demand succinctly.
- [x] Requested device guidance to evaluate: **Plapre 3 — very demanding; Galaxy
  S26 or newer recommended**, **Pico — Galaxy Z Flip7 class or above recommended**.
  These are the user's proposed recommendation tiers, **not validated performance
  guarantees**. Base final wording on actual tests; do not claim nothing can run
  Plapre 3 or that every newer phone necessarily sustains narration. Keep supported
  older devices distinguishable from recommended devices and desktop rendering.
- [x] Fix **Library display** overlapping the library screen. Review spacing,
  hierarchy, sheet bounds and large-text behavior on narrow and foldable layouts.
- [x] Review the supplied `E:/Overførsler/mihon-main.zip` and the existing Mihon
  reference for applicable **settings/navigation/library organization** patterns.
  Use the archive as reference data; verify licenses/attribution for copied source.
  Keep the result appropriate for EPUBs and audiobooks.

## Word-count / book-information page

- [x] Reduce the busy presentation: clear primary total word count, current
  chapter count and relevant progress; reveal chapter distribution and secondary
  statistics on demand. Avoid repeating explanations or showing every metric at
  equal visual weight. Keep chapter/page information consistent with the reader.

## Performance test and model loading

- [x] Extend configuration beyond sample wording: allow comparison of **passage
  word/chunk size** and **lookahead/preload amount** (passages or buffered audio),
  preserving sentence boundaries and reading/highlighting positions.
- [x] Distinguish the benchmark's sample text from the live narration buffering
  settings; expose the configurations that actually influence sustained playback.
- [x] Add visible phases, meaningful **progress bars, milestones and elapsed
  time**: model loading, warm-up, generation, buffering/playback evaluation and
  completion. Apply equivalent loading/preparation feedback to **Plapre 3**.
  Use indeterminate progress where no reliable total exists; do not fabricate %.
- [x] Report finished audio versus compute time, actual playback speed, stalls
  and tested configuration. Save comparable results so users can choose a
  sustainable configuration rather than infer it from a single short sentence.

## App icon — choose before applying

- [x] Create **six distinct original drafts**, numbered 1–6, for user selection.
  Keep the cat/book/Folio idea but aim for clean, geometric, polished modern
  product branding. The user cited Microsoft as a quality/style reference; do
  not copy its trademarks or make simple recolours of the current disliked icon.
- [x] Present one comparison sheet plus small launcher-size examples; include
  Android adaptive-icon masks and monochrome readability where helpful.
- [x] **Show the drafts to the user and wait for their choice before applying an
  icon to the next release.** Keep this task after Studio, not during its build.

## Acceptance

Review the compact layouts before broad implementation. Test copy/dictionary,
saved quotes, book/chapter counts, menu-triggered guided-reading pause, tap-zone
overlay timing, folder permission/relinking, voice/loading/benchmark flows and
font scaling on S10-class narrow screens and Flip7-class foldable layouts.
Verify an upgrade preserves the existing library, highlights, playback/café data.
Include the new Books/Audiobooks filters, persisted status/manual completion,
import-screen navigation, labelled listening entry and hybrid-title discovery.
