# Folio: page navigation and speech implementation record

## 0.10.2 and device priorities

Updated 18 October 2026. The user accepts Pico's remaining Galaxy S10 limitations;
Galaxy Flip7 is the priority for future Danish models. Flip7 listening is better,
but can still exhaust the buffer during longer sessions, particularly at 1.5× / 2×.

- Implemented for 0.10.2, under verification: explicit Pico inference threads up to
  eight, native stage timings and warm reversed-order measurements; 20 / 30 / 35-word
  sentence-boundary experiments; silent, bounded preparation ahead without moving
  the listening bookmark; voice storage inventory and ownership-based cleanup.
- Confirm sustained generation during actual playback on S10. Record the throughput
  rather than promise continuous narration when the requested speed exceeds it.
- Finish 0.10.2 installation/data-retention verification. The full public
  0.9.0 → 0.10.1 flow has now passed on the populated test emulator.
- Next: run the same benchmark and long 1× / 1.5× / 2× reads on Flip7. Cover Bluetooth,
  heat, memory and unplugged battery use. USB-powered checks cannot establish battery life.
- Continue evaluating Plapre 3 and Røst v3 as optional future packs using the shared
  bounded producer/player and saved source positions. Keep English Kokoro available.
- The reading-streak cat game remains a separate planned feature.

See [0.10.2 implementation](IMPLEMENTATION-v0.10.2.md) and
[voice storage](IMPLEMENTATION-v0.10.2-storage.md). Version-specific entries below
record earlier decisions and their evidence at that time.

## Current priority: Plapre Pico

Updated 17 October 2026. Folio 0.10.0 and its optional Danish Pico pack are published. Kokoro for English and Supertonic remain available. The older version-specific plans below are historical records. See [Pico implementation](IMPLEMENTATION-v0.10.md) and [continuity and obsolete-version storage evaluation](DESIGN-v0.10.1-speech-continuity-and-storage.md).

- [x] Evaluate Pico against the existing engine, downloader, cache and playback architecture. This is a source/documentation assessment; no Pico synthesis or phone benchmark yet.
- [x] Prove the complete Pico → Kanade → HiFT waveform path with a public INT8 ONNX conversion; correct its rotary configuration and compare dynamic acoustic outputs with the reference. The all-ONNX path was selected over adding llama.cpp.
- [x] Implement the Android engine, source-sample playback/checkpoint integration, downloadable voice pack and authentic Danish samples. Initial Android native/cancellation/checkpoint, ordered 1.5× PCM playback and real Danish EPUB service checks passed. Physical-phone quality and throughput remain unmeasured.
- [x] Validate native generation, ordered queued playback and checkpoint recovery on the emulator, run existing-engine/public-download regressions, and build/publish the signed 0.10.0 APK with the original certificate. The ordered Pico test uses pre-generated clips; sustained cold-synthesis continuity is not established by that check.
- [x] 0.10.1: remove excess Pico edge padding with conservative speech margins, preserve interior audio and use a new cache identity so old offsets cannot be applied to shortened clips. Native reference: 5.92 → 4.69 seconds; all five voices validated. See [implementation and measured limits](IMPLEMENTATION-v0.10.1.md).
- [x] Verify real background production using 20 cold-cache passages through the service: eight native jobs started inside presented passages and 12 handoffs had lookahead ready. The buffer still starved under emulator load (maximum wait 5.74 seconds), so this does not establish sustained phone performance.
- [ ] Priority: improve sustained Pico generation for Galaxy S10/Flip 7. A warmed 19-word sentence-group comparison was only about 5% faster on average (two trials); production grouping is unchanged. Evaluate bounded word targets at semantic boundaries with correct navigation/highlighting, profile acoustic stages and two/four-thread CPU settings, and consider bounded chapter preparation when real-time synthesis cannot keep up. Do not use screen-wrapped lines or treat a larger buffer as a throughput fix.
- [ ] Priority: inventory obsolete model versions and add safe cleanup with reclaimable size, ownership manifests, active-engine/shared-component protection, rollback recovery and separate partial-download/audio-cache controls. Current removal covers only the current catalog version; older version folders can survive upgrades. Keep current comparison engines installed.
- [ ] Physical Galaxy S10/Flip 7 acceptance: Danish listening quality, sustained speed, RAM, heat/battery and Bluetooth. Keep Pico marked experimental until this is measured. Earlier plans referenced Fold 7; the latest reported device is Flip 7.
- [ ] Complete the production 0.9→0.10 in-app installation UI and data-preservation check; the previous automation did not finish this flow.
- [ ] Future: Plapre 3, including possible shared acoustic components and an FP32 quality/performance evaluation.
- [ ] Future: Røst v3 Chatterbox 350M Danish quality pack investigation.
- [ ] Future: Folio Cat Café with durable reading/listening rewards, streaks and a small incremental game; [design](DESIGN-next-voices-and-cat-game.md).

