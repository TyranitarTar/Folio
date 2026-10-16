# Folio 0.9 design

14 October 2026. Implemented and released as Folio 0.9.0. Validation: 129 JVM tests, 16 targeted Android checks, real interrupted voice downloads and the signed 0.8.1 → 0.9.0 in-app upgrade passed. Galaxy S10/Bluetooth performance and acoustic acceptance remain device checks.

## Continuous moving word window

Interpretation of the requested behavior: a four-word window advances one word at a time. Three overlapping words stay highlighted throughout the transition. For a custom N-word window, N−1 words remain selected.

`[one two three four] five` → `one [two three four five]`

The window retains one uniform color and opacity, including intervening spaces. The exiting word loses its highlight and the entering word gains it in the same display frame. There is no fade, temporary single-word highlight, or blank frame between these states. Window mode advances one word; Group mode remains the separate mode for advancing a complete group.

Design investigation of 0.8.1: GuidedReading.show() commits a new current-word decoration, waits 25 ms, checks visibility, then calls GuidedBand.apply() to expand it to the full window. This can expose a single-word state before the band returns. This is a likely cause of the reported blinking; runtime capture should confirm it. The previous tests checked settled geometry rather than every frame of an advancing window.

Use a persistent paint layer in each loaded reading document. Resolve the next start/end anchors without replacing the visible paint. When both anchors and line rectangles are ready, update the existing painted segments together inside one requestAnimationFrame callback. Reuse segments on unchanged lines and retain the previous complete band while resolving the next one. The focused-word locator remains separate from the painted window so navigation, timing and saved positions retain their existing meaning.

Do not repaint all words as separate highlights. Merge ranges per rendered line and page, preserving spaces and inline formatting. Reflow recomputes the current window; it does not advance the cursor. Paragraph endings may shorten the window. Page/document transitions need a prepared destination highlight; avoid flashing an intermediate single-word state. Pausing retains the band; Stop removes it. Saved annotations and narration decorations remain separate.

Acceptance: examine advancing transitions frame by frame, including 300/500 WPM, four/eight-word windows, repeated words, punctuation, emphasis, wrapped lines and page boundaries. On overlapping words, no captured frame should have missing highlight or changed opacity. Verify pause/resume, reflow, rotation and bookmarks independently of paint geometry. Keep the existing compact reader player.

## Guided speed and narration speed

Add 300 WPM to all guided speed selectors. The complete preset set becomes 150, 200, 225, 250, 275, 300, 350, 400, 450 and 500. Preserve custom speeds, CPS mode and the user's current selection.

Add 2.5× and 3.0× narration presets and extend the configurable range to 0.5–3.0×. Apply the same bounds throughout the UI, saved settings, IPC/service handling, native generation, presentation clock, playback controls and backup validation. Existing code limits several of those paths to 2×, so adding buttons alone would not work.

Supertonic continues generating its source waveform at 1× and applies pitch-preserving playback speed. This preserves waveform reuse and sample-based checkpoints. Validate Kokoro's native higher-speed synthesis against playback time stretching before selecting its higher-speed path; preserve its existing behavior at established speeds unless a tested improvement justifies changing it. Android system speech receives the requested rate, but its interpretation and quality depend on the installed engine.

Buffer readiness must measure actual playable time at the selected speed. At 3×, twelve seconds of playback requires thirty-six seconds of source audio when synthesizing at 1×. Respect existing bounded PCM memory/lookahead limits. Sustained generation must keep up with consumption; allow explicit buffering when it cannot, without overlapping passages, dropping tails, or advancing the saved cursor during generation. At 3× the theoretical minimum is generation of three source-audio seconds per wall-clock second, plus processing overhead.

Speed changes preserve the last presented source sample, not elapsed wall-clock time. Sentence completion follows the audio presentation clock. Test halfway-through-passage speed changes, pause/resume, seek, Bluetooth routing and the final passage at 1/1.5/2/2.5/3×. A device route that rejects a requested speed gets a clear error and a supported-speed choice; never silently store 3× while playing 2×. If Kokoro's waveform policy changes, preserve the reading passage and explicitly migrate/repeat incompatible cached checkpoints rather than applying an old sample offset to different audio.

