# Folio
Folio is a vibe-coded Android reader with Danish and English TTS.

Version **0.15.0** adds compact swipeable **All / New / Reading / Finished**
libraries, shelf selection in the Reading title, cached audiobook covers,
embedded M4B chapter navigation and separate chapter/book seek bars. Listening
has clearer transport, bookmarks and recovery history, configurable skip
intervals and optional rewind after longer pauses. Version history is inside
**About Folio**; all update actions/progress stay at the top of **App updates**.
Existing voices are unchanged. See [implementation and limits](IMPLEMENTATION-v0.15.md).

Version **0.14.1** makes **Reading** and **Audiobooks** use matching cover grids,
compact/list layouts and library settings. Player/reader headers share a direct
**Library** action; playback controls remain available after leaving a player.
Audiobooks has a **rescan** icon for saved roots. New ordinary root-folder audio
links original files immediately, with duration metadata read afterwards; this
avoids copying/hashing the entire library. Keep those source folders available.
FolioBooks and explicit imports still copy for offline use. Page turns reuse
verified geometry, and chapter-boundary lookup avoids repeated document-prefix
scans. Voice-pack cards are shorter. See [verification and limits](IMPLEMENTATION-v0.14.1.md)
and [the Smart AudioBook Player comparison](SMART-AUDIOBOOK-COMPARISON.md).

Version **0.14.0** uses the selected **Bookmark Cat (icon 2)** branding. Books and
Audiobooks share All/New/Unread/Read filters and manual read status. Audiobooks
has search/sort, a separate **+** import screen and saved library root folders
with refresh/relinking and nested CD ordering. The permanent centre tab is Café.
The reader adds rendered chapter page counts, Copy, online Danish dictionary
lookup and full saved passages. Guided-reading, read-aloud, statistics and voice
screens are more compact. Advanced narration settings control passage size,
lookahead and startup buffering; diagnostics show phases/elapsed time and offer
an audible live generation/playback trial alongside the labelled simulation.
See [implementation and verification scope](IMPLEMENTATION-v0.14.md).

Version **0.13.0** adds separate **Books** and **Audiobooks** tabs, a recorded-audio
player with saved positions/bookmarks, chapter tracks, 0.5–3× speed, a sleep timer
and background controls. Import audio files, an audiobook folder, or a narrated
`.foliobook` companion made on the PC. Narrated books also remain in Books, with
recorded sentence highlighting and listening from the reading page. Original
EPUB bytes and annotations are preserved. Audio backups remain separate; retain
source audio or the companion package. See [0.13 details](IMPLEMENTATION-v0.13.md).