## Next release: pagination performance and guided-reading presets

Updated 2026-10-04. These are planned changes; the current release is v0.5.0; the items below remain planned.

### 1. Make page counting efficient

The current `PageCounter.kt` renders every reading-order resource sequentially in a second Readium navigator on a cache miss. It waits for fonts/images and repeated stable measurements, then saves the whole-book result only when all resources finish. `ReaderActivity.kt` also keys this cache on the complete appearance preferences, including theme colours. This explains unnecessary work on layout changes and interrupted calculations. Existing debounce and whole-book caching help, but do not address these costs.

- Profile book-open time, time to a usable page label, resource renders, peak memory and responsiveness, both with and without speech playing. Cover a long novel and an illustrated EPUB, cold/warm opens and repeated appearance changes.
- Use a stable, explicit layout fingerprint: publication/content identity, effective viewport, font family/size, line spacing, margins, publisher layout, reading direction and reading mode. Avoid invalidating counts for colour-only changes when effective layout is unchanged; account for publisher CSS that actually changes geometry with theme.
- Persist verified counts per resource and layout as each measurement completes. Resume incomplete work after reopening instead of starting the book again, and reuse counts when returning to a previously measured layout.
- Obtain the current resource's page count promptly, using visible-renderer information where it is reliable. Fill missing counts progressively without blocking reading, guided highlighting or speech. Exact global Page X of Y requires counts for preceding resources and the total; show chapter-local progress or a clear pending total until those are known.
- Reuse Readium layout information and completion events where available. Reduce repeated JavaScript polling; stop waiting promptly once the relevant layout is verifiably ready.
- Create the measuring renderer only when missing counts require it and release it when work finishes or is cancelled. Pause or reduce background measurement if it competes with interaction or speech generation.
- Keep debounce/cancellation for rapid font changes. An obsolete job must not publish counts or clear the busy state of its replacement.
- Retain exact rendered pages and stable passage locators. Do not present character-based estimates as exact page totals or use page numbers as saved reading/listening positions.

Acceptance: reopening an unchanged, fully cached book performs no full-book measuring pass; colour-only changes with unchanged geometry reuse counts; interrupted work retains completed resources; font/viewport changes give correct new totals and preserve the passage. Check chapter boundaries, illustrations, the final page, rapid settings changes, rotation and simultaneous TTS. Establish numerical performance targets from the baseline and report before/after measurements on a physical ARM phone.

### 2. Guided-reading speed levels

- Replace the WPM preset choices with exactly **150, 200, 225, 250, 275, 350, 400, 450, 500** in ascending order.
- Keep custom WPM speeds and CPS mode. These levels apply to guided word highlighting, independently of speech speed.
- Arrange all nine choices so they remain accessible on a narrow phone and with large UI text.
- Applying a preset changes the timer without skipping/resetting the current word or changing paused/running state. Save the selected speed across reopening.
- Verify each preset's base interval (`60000 / WPM` ms), saved preference, word order and chapter transitions.

### Danish speech: feasibility investigation