Android exposes separate pitch and speed controls, but this alone is not evidence that every device route performs well at 3×: [PlaybackParams](https://developer.android.com/reference/android/media/PlaybackParams). Galaxy S10 testing is required for sustained 2.5/3× Supertonic playback and intelligibility.

## Tap the page count for reading information

Make the existing Page X of Y / Screen X of Y label a clearly tappable target with an accessible “Reading information” label. Its bottom sheet shows:

- Current book page/screen, current chapter and overall progress.
- Total readable words, words before/after the current reading position, and the current chapter's word count.
- An estimated text-reading time at the selected guided WPM, explicitly excluding illustration viewing and additional pauses.
- Chapter rows with title, word count, share of the book and page range when available. A proportional bar compares chapter lengths. Explicit chapter-jump actions reuse existing navigation behavior.

Count the same readable word tokens used by guided reading and narration; explain that headings and retained notes/front matter are included. Visual location, guided position and listening position are independent: label the position used for “remaining,” defaulting to the current reading page. Do not imply a partial narrated paragraph has trustworthy word timestamps from a neural model.

Use real EPUB contents anchors, including chapters starting inside the same HTML resource. Nested chapter totals must not double-count text; top-level chapter shares partition the readable text. Include a front matter/other-text bucket for content outside chapter ranges. Image-only resources have zero readable words and can still have pages. Shared boundary pages can appear in adjacent chapter page ranges; do not sum those ranges as independent page counts.

Cache compact text statistics and chapter boundaries by book content hash and extraction schema, independent of font size. Extract in the background once; share the existing BookText work where available. Persist totals/boundaries, not a second full text/locator copy. Cache invalidation follows changed book content, not screen taps or theme changes.

Reuse PageCounter's existing layout cache for page information. Missing chapter page boundaries can be added during normal measurement of that resource, with reuse of its renderer. Opening the sheet must not create another renderer or restart a whole-book recount. Show words first and “Calculating”/“Unavailable” for unknown page ranges. Font/viewport changes affect pages, not word totals. Cache clearing or restore may regenerate statistics without changing progress or annotations.

Opening the information sheet pauses guided motion and retains its highlight. Narration can continue, with a Pause control available in the sheet. Closing the sheet keeps the same passage and does not automatically seek or resume guided motion.

## Optional local voice packs

Recommended first implementation: keep the compiled speech runtime in the signed APK and download model data as optional packs. Both local neural engines already load prepared files from private storage, making this feasible. The main refactor is replacing the assumption that every missing model can be recopied from bundled APK assets.

For the current GitHub APK distribution, use versioned data archives attached to GitHub releases. Play Asset Delivery and Play Feature Delivery use Google Play's delivery infrastructure, so they are not the chosen mechanism for this APK channel. [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery), [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery). GitHub release assets can hold these pack sizes: [GitHub releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).

Measured from the released Folio 0.8.1 APK; MB means decimal megabytes:

| Component | Current compressed contribution | Expanded files |
| --- | ---: | ---: |
| Kokoro model/configuration/voice data | 336.41 MB | 371.77 MB |
| Supertonic model/configuration/voice data | 124.05 MB | 145.33 MB |
| Shared native runtime, all shipped ABIs | 41.52 MB | 108.48 MB |
| Other compressed APK contents | 19.32 MB | Not measured here |

Removing the two model groups suggests a core APK around 60–65 MB instead of 521.44 MB, roughly 88% smaller. This is an estimate from archive contributions, not a built thin APK. Model-pack download sizes depend on archive format; roughly 337 MB for the current Kokoro pack and 124–125 MB for Supertonic are sensible initial estimates. Installed storage includes the core APK, extracted runtime, selected expanded packs and audio caches. Download staging temporarily needs more space.

Offer one Supertonic pack for English/Danish and all current voices, and one Kokoro pack shared by Emma and Heart. Do not download a full model twice for two voice choices. Language/voice choices within a shared pack do not require a second model download. Smaller voice/dictionary variants are a later optimization requiring native-format validation; do not promise them from current measurements.

### User flow

Add More → Voice packs and link it from Read aloud. Each pack card shows languages, voices, download size, installed size and state: Not downloaded, Downloading, Verifying, Ready, Update available or Failed. Actions: Download, Cancel/Resume, Retry and Remove. Make device Android speech available immediately when an appropriate offline voice is installed.

Each voice has a short Preview/Stop sample, available before downloading its model. Use a small prerecorded clip generated by the exact pack/voice, labelled with language and normal 1× speed. Samples use the same comparable original phrase per language and are cached for offline replay. Do not simulate an unavailable neural voice with Android speech. A missing/failed sample gets a visible retry state rather than initiating a model download. Installed packs additionally allow local preview at the selected narration speed.

The pack catalog separates pack metadata from its voice/language/sample entries. Future compatible packs can be added without changing the screen layout; new engine implementations still require a signed APK update. Each sample has an approved address, byte limit and pinned checksum, and playback is exclusive. Starting a preview pauses book narration at its saved point; it does not overwrite the listening cursor, voice selection or reading progress. Closing the voice-pack section or choosing another sample stops the previous preview. The section includes download/installed size, license/attribution and explanatory language/voice labels, avoiding a fixed two-card special case.

Choosing a missing neural voice opens an explicit size/space confirmation with Download and a device-speech alternative. Show separate download, verification, model loading and speech buffering messages. Downloading a pack alone never starts narration. For an explicit Download and read request, capture the requested book/passage and start only while that request remains current; navigation, cancellation or leaving the reader revokes automatic playback while the download may continue.

Installed packs work fully offline, persist across normal APK upgrades, and have independently versioned updates. The smaller APK does not make speech generation faster by itself. Removing a pack preserves books, voice preferences and listening positions; requesting its voice later prompts for download again.

### Pack installation and migration

Introduce a VoicePackStore and a pinned compatibility registry: pack ID/version, backend, format, engine version, exact file sizes/hashes, approved download addresses, language/voice mapping and license notices. First-release identities can be shipped in the signed APK. Any later remote catalog that authorizes new identities needs a verified signature; a downloaded archive's own manifest is not an independent integrity guarantee.

Reuse persistent transfer primitives where appropriate, with a separate voice-pack queue and suitable long-running-download handling for Android. Support interrupted downloads, cancel/retry, provider backoff and space checks using both archive and expanded sizes. Extract into bounded staging storage, reject unexpected paths/files, validate every file and native headers, then atomically activate the complete pack. Retain the previous working version until activation succeeds. No downloading executable Java/native runtime code in this phase.

The engine holds an installed-pack version while in use. Remove/update must release native inference safely through the existing worker before replacing its files. Associate generated audio and checkpoints with the model version as well as voice/language/text; a changed model cannot reuse an unverified waveform offset. Clearing audio caches is independent from removing voice packs.

Existing verified copies under noBackupFilesDir/local-voices can be adopted in place after checking the same identities. Users who already prepared a voice should not download it again. Packs present only inside an older APK, never prepared into private storage, will need a first download after upgrading to the thin APK; do not promise that Android preserves the old APK assets. Ship licenses/attribution with each pack and retain accessible credits in the core app. Regenerable packs remain excluded from library backups, while voice choices and reading checkpoints remain backed up.

Keep pack URLs immutable and independent of normal app versions. Hosting a pack release must not replace the latest stable numeric app release used by the in-app updater. Reuse unchanged packs across app updates instead of uploading/downloading them repeatedly.

Acceptance: fresh install with no packs, first download, offline reuse, process death/network interruption during download, cancellation during extraction, corrupted/missing files, insufficient storage, incompatible versions, adoption of existing copies, safe removal while paused/playing, and signed APK upgrade without redownloading unchanged packs. Verify actual core APK/download/installed sizes before advertising them.

## Delivery order

1. Fix continuous window painting and add 300 WPM; prove advancing-frame behavior rather than just settled screenshots.
2. Add 2.5/3× throughout the narration pipeline, with source-sample continuity and physical-device buffer checks.
3. Add cached reading information and chapter distribution behind the page label.
4. Introduce optional voice-pack storage/downloads, migrate existing prepared copies and build/measure the smaller APK.

Keep 0.8.1 available during testing. Publish the thin APK after missing-pack, migration, offline use and interrupted-download paths pass.

## Delivered behavior and follow-up

More → Voice packs includes 22 authentic pre-download samples, local previews for installed voices, optional model downloads, safe removal and a catalog for future compatible packs. Downloading alone does not automatically start narration; an explicit future Download and read flow remains deferred. The core APK is 61,045,559 bytes (61 MB), about 88% smaller than 0.8.1. Supertonic downloads 124,047,062 bytes; Kokoro 336,458,261 bytes. Previously prepared copies are reused. Guided paint, 300 WPM, 2.5/3× speech and cached reading information are implemented.

[Published release and validation](https://github.com/TyranitarTar/Folio/releases/tag/v0.9.0).
