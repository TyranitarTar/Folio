# Folio 0.14 — review of the original list and follow-up

Historical review of 0.14, before the 0.14.1 implementation. For delivered
changes see [IMPLEMENTATION-v0.14.1.md](IMPLEMENTATION-v0.14.1.md); for current
pending work see [ROADMAP-NEXT.md](ROADMAP-NEXT.md). Unchecked items below record
the state at review time, not a claim that 0.14.1 omitted those changes.

25 October 2026. Requested review, with the user's additional navigation and
pagination feedback. This document separates implementation from acceptance.
The original roadmap's checked boxes described code delivery; they did not
establish that the complete interface or phone performance was satisfactory.

## Release identity and evidence

Compared all 85 main Kotlin files in `output/Folio-source-0.14.0.zip` with the
workspace: all match. The local signed APK matches the release-validation hash:
`e00e95b9796c61afd883eb44df63be7383eb2692e6cc29572ca0c77265614b70`.
This review therefore examines the released implementation, not later Studio
changes or an assumed newer Android build.

Release evidence records 178 passing Android unit tests, one skipped test, 17
Android 35 emulator cases and upgrade/data checks. Those are functional checks;
they do not certify S10/Flip7 pagination speed, foldable/large-font presentation,
Bluetooth behaviour or the user's acceptance of the redesign.

## Original request: what actually shipped

| Original item | Present in 0.14 | Remaining qualification / work |
|---|---|---|
| Book and chapter page x of y | `PageCounter.kt`, `ChapterPagination.kt` | **Performance remains open.** Exact unknown resources still require rendering; user reports slow calculation. |
| Copy selected text; Danish dictionary | Selection actions in `ReaderActivity.kt`; encoded online Ordnet query | Online lookup was the permitted first implementation. Offline dictionary was not promised. Physical cross-page selection acceptance remains open. |
| Highlights save the actual passage and locator | Full saved quotes and stable locators are displayed/exported | Upgrade/unit checks passed; wider edition/layout/selection edge cases still need acceptance. |
| Guided Resume/Start side by side; focus preview | `ReadingPanels.kt` includes both actions and a live focus preview | Needs visual acceptance on the user's devices. |
| Remove countdown, practice session, gradual +25 | Controls removed; `GuidedOptions.normalized()` turns legacy options off | Legacy fields remain for compatibility; their presence in stored settings does not mean the removed controls are still shown. |
| Guided reading pauses for menus/fonts | Reader menu, typography, selection and lifecycle pause paths are present | Preserve during header/navigation rework. |
| Read-aloud top action rows; compact threads | `SpeechPanel.kt` puts entry actions first, diagnostics/preparation in a row, threads under Advanced | **Simplification is partial in quality.** Several engine explanations and controls still make this a long panel. |
| Audiobook root folder, nested CDs, refresh/relink | Persistent SAF sources in `AudiobookSources.kt` and the import sheet | Revoked grants/unusual providers and further physical-device cases remain open. |
| Proper audiobook library and separate + import | Search/sort/status rows, covers/linked actions, dedicated import sheet | **Navigation/presentation needs another pass**, especially exiting a playing title. |
| All/New/Unread/Read in both libraries | Shared filter semantics and manual status | Implemented: New = not started; Unread = unfinished. Keep positions independent of manual read status. |
| Clearer FolioBook/read/listen navigation | Labelled actions exist in library/player/reader | **Still inconsistent.** The global Books “Read + listen” row merely opens Audiobooks. New feedback explicitly removes it. |
| Café permanently in the centre; no duplicate in More | Centre destination in `FolioHome.kt` | Miso story/animation replacement remains a separate earlier future todo. |
| Tap-zone hint for one second, enabled by default | `NavigationSettings.overlay` defaults true; reader delays 1000 ms | Preserve explicit saved user preferences. Physical readability/touch-through acceptance remains open. |
| Short voice-pack page, quality/demand/size and samples | Collapsed details, sizes, device guidance and immediate actions in `VoicePacksPanel.kt` | **Only partly satisfies the density goal.** Cards still contain several text/button rows; quality advice remains descriptive and sample-based. Needs compact visual review. |
| Plapre/Pico device requirements | Pico Flip7-class guidance; Plapre desktop recommendation and unverified S26 wording | Deliberate qualification: no evidence supports guaranteeing S26 real-time Plapre 3 performance. |
| Library Display overlap; Mihon-style organization | Filter/Sort/Display grouping and bounded sheets | Functional grouping is present. Narrow/foldable/large-text visual acceptance remains open; this was not a complete Mihon UI replacement. |
| Six launcher drafts and selected icon | User chose 2, Bookmark Cat; adaptive/legacy/themed resources applied | Delivered. |
| Less busy word-count page | Primary word count; chapter distribution/counting details expand | Functional simplification delivered; include in the next visual acceptance pass. |
| Useful performance configuration, bars/milestones/time | Passage target, lookahead, startup buffer; diagnostic phases/time/reports and optional audible test | Configuration is real. Emulator checks and buffer simulation are not physical-phone sustained narration benchmarks. |