See [Danish TTS feasibility and primary sources](Danish-TTS-feasibility.md). Supertonic 3 is included in v0.4.0. Measure it on a physical ARM phone and consider Piper as a lighter local alternative; investigate Plapre Pico for LLM-based speech and Røst as a Danish quality reference. Before selecting a model, measure pronunciation, latency, sustained generation speed, memory and battery on the target phone. Preserve the existing listening checkpoint and bounded audio buffering. Evaluate optional voice packs and unloading unused engines so adding Danish does not require another large, always-loaded model.

## v0.5.0: ordered audio and Mihon-style EPUB library

Natural AI passages now append to one persistent AudioTrack FIFO; only stop/seek/model changes replace it. Presentation timestamps withhold committed future frames. Unavailable timestamp routes use a conservative render guard. Pausing during replacement preparation cannot inherit an old output offset. Installed Android speech uses QUEUE_ADD for natural passages; explicit requests stop its old queue first.

Mihon's supplied source informs the new home navigation, cover-grid/list modes, filter/sort/display sheets, shelves, selection actions, history and EPUB book details. Existing EPUBs, annotations, reader controls and both neural model packs remain compatible. The separate page-counting/preset roadmap above remains pending.

## v0.4.0: Supertonic and continuous speech

Adds bundled Supertonic 3 INT8 with English/Danish selection and ten voice styles, retaining Kokoro Emma/Heart for comparison. Both share the existing native runtime and sample-based listening checkpoint. Only the selected engine is loaded. Queue identity includes model revision, language, voice, speed and Supertonic's fixed generation seed/steps.

Addresses line-by-line waits with a six-passage lookahead, a twelve-second initial buffer and up to four Kokoro CPU threads. Read from here now captures the visible word rather than a paragraph's first word, uses fresh page context on each press and cancels/replaces the previous request safely. Tests measure real cold-synthesis handoffs and check a page beginning within a wrapped paragraph.

## v0.3.0: local AI speech

Implemented Kokoro v1.0 FP32 through sherpa-onnx v1.13.8 with two English choices: British Emma and American Heart. The APK bundles the model/runtime and needs no Internet permission. Android speech remains the compatible fallback and the existing default for upgrades.

Both backends use the same media service, audio-focus handling and independent listening cursor. AI playback persists actual AudioTrack sample offsets, retains current passage PCM in a bounded cache and prepares only one upcoming passage. Pause/reopen resumes at the audio checkpoint; voice/rate changes replay the current passage. AI follows passages rather than fabricating word timing. Existing WPM/CPS guided reading stays independent.

Real Android synthesis/playback recovery and the reader regression suite are included in the release validation. Physical ARM phone performance, long-session battery/memory use and Bluetooth interruptions still require device testing. The accompanying application/native source bundles and license texts include the eSpeak NG GPL obligations.

## Historical v0.2.0 scope

Implementation record for Folio 0.2.0. Configurable page taps, timed guided word highlighting, rendered page totals and native offline English TTS are implemented. The original 0.1.0 APK is retained. AI/custom speech models remain deferred. See README and the versioned validation report for verified behavior and remaining physical-device checks.

## Objective

Read an EPUB aloud offline, with a pleasant English voice and reliable recovery of the listening position. Starting speech, changing typography, returning to the library, or switching between reading and listening must not reset progress.

Make page navigation by tapping a first-class reading option, with configurable tap zones following the Tachiyomi/Mihon approach. Continuous scrolling remains an option, and readers must be able to choose paginated reading explicitly.

## Confirmed speech scope

Use Android's standard TextToSpeech API with an installed, good-quality English offline voice. Prefer a suitable high-quality English voice when available, allow British/American English and voice selection, and remember that choice. Actual voice availability and quality depend on the installed engine and device. If no suitable offline English voice is installed, explain how to install the engine's voice data rather than silently using a network voice.

The current release will use the system speech engine; it will not bundle a custom speech model or LLM runtime. Kokoro, Piper, custom AI voices and IVONA extraction are deferred to a later phase. Keep the speech interface extensible for that future work without implementing those engines now.