Version **0.12.0** adds the optional **Cat Café** with Miso, three animated treat
interactions, six complete Danish story chapters and a separate in-app test mode.
Earn treats through active reading/listening and spend them to open his story.
Includes reduced motion, resumable scenes, a separate café backup and the new
cat-and-book icon. See [Miso's implementation](IMPLEMENTATION-v0.12.md).

Version **0.11.0** adds experimental Plapre 3 Danish narration as an optional FP32 voice pack, independent voice/CPU choices and a Plapre 3 performance test. It uses the existing background audio queue, listening checkpoints and preparation ahead. The Library home also shows an Audiobooks coming-later section; audiobook playback and a joint UI cleanup remain future todos. See [implementation and verification scope](IMPLEMENTATION-v0.11.md).

An original Android app for reading local EPUB books. Kotlin and Jetpack Compose provide the library and controls; Readium Kotlin Toolkit 3.1.2 renders EPUB content. The Windows Icecream executable and its code or artwork were not used.

Version 0.10.0 adds experimental offline Danish Plapre Pico narration with five voices. Download its optional pack and compare genuine samples in **More → Voice packs**, then choose **Read aloud → Plapre Pico · Dansk**. Pico uses the existing continuous player and listening checkpoints; 0.5–3× speed changes reuse its source waveform. The pack downloads approximately 0.529 GB and installs 0.622 GB; allow approximately 1.19 GB free while installing. Physical Galaxy S10/Fold7 speed, battery and listening quality remain device checks. English Kokoro and Supertonic remain available. Røst v3 desktop export arrives in 0.13.0; Miso’s first Cat Café arrives in 0.12.0. See [Pico implementation](IMPLEMENTATION-v0.10.md).

Version 0.9.0 adds optional voice-pack downloads with authentic pre-download samples, continuous moving-window paint, 300 WPM, 2.5×/3× narration and tappable reading statistics. Version 0.8.1 fixes a crash when updater progress changes to APK verification. Version 0.8.0 adds English/Danish book discovery and downloads from Project Gutenberg and Wikisource, a timed screen-awake policy, and continuous, uniformly colored guided word windows. Version 0.7.1 added sentence/paragraph guided skips, direct Stop buttons, cache-first pagination, version history and explicit GitHub update checks. Version 0.7.0 added a compact on-page narration player, visible preparation/cancellation, always-on spoken highlighting/following, speed-aware Supertonic playback, text navigation, sleep timers and voice previews. Guided reading adds moving windows, word groups, rendered lines and optional practice sessions. The transactional library, backup/recovery and incremental page counts from v0.6 are retained. See [Mihon adaptation](Mihon-adaptation.md) and the [Danish speech study](Danish-TTS-feasibility.md) for background.

## Install and use

Copy `Folio-0.15.0.apk` to your Android phone, open it, and allow installation from that source if Android asks. Install over the earlier signed Folio release to retain your library, annotations and saved positions. Minimum supported version: Android 8.0 (API 26). Emulator checks do not establish physical-phone performance.

Choose **Read aloud → Supertonic 3 → English / Dansk**, then choose one of ten voices. **Kokoro · English** retains British Emma and American Heart for comparison; **Android speech** uses installed offline English voices. Choose **Resume listening** for the saved listening position or **Read from here** for the first visible word on the current page. Download a neural voice pack once in **More → Voice packs**. Reading then works offline; Android speech uses its already installed voices.

**Plapre 3:** open **More → Voice packs**, audition Ida/Tor/Liv/Ask/Kaj and download the optional pack. Choose **Read aloud → Plapre 3 · Dansk**. The FP32 pack is about **1.73 GB download / 1.87 GB installed**; allow at least **3.64 GB free** during installation. It requires 64-bit Android and may generate slower than playback. Use **Prepare chapter ahead** before listening when necessary. Voice and thread choices are separate from Pico; speeds reuse the source waveform. The Plapre 3 performance test in Read aloud compares 1/2/4/6/8 threads directly on your phone without USB debugging. The pack is self-contained, so it includes its own codec files even when Pico is installed. Keeping English Kokoro needs no additional English download. Packs and samples are hosted in Folio’s GitHub releases; the app does not contact Hugging Face or require its credentials.

The main APK excludes neural model data. **More → Voice packs** offers Supertonic (about 124 MB download / 145 MB installed) and Kokoro (about 336 MB download / 372 MB installed), English/Dansk voice selection, authentic 1× samples before downloading, progress, cancel/resume, retry and removal. Each sample is a small original recording from that exact model and voice; it is cached for later offline comparison. Installed packs also offer a locally generated preview at the selected speed. Samples pause narration without moving its listening checkpoint or changing the selected voice. Allow about 303 MB free for Supertonic or 742 MB for Kokoro during installation; temporary archives are removed after activation.

Existing prepared models from earlier versions are rehashed and reused without downloading. Fresh installations download only the chosen pack; model data is installed in private storage outside Android backup. Downloads run through WorkManager, resume partial immutable archives, verify the pinned archive and every file, then activate the pack atomically. Cancellation or a failed install retains the prior model. Removing a pack keeps the library, settings and listening positions. Corrupt or removed data can be downloaded again. Future compatible packs can expand the catalog in app updates; new engine code still requires a signed APK update. Only the chosen neural engine is loaded. Physical ARM phone latency, memory and battery use remain to be measured.

Continuous neural playback prepares up to thirty-two passages ahead within a 32 MiB / 180-second PCM budget and buffers at least twelve playable seconds, or the remaining book if shorter, before starting. This adds an initial preparation wait instead of generating each line after the preceding line ends. Kokoro uses up to four CPU threads, based on available processors; Supertonic uses two. Sustained speech still depends on the device generating audio faster than playback, especially at higher speech speeds. Natural passages append to one PCM AudioTrack FIFO; generation and output are separate. Presentation timestamps withhold future committed frames, and unsupported routes use a guarded render estimate. Pause/resume preserves a sample checkpoint. A growing MP3 is unnecessary: it would add re-encoding and make seeking/checkpoints harder. **Read from here** captures a fresh visible word and its surrounding text, replaces the listening position deliberately, enables following, and closes the controls.

The home interface follows Mihon's library patterns for EPUB books. **Library** offers comfortable/compact cover grids or a list, cover badges, quick resume buttons, search, reading-status filters, annotations filter, sorting and persistent display options. Create or rename shelves through the library menu; long-press a cover to select books and assign multiple shelves or remove a confirmed selection. **History** shows books by last read date, **Browse** searches the two supported book sources, manages downloads, imports local EPUBs or opens the included sample, and **More** exposes reader settings, library display, app theme, speech controls and licenses.

Tap a cover to open **Book details**, with the real EPUB chapter list, saved annotations and Resume reading/Read aloud actions. A cover's play button resumes reading directly. Import through Browse, the + button or a file-manager open/share action. Original EPUB files remain outside Folio's private copy.

Tap the page edges or swipe to turn pages. Tap the centre to hide or show controls. Use **Aa** for appearance, the list button for contents/bookmarks/notes, and the bookmark button to save your place. Long-press text to highlight it, add a note or start narration from the selection. Search results, contents entries, and saved marks jump to their passage. Notes can be exported to a text file.

## Download books inside Folio

Open **Browse → Sources**, choose **Project Gutenberg** or **Wikisource**, and select **English** or **Dansk**. Search for a title or author, open a result, and choose **Download to library** after its edition has been checked. Gutenberg prefers illustrated EPUB 3, then EPUB, supported book HTML or UTF-8/UTF-16 text. Wikisource prepares readable works from the official text API, following explicit contents or work subpages and adding source/contributor attribution. It rejects ambiguous editions and missing required sections. Unsupported scanned/PDF/DRM editions are not converted.

**Browse → Downloads** shows queued, downloading, preparing, completed and failed books, with Cancel, Retry and Read. Jobs persist across closing/reopening the app and wait for a network connection. Partial files resume when the provider offers a stable version marker; otherwise they restart. Repeated downloads retain existing reading positions and annotations. Clear finished removes transfer/conversion files while keeping imported library books. Conversion is bounded to 20 MB of text, 120 linked sections and 100 supported illustrations (50 MB total); direct EPUB downloads are capped at 200 MB. A provider outage or unsupported edition is shown as an error rather than an incomplete library entry.

Catalogs and book text are supplied by the providers and may change. Gutenberg's catalog format is scheduled to change in 2027; its adapter will need updating then. Wikisource uses its supported MediaWiki API because the export website may require interactive access. Check each edition's rights for your country; Gutenberg's copyright status uses US law. Litteraturbanken and other sources remain deferred.

## Storage, recovery and backup

Open **More → Storage and backup** to export a ZIP backup with EPUB files or metadata only, restore/merge a backup, scan stored EPUBs after library damage, repair prepared voice files, clear generated audio/page caches, or reset one book's guided/listening position. Restore preserves newer local reading progress and merges annotations. Each backed-up reading mode carries its own saved timestamp; restoring an older or partial backup preserves newer positions and modes it does not contain. A metadata-only backup requires its EPUBs to be imported first. Backup export and restore share limits of 5,000 books, 20 MB of metadata and 2 GB total, and exclude downloadable/regenerable models and PCM. Parsing depth/value limits reject pathological JSON before recursive parsing.

The first upgrade migrates the old library to a transactional SQLite database, with separate metadata, annotation and progress records. The exact original JSON remains preserved. A damaged record is isolated; recovery notices link to the storage screen. Recovering stored EPUBs can restore book access but cannot recreate annotations whose only copy was damaged—use a saved backup for those.

Note drafts survive rotation, home/details scroll context is retained, active filters are visible, and shelf deletion/reset require explicit user confirmation. Removing a book stops its narration and clears its imported copy, annotations, navigation and all saved reading/listening modes.

## Included

- Mihon-style Library/History/Browse/More navigation, compact/comfortable cover grid or list, search/filter/sort/display sheets, persistent shelves and selection actions.
- EPUB book details with real chapter navigation, annotations and resume/read-aloud actions.
- EPUB chapters, embedded illustrations, publisher formatting, page turns or continuous scrolling.
- Saved reading positions, chapter navigation, a progress slider, and bookmarks.
- Full-book text search, highlights, notes, and text export of annotations.
- Paper, Sepia, and Night themes; text size, typeface, line spacing, and margins.
- Optional volume-key page turns. Screen awake: Device timeout, 2/5/10/15/30 minutes after interaction (10 minutes by default), or Always. Running guided reading keeps the visible page awake. Rotation preserves the deadline; narration alone does not renew it.
- Six configurable tap layouts, zone inversion, reading direction, animations and per-book navigation overrides.
- Rendered **Page X of Y** counts that adapt to typography and viewport size. Continuous mode labels equivalent viewport pages as **Screen X of Y**. A hidden renderer is created only for missing counts. Counts are saved per chapter, retained if interrupted, and reused across colour changes. Cache reads are immediate; measurement settles fonts/images and full geometry without fixed waits on every chapter. The corrected consistent vertical padding triggers a one-time recount when upgrading from older layout keys. Current-chapter pages appear while remaining chapters are counted; whole-book totals appear when complete. Measurement pauses during narration/backgrounding.
- **Guided reading**: a four-word moving window (customizable 1–8), with a uniform contiguous band including the spaces between words, nonoverlapping groups or rendered lines advance at WPM or CPS, with WPM presets 150/200/225/250/275/300/350/400/450/500, CPS presets, custom speed, pause/resume and an independent saved word. Page changes keep the current word visible. Manual navigation and backgrounding pause the guide; illustration-only resources pause for viewing.
- Tap the page/screen counter for cached word totals, words before/remaining from the visible page, estimated reading time, real contents/chapter distribution and available page ranges. Opening this sheet does not restart pagination. Counts include readable headings and retained notes; chapter boundary pages can overlap.
- Moving word windows retain overlapping words through each advance, with unchanged color/opacity and no temporary one-word frame. The persistent paint layer updates the full range in one animation frame.
- Narration rates span 0.5–3× with 2.5×/3× presets. Supertonic applies pitch-preserving playback to its 1× waveform; Kokoro keeps native speed generation. Higher speeds consume the buffer faster and can require buffering on slower devices. Unsupported output routes report an error and retain the previous working speed.
- **Read aloud**: optional downloaded Supertonic 3 English/Danish (ten voices), Kokoro English (British Emma and American Heart) or installed offline English Android voices. Remembered backend/voice/rate, independent listening cursor, following/highlighting, and foreground media controls for background/screen-off listening.
- AI playback saves the presentation-clock audio sample, caches the current passage and prepares bounded upcoming passages. Pausing and reopening resume the cached audio rather than estimating a word from elapsed time. Cache eviction regenerates audio; a differing waveform repeats the current passage rather than applying an unverified sample offset. Changing language or voice repeats the current passage safely. Supertonic generates at 1× and applies pitch-preserving playback speed; rate changes reuse its waveform and source-sample position.
- Duplicate detection, invalid-file checks, removal confirmation, and transactional library storage, bounded import validation and quarantined recovery copies.
- An original sample EPUB with English and Danish passages.

## Scope and privacy

Version 0.9.0 supports unprotected EPUB and local English and Danish neural speech. PDF, MOBI/AZW, DRM, cloud sync, accounts, and an ebook store are not implemented.

Choose **Guided reading** to set WPM (equal time per word) or CPS (time based on each word's characters and following whitespace). **Resume guided reading** uses its saved word; **Start from here** begins at the current visible passage. Previous/Next skips a sentence by default, with a separate paragraph option. Focus mode still controls automatic pacing. A direct Stop button clears active playback. A compact player remains below the text even with ordinary reader controls hidden. It exposes previous/next focus, pause/resume, pace and reading actions. Optional punctuation/paragraph pauses, long-word weighting, a countdown and 1/3/5-minute practice sessions show words, time and actual WPM. Gradual practice increases add 25 target WPM each minute. Self-assessed understanding is a reflection, not an objective comprehension score. RSVP remains a later feature.

Choose **Read aloud → Resume listening** for the separate listening position, or **Read from here** to move it deliberately. Closing the controls does not stop narration. Browsing a different page pauses listening and preserves its bookmark. Highlighting/following is always enabled; old disabled preferences and backups migrate on. Resume returns to the listening bookmark. Accepted Resume/Read from here requests close the panel immediately, with Loading model / Preparing speech / Buffering audio and Cancel on the reading page. Voice changes retain the spoken passage. Supertonic rate changes retain the audio position. The compact player offers line/sentence/paragraph navigation, replay sentence, page start, chapter navigation, Stop and settings. Voice settings include preview, direct speed presets, sentence/paragraph pauses and a 5/15/30/60-minute or chapter-end sleep timer. Android engines with range callbacks save word progress; engines without them safely repeat the current sentence on recovery. Use **Android Speech settings** to enable a speech engine and install English offline voice data first. Quality and available voices depend on that engine; Folio only selects voices advertised as installed and offline.

AI following highlights a passage, since the neural engines supply no trustworthy word timestamps. Guided WPM/CPS reading retains its separate word highlight. AI checkpoints use AudioTrack presentation timestamps when supported; fallback routes use a delayed render estimate and can repeat a short tail. Checkpoints are written about once per second and immediately on pause/stop; an abrupt process kill can repeat the last second. Ready lookahead PCM enters the same AudioTrack FIFO before the preceding passage completes. Its cursor/callbacks activate only after preceding presentation completion. Pause or seek discards future queued audio. Audio synthesis and prefetch never advance the listening position. Inference uses up to four CPU threads for Kokoro and two for Supertonic, with bounded passages and audio caching. A slow device may still need buffering; neural model resources are released after 60 seconds paused. PCM is health-checked and checksummed, and checkpoint writes are coalesced and ordered across service restarts. Audio focus loss and headphone disconnection pause narration.

Books and reading data are stored in the app's private storage. The app uses Internet access for requested GitHub update checks/downloads and requested book-source browsing, covers and downloads, and requested voice-pack/sample downloads. Providers see these requests and your network address. Public provider APIs receive no GitHub publishing token. Downloaded EPUBs and converted works remain available offline; no account is needed. Its EPUB WebViews block network loads from construction; local book content remains offline. It includes no ads or analytics. Removing a book deletes Folio's copy and annotations; the original selected file is retained. Uninstalling or clearing app storage removes the local library. Use the full backup export before uninstalling/clearing storage or moving devices; text-only notes export cannot restore anchors or listening positions. Opening an external link launches your chosen browser.

The user-provided novels are not bundled in the app or source archive. They were used only as local test fixtures. Third-party credits and license texts are bundled and can be read under **Library menu → About Folio → Licenses**.

## Version history and updates

Open **More → Version history** for offline notes on every version. **More → App updates** checks the public `TyranitarTar/Folio` GitHub releases only when requested. Downloads show progress and may continue in the background. Folio verifies the release checksum, APK application ID/version and installed signing key, stores a verified private copy, then opens Android's installation confirmation. Android may ask you to allow installation for Folio. The first v0.7.1 install is manual; future signed versions can be downloaded from inside the app. No GitHub token is stored on the device. A repository with no published release reports that state rather than claiming the app is up to date. See GITHUB-RELEASES.md for publishing instructions.

Native binaries and model weights remain unchanged; model data has moved from APK assets to optional verified downloads. The matching native source archive is byte-identical to v0.7.1 and retains the original native build notes; v0.8 integration changes are in the application source archive.

## Build from source

Open this directory in Android Studio. Use JDK 17 or 21, install SDK platform 35, and let Android Studio create `local.properties` pointing to your SDK. The Gradle wrapper is included and pins Gradle 8.11.1.

Run `python bootstrap_ai.py` and `python bootstrap_supertonic.py` first (Python 3.10+). It downloads SHA-256-pinned sherpa-onnx 1.13.8 Android libraries, the Kokoro v1.0 FP32 model and the exact Kotlin JNI API. The second script restores the pinned Supertonic INT8 model pack with SHA-256 checks. Large model/native binaries are deliberately excluded from the application source ZIP and restored by this script. License texts are already included. `collect_ai_sources.py` collects exact native sources and their licenses; `Folio-native-source-0.9.0.zip` accompanies this release with native build scripts and source dependencies. Application code is distributed under GPL-3.0-or-later because the bundled phonemizer includes eSpeak NG; individual components retain their own licenses. Kokoro weights use Apache-2.0; Supertonic 3 weights by Supertone use OpenRAIL-M (including its use restrictions), with separate MIT conversion licensing. Full texts and attribution are bundled under Licenses and in the source archive.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest lintDebug
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`.

To sign a release, run `python prepare_signing.py` once with a JDK's `keytool` in PATH, then `./gradlew.bat assembleRelease`. The private key and passwords are stored in the ignored `signing/` directory. Preserve this directory privately to sign compatible updates. It is deliberately excluded from the source archive. The APK delivered with this project was signed with the key retained in the original workspace.

Release output: `app/build/outputs/apk/release/app-release.apk`. Debug builds use the separate package `app.folio.reader.debug` and can coexist with the release; their libraries are separate. Signed releases use `app.folio.reader` and the retained release key for compatible upgrades.

`build.ps1` is a convenience for the downloaded toolchain in the original workspace. Source recipients can use Android Studio or the wrapper instead.

## Validation

Release verification is in `Folio-0.9.0-validation.json` and `Folio-0.9.0-review.md`. The release validation JSON records the current JVM/Android checks and lint results. The local Android 15/API35 x86_64 emulator verifies frame ordering, sample checkpoints, UI, reflow and signed upgrade preservation. No physical Galaxy S10 or Bluetooth device is connected; emulator callbacks do not prove audible endings, speaker quality, battery life or thermal behavior.

New regression tests cover legacy-library salvage, typed portable backup round trips, repeated passages/character offsets, stale reader/service writes, deletion/reimport cleanup, malformed shares, corrupt/legacy PCM, model integrity and memory budgets. Android tests add actual AtomicFile repair, backup restore, draft rotation, all requested WPM presets and guided storage-failure recovery.

JVM tests cover real EPUB import/search, metadata/cover handling, persistence, extracted word and sentence coverage, independent durable cursors, corrupt-state handling, tap-zone geometry, word timing, rejected stale speech callbacks, presentation timing, persistent FIFO handoff, pause/resume, future timestamps, invalid PCM and cache eviction. Android instrumentation checks illustrated resources, bookmarks/themes/reopening, rendered page counts and taps, viewport changes, guided-word recovery, the installed speech engine, real Kokoro and Supertonic JNI synthesis in English/Danish, ten Supertonic voice styles, fixed-seed regeneration, cold continuous-playback handoffs with one live AudioTrack, pause during replacement preparation, Mihon home tabs/shelves/display preference recovery, a visible-word Read from here check, saved sample recovery after service restart, background playback and paused voice/backend changes.

```powershell
.\gradlew.bat connectedDebugAndroidTest -PfolioBundledVoices=true
```

The illustrated-book test is optional without an external fixture. To run it too, place an EPUB in the debug app's private files directory and supply `-Pandroid.testInstrumentationRunnerArguments.externalEpub=/data/user/0/app.folio.reader.debug/files/test-input.epub`. User-provided books are not included in the source or APK. The speech test exercises real callbacks when an installed offline English voice is available, otherwise it verifies the missing-voice path.

The original sample asset is already included. Regenerating it requires Python, Pillow, and the Windows Georgia/Segoe UI fonts (`make_sample.py`). `runtime-dependencies.txt` records resolved runtime versions; `collect_notices.py` is a helper for the original dependency cache. Normal builds use the already collected notices.

For development-only model fixtures, use `-PfolioBundledVoices=true` with a debug build. Release builds always exclude model data. The opt-in `VoiceSampleGenerationInstrumentedTest` generates authentic AAC/M4A samples from the local models; run it with `-e generateVoiceSamples true`. `build_voice_packs.py` produces deterministic data-only archives and the pinned catalog; `validate_voice_packs.py` checks their complete contents and hashes. Runtime downloads use the immutable `voice-packs-v1` GitHub infrastructure release, which is a prerelease and never the latest app update. Ordinary instrumentation does not run sample generation or real download acceptance unless explicitly requested.

For explicit thin-APK acceptance, run VoicePacksInstrumentedTest with `thinVoicePacks=true` after upgrading an already prepared development installation. Remote preview checks require `networkVoiceSamples=true`; real cancel/resume/install acceptance requires `downloadVoicePacks=true`. These are opt-in to avoid unexpected downloads in offline tests.