## New priorities confirmed by the user on 25 October

- [ ] **Leave a playing audiobook or FolioBook easily.** Provide an obvious
  Library/minimize action from both reading and audio playback. Preserve the
  listening cursor and continued playback when returning to the library; keep
  Pause/Stop as clear controls. Returning should not require traversing a
  reader/player activity stack repeatedly.
- [ ] **Unify reader and audiobook headers.** Same placement/target size for
  Library/back, actual title and relevant actions; consistent hierarchy when
  controls are shown. Verify text-to-audio, audio-to-text, system Back and library
  return while playing, paused and loading.
- [ ] **Rename the visible e-book section to Reading.** Update the tab, header
  and related navigation labels consistently. Preserve existing internal saved
  Books/tab preference keys or migrate them explicitly.
- [ ] **Remove the global Read + listen row from Reading.** It currently only
  switches to Audiobooks. Keep useful per-title listening actions for a linked
  FolioBook; do not hide the actual narrated edition or duplicate imports.
- [ ] **Make page counting measurably faster.** Treat the user's report as an
  open defect, not closed by the existence of a cache. Measure before choosing
  changes and report cold/reopen/font-change behaviour separately.

## Concrete findings behind those priorities

`FolioHome.kt` currently labels the section Books. Its `Read + listen` button
sets the selected tab to Audiobooks. This is the redundant navigation described
by the user.

`AudiobookPlayerScreen` uses Material `TopAppBar`, a generic Audiobook title and
a Back arrow. The reader builds a separate custom title/search/font row which
can be hidden. Its Back handler first closes a sheet or shows controls. Moving
from the audio player to its linked text starts a reader activity; finishing
that activity can return to the player rather than directly to the library.
The individual back actions exist, but they do not provide a consistent single
library-return action.

Page counting already has per-layout memory/disk caches and prioritizes the
visible resource. A new layout still visits all unknown EPUB resources with a
hidden Readium renderer in sequence. Every page-change callback also calls
`verifyVisible()`, which cancels/restarts a sampling job. Sampling waits for
settled geometry and can collect DOM chapter/statistics boundaries. Counting is
deliberately deferred during playing/preparing speech. These facts explain
plausible delay paths; this review does not claim to have profiled the phone.

### Pagination investigation and acceptance

- [ ] Record time to first useful chapter count and exact book total, cache
  hits/misses, renderer visits, repeated visible verification and DOM/boundary
  time. Cover first open, unchanged reopen, a font change and rotation.
- [ ] Reuse verified chapter geometry on ordinary page turns; investigate
  avoiding full resource resampling when only the current page changes.
- [ ] Investigate unnecessary layout-key changes/cache invalidation and repeated
  cancellation before attributing everything to book size.
- [ ] Keep chapter/statistics boundary work bounded and independent of showing
  the first useful page count. Investigate lazy boundary collection.
- [ ] Preserve accurate layout-specific counts. Unknown book totals must be
  clearly pending; do not present word estimates or stale typography totals as
  exact pages. Cache display/current-chapter priority already exist and must be
  retained, not advertised again as a new fix.
- [ ] Confirm UI interaction remains responsive during counting; compare a short
  EPUB, a long EPUB and an image-heavy book on the target phone when available.

## Remaining acceptance from the original overhaul

- [ ] Review voice packs, read aloud, library sheets and statistics visually
  against the user's density goals, including narrow and expanded fold layouts.
- [ ] Review the complete original checklist with the user after those screens
  are concrete; track implementation, automated verification and user acceptance
  separately instead of marking the entire overhaul complete at once.
- [ ] Preserve books, audio, highlights, café state and reading/listening
  positions through the follow-up upgrade.