## Implementation order

### 1. Configurable page navigation

- Provide an explicit **Pages / Continuous scrolling** setting. In paginated mode, a tap advances or reverses exactly one displayed page; swipes and the existing page buttons remain available.
- Offer Mihon's documented tap-zone presets: **Default, L shaped, Kindle-ish, Edge, Right and Left, Disabled**. Use the project's actual zone definitions as the reference during implementation rather than guessing their geometry from the names.
- Offer tap-zone inversion: **None, Horizontal, Vertical, Both**. Keep zone inversion separate from the book's left-to-right or right-to-left reading direction.
- Proposed Folio starting layout for ordinary left-to-right EPUBs: right side advances, left side goes back, centre shows/hides controls. Honour EPUB reading direction and allow a user override. This is Folio's proposed default, not a claim about Mihon's default geometry.
- Include a visual preview of the active zones in settings and an optional brief zone overlay when a book opens.
- Allow page-transition animation to be switched on or off. Include optional volume-key page turning and reversal of its direction.
- Persist global navigation settings and allow an individual book to override them. Reopening the app or rotating the device must retain the selection.
- In continuous mode, define tap behavior explicitly. A proposed option is scrolling by roughly one screen with a small overlap; this is a Folio proposal to evaluate, not an existing Mihon behavior being promised.
- Tapping links, selecting text, long-pressing to annotate, using controls, and interacting with an illustration must not also turn a page.
- Save the reading locator after each page change. Turning pages must not overwrite a paused listening cursor unless the user chooses **Read from here**.

#### Guided reading: timed word highlighting

- This feature highlights one word at a time in the normal book layout, then advances the highlight after a timed interval. It is not continuous automatic scrolling, and it does not replace the page with a standalone word display.
- Provide **Start / Pause / Resume**, **words per minute (WPM)** or **characters per second (CPS)**, presets and custom speeds. Proposed presets: 150/200/250/300 WPM and 10/20/30 CPS.
- WPM sets the base word interval to `60000 / WPM` milliseconds: 200 WPM means 300 ms per word. Optional punctuation pauses, if later offered, must be identified separately because they affect the effective pace.
- CPS sets a word's duration from its actual counted characters: `1000 * characterCount / CPS` milliseconds. Include punctuation and the following normalized whitespace in the segment's count; do not count markup or hidden content. Longer words therefore remain highlighted longer.
- Define word boundaries consistently for English and Danish, including punctuation, hyphenation and words split across inline HTML elements. Never skip or double-count a word when crossing a line or chapter.
- Let speed or pace units change without moving or resetting the current word. Show converted equivalents as approximate because word lengths vary.
- Turn to the next page when the highlighted word moves beyond the current page. In continuous reading mode, reposition only as needed to keep that word visible; viewport movement supports word tracking rather than being the pacing mechanism.
- Save the highlighted word using a stable EPUB passage locator and text offset. Retain it across font changes, rotation, pause, reopening and changes of reading mode.
- Pause for manual navigation, text selection, settings, backgrounding and screen lock. On illustration-only pages, offer a pause for viewing before continuing.
- Use a temporary reading highlight that cannot overwrite saved annotations. Keep the independent pace timer separate from TTS following; speech callbacks drive speech-following mode.
- Acceptance checks: verify highlighted word order, measured WPM/CPS intervals, speed/unit changes, automatic page transitions, and recovery of the highlighted word after interruption or layout changes.

#### Page X of Y for the current layout

- Show **Page X of Y** as the main progress label, replacing percentage-only feedback. Retain the progress slider.
- Count the book's actual displayed pages for the active layout rather than relabeling Readium's fixed content positions as pages. Include resources in reading order and illustration pages.
- Recalculate the current and total page counts after font size/typeface, line spacing, margins, publisher layout or viewport dimensions change. Preserve the underlying passage while its page number changes.
- Calculate/cache totals in the background, keyed to the book and layout. Show a clear calculation state while a new total is being determined, and discard stale results when the layout changes again.
- Define continuous mode's counter as equivalent pages for the current viewport, while actual paginated mode counts rendered pages. Keep the distinction clear in the UI if both modes show a counter.
- Never persist a layout-dependent page number as the canonical reading, guided-reading or listening position.
- Acceptance checks: one page turn changes X by one, the final page is Y of Y, and changing font size changes total pages without changing the passage. Check chapter boundaries, images, rotation and controls that change the available viewport.

### 2. Preserve listening position

- Add a persistent listening cursor alongside the existing visual reading cursor, preserving the existing library, marks and progress during migration.
- Anchor the listening cursor to the book identifier, EPUB resource, passage text and sentence offset. Screen page numbers change with typography and are unsuitable for persistence.
- Update the cursor from speech actually played, not text queued or audio generated ahead.
- Provide **Resume listening** and **Read from here** as distinct actions. Browsing a different page must not silently replace the listening cursor.
- Checkpoint during playback and on pause, interruption, stop and chapter changes. A sudden process death should recover at the last safe sentence boundary; a little repeated speech is preferable to skipped text. More precise cached-audio offsets can follow.

### 3. Build reliable playback

- Extract readable content through Readium and retain its connection to EPUB locators.
- Play/pause, previous/next sentence, speech speed, voice choice and optional following/highlighting of the spoken passage.
- Screen-off/background playback, a foreground media notification, headphone controls, audio focus and interruption handling.
- Prepare a small buffer of upcoming speech. Avoid synthesizing an entire novel before playback.

### 4. Validate standard English TTS

- Enumerate installed English voices, select suitable offline voices, expose voice/speed choices and handle missing voice data or engine failures clearly.
- Use speech-engine playback callbacks to save listening progress. Use word/range timings when the engine supplies them; keep a safe sentence-level recovery fallback when it does not.
- Test dialogue, abbreviations, numbers, long sentences and names such as Maomao/Jinshi with the available regular English voices.
- Check responsiveness, memory, battery use and uninterrupted playback on the target phone. Verify airplane-mode operation after voice data is installed.
- Keep original book text unchanged. Any future pronunciation overrides affect speech only.
- No Kokoro/Piper/LLM model downloads, benchmarks or integration in this phase.

### 5. Regression tests and next APK

- Test next/previous taps for each zone preset and inversion setting, with controls both visible and hidden.
- Verify one tap produces one page change, including at chapter boundaries, with no skipped pages.
- Test pagination versus scrolling, left-to-right/right-to-left reading, portrait/landscape, different fonts and illustration pages.
- Verify saved navigation preferences and the reading passage survive closing/reopening and changing reading modes.
- Verify links, long-press selection and annotations do not trigger accidental navigation.

- Start halfway through a chapter; confirm speech starts there.
- Pause midway, close and reopen; confirm sentence-level recovery.
- Test screen lock, app process death, rotation, incoming audio interruptions, headphone disconnect and phone restart.
- Change font, theme, scrolling mode, speech speed and voice without losing the passage.
- Browse another chapter while paused; **Resume listening** still uses the listening cursor.
- Confirm chapters continue in order and queued speech does not advance persisted progress prematurely.
- Confirm existing reading positions, bookmarks and notes survive the data update.
- Test guided word timing/order and Page X of Y before and after changing the layout.
- Package a new signed APK only after these checks pass. Custom AI speech remains deferred.

## Primary references

- [Mihon reader settings: tap zones, inversion, overlays and page transitions](https://mihon.app/docs/guides/reader-settings)
- [Kokoro model and license](https://huggingface.co/hexgrad/Kokoro-82M)
- [Kokoro English voice inventory](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md)
- [Sherpa-ONNX offline Android support](https://github.com/k2-fsa/sherpa-onnx)
- [Kokoro models for Sherpa-ONNX](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html)
- [Android voice network requirements](https://developer.android.com/reference/android/speech/tts/Voice#isNetworkConnectionRequired())
- [Current Piper engine](https://github.com/OHF-Voice/piper1-gpl)
