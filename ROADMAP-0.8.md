# Folio 0.8 plan

Updated 13 October 2026. Planning only; the released APK remains 0.7.1. The owner is handling GitHub publication.

## Goals and recommended scope

Make reading comfortable without frequent screen taps, and extend Browse with book discovery and downloads. Limit 0.8 to two built-in source adapters: Project Gutenberg and Wikisource. An installable extension system is unnecessary for the initial release: it introduces a separate executable-code, compatibility and distribution lifecycle.

Agreed source-count limit: two. Recommended 0.8 scope: screen-awake policy; Browse → Sources and Downloads; Project Gutenberg and Wikisource with English/Danish choices; robust EPUB download/import and supported TXT/book-HTML conversion. Use Gutenberg's downloadable editions and Wikisource's EPUB export where available. Verify complete-book exports before offering Download. Standard Ebooks, custom OPDS catalogs, Calibre/Kavita and Internet Archive are deferred. Litteraturbanken remains relevant only if a useful collection of Danish-language EPUBs is verified; Swedish-only availability does not qualify. Do not silently expand the release beyond two sources.

## Screen-awake behavior

The current Appearance.keepAwake Boolean is surfaced in ReaderActivity.kt and sets FLAG_KEEP_SCREEN_ON. Replace this switch with a persisted reading-screen policy:

| Choice | Behavior |
| --- | --- |
| Device timeout | Android's normal inactivity timeout |
| 10 minutes after interaction | Recommended default; keep the visible book awake for ten minutes following input, then allow Android's normal timeout |
| Always while reading | Keep the visible book awake until leaving the reader or manually locking |

Offer 2/5/10/15/30-minute custom preset choices inside the timed setting. This is a minimum awake interval, not a promise to lock the phone exactly at minute ten; Android determines the final sleep time after the app releases the screen flag. Do not change the global device timeout or force-lock the phone. Android documents the supported activity flag and its background behavior: https://developer.android.com/develop/background-work/background-tasks/awake/screen-on

Count touch, scroll, page-turn keys and reader-control interaction as input, including interactions inside Readium child views. Automatic highlighting, synthesized passages and pagination work must not reset the inactivity timer. Use monotonic time and retain the deadline across rotation; cancel background timers safely and restore the current policy on foregrounding. Clear the flag outside the reading page. Existing explicit keep-awake-on preferences migrate to Always; other readers receive the new ten-minute default.

While guided reading is running in the foreground, keep the screen on so the moving highlight remains visible. Pause, Stop or practice-session completion returns to the chosen policy using the last real input deadline. Ordinary narration follows the chosen screen policy; it can continue through the existing audio service with the screen off. Manual screen lock always wins. Do not wake or unlock the device automatically. Narration's sleep timer continues to control audio independently.

Acceptance: short system timeout cannot interrupt the protected interval; touch and page keys extend it; automatic reader events do not; rotation cannot restart the deadline indefinitely; pause/Stop/backgrounding release the appropriate protection; listening and saved positions survive screen-off. Include a physical Galaxy S10 check because manufacturer power behavior is not fully represented by emulator tests.

## Guided-reading highlight

User-requested 0.8 change: the four-word moving/trailing window must use one uniform highlight color and opacity for every word. Remove the per-word fading/shading gradient. Fill the spaces between highlighted words with the same color so the window reads as one continuous band rather than alternating colored and blank patches. Apply this behavior to custom window sizes as well; retain the existing timing, word order and saved guided position.

When the window wraps, draw one contiguous highlight segment per rendered line, covering only the selected text and intervening spaces. Do not fill unrelated text or the whole rectangle between lines. Use a readable uniform highlight for Paper/Sepia/Night and preserve punctuation, text selection and saved annotations. Acceptance: inspect four-word and custom-size windows at normal/large fonts, across line/page boundaries and in all themes; verify no alternating gaps or opacity steps and no position/timing regression.

## Book-source design

Preserve Library/History/Browse/More. Browse gets Sources, Downloads and existing Local import. The required flow is Source → Search or categories → Book details → Download in Folio → Import or convert → Add to Library → Read. Supported sources must complete that flow inside the app; a website shortcut or browser handoff does not qualify. Show title, author, language, cover when available, description, source and available formats. Prefer EPUB with images, preserve the original EPUB/license, and show unsupported formats clearly. A provider's Download action must acquire a real supported book; purchase, sample, loan and website links receive distinct labels.

Format handling for 0.8:
- EPUB: validate and import directly, preserving publisher content and illustrations.
- Plain text: convert locally to EPUB with correctly decoded Danish characters, paragraphs, metadata and navigable sections. Infer chapters only when reliable; otherwise retain a simple reading order rather than inventing titles or rewriting the text.
- Provider-supplied book HTML or an HTML package: convert locally to EPUB, preserving headings, paragraphs, footnotes, supplied illustrations and license notices. Use a provider-specific complete-book manifest when multiple files are involved; do not treat an arbitrary webpage as a complete book or crawl unrelated pages. Package supported assets for offline reading and report missing required content rather than silently producing an incomplete edition.
- PDF/scanned images, MOBI/AZW and DRM-protected files: no conversion promise in 0.8. A source with only these formats does not qualify for the supported download list.

TXT conversion is feasible with moderate effort; complete-book HTML conversion needs more validation of structure and assets. EPUB-first selection minimizes conversion and preserves fidelity. Conversion runs on-device in background work without a remote service, with a visible Preparing book stage and cancel/retry. Retain the downloaded original and origin metadata privately for recovery; bound temporary storage and clean up failed jobs. Validate the resulting EPUB before atomically adding the book. Conversion must not rewrite, translate or summarize the text. Imported/converted books must support the existing offline reader, TTS, guided reading, page counts, annotations and backups.

OPDS is a standard for catalogs and acquisition links; it is useful infrastructure rather than a requirement that every provider use the same protocol. Support common OPDS 1.x Atom feeds and OPDS 2 JSON behind one source interface, with paginated results and source-advertised search. Keep provider IDs and stable book IDs, origin URL, language and rights metadata. See https://opds.io/ and https://specs.opds.io/opds-2.0.

Fetch catalogs only when opened/refreshed. Cache results and covers, debounce search, respect provider request limits and Retry-After, and show useful unavailable/offline states. Cached browsing must distinguish cached results from newly fetched ones. Downloaded books remain usable without the source or a network connection. Download failures must never alter a user's existing library.

Downloads have queued/downloading/converting/importing/complete/failed/cancelled states, bounded concurrency, visible progress and cancel/retry. Resume only if the server supports ranges and content identity is unchanged; otherwise restart transparently. Persist jobs across Activity recreation/process restart, download to a temporary file, convert supported non-EPUB formats when needed, validate with the existing bounded EPUB importer, then commit the library entry. Reuse content-hash duplicate detection and preserve annotations/progress for an existing copy. For converted books, use the source content hash and conversion revision as well as the generated EPUB hash; ZIP timestamps must not create false new editions. Also detect repeat downloads by source/book ID. Never replace an existing edition silently.

Catalog URLs, redirect destinations and downloaded files are untrusted. Restrict schemes to HTTP(S), use HTTPS by default, bound feed/cover/file sizes, sanitize descriptions, disable XML external entities, and do not execute source-provided scripts. User-configured local HTTP servers need an explicit per-server opt-in. Authenticated feeds require separate credential storage and redirect rules; never put their tokens in logs, public backups or unrelated-host requests. Reader EPUB WebViews retain their offline network blocking; source browsing/downloading uses a separate network layer.

## Sources and feasibility

| Source | Value | Decision |
| --- | --- | --- |
| Project Gutenberg | Large classics collection, including Danish | First built-in adapter. Official OPDS/catalog data; no website crawling. Implement an adapter that can switch feed versions/endpoints. |
| Standard Ebooks | Carefully prepared English classics | Deferred beyond 0.8. Official OPDS instructions currently use a Patrons Circle login; verify available open-source application access before making a built-in public feed commitment. |
| Wikisource | Multilingual literature, including Danish | Second 0.8 source. WS Export produces EPUBs; prioritize complete works and show preparation progress. Validate export completeness/illustrations and metadata quality. |
| Litteraturbanken | Potential Danish-language EPUBs; availability not verified | Excluded from recommended integrations unless a useful Danish-language EPUB collection is confirmed. Books by Danish authors in Swedish translation do not qualify. If confirmed, verify integration/reuse conditions before automating access. |
| Calibre / Kavita | The user's own library on a PC or server | Deferred beyond 0.8 along with custom catalogs. Authenticated configurations need a compatibility/security pass. Acquisition does not imply reading-position synchronization. |
| Internet Archive | Additional openly downloadable editions | Later, restricted to openly downloadable supported EPUB files. Search results and a metadata record alone do not establish download availability or rights; scanned/OCR books need quality checks. |
| eReolen | Relevant Danish library discovery | Excluded from the supported source list: no verified complete in-app download/import route. Reconsider only with a documented authorized integration. |

eReolen feasibility follow-up, 13 October 2026: a documented integration route does exist at Publizon/Pubhub. The Library API guide v2.7 (September 2023) describes registered client/library credentials, loan creation, expiry and protected downloads. The published Web Reader guide v2.2.0 describes embedding a proprietary reader; the Offline API guide v1.0.1 describes encrypted book packages and supplier-issued keys. These documents establish technical possibilities, not Folio's access entitlement or that the older offline interface remains supported today. A provider-approved integration could use an embedded reader or an approved native/offline mechanism. Confirm current access, terms, text/TTS/guided-reading capabilities and compatibility with Folio's public source distribution before implementation. Protected loans need expiry-aware storage and must not enter ordinary unprotected EPUB export/backups. Do not promise permanent EPUB conversion. Leave the two-source 0.8 scope unchanged pending those answers.

Evidence: https://docs.pubhub.dk/LibraryApi/2.7/Pubhub_Library_Integration_Guide_v2.7_September_2023.pdf ; https://docs.pubhub.dk/Reader/Pubhub_Reader_2.2.0.pdf ; https://docs.pubhub.dk/OfflineApi/1.0.1/Publizon_Offline_API_v.1.0.1.pdf

Project Gutenberg explicitly supplies machine-readable catalogs and OPDS for applications. Its official page says an OPDS2 feed is being tested with access by contact, and XML OPDS is expected to be retired in 2027. Start with currently available official OPDS, verify the chosen download method against its access policy, and keep a migration/fallback path. Browser links and local import are a fallback if a feed becomes unavailable, not a substitute for a working direct-download launch feature. A cached catalog derived from the official metadata is an alternative if feed availability fails, but requires deciding how to refresh/distribute it. A third-party API would add a service dependency and must be assessed separately.

Retain provider rights notices and edition information. Gutenberg's copyright assessment is US-based; availability must not be presented as a guarantee of public-domain status in Denmark. Show supplied rights information without declaring every catalog item unrestricted worldwide.

Primary sources checked 13 October 2026:
- Gutenberg catalogs and OPDS transition: https://www.gutenberg.org/ebooks/offline_catalogs.html
- Gutenberg automated access: https://www.gutenberg.org/policy/robot_access.html
- Gutenberg rights: https://www.gutenberg.org/policy/license
- Danish Gutenberg catalog: https://www.gutenberg.org/browse/languages/da
- Standard Ebooks OPDS access: https://standardebooks.org/help/how-to-add-an-opds-feed-to-your-ereader
- Wikisource EPUB export: https://wikisource.org/wiki/Wikisource:WS_Export
- Litteraturbanken EPUBs: https://litteraturbanken.se/epub and https://litteraturbanken.se/urllista/epub
- Calibre server: https://manual.calibre-ebook.com/en/generated/en/calibre-server.html
- Kavita OPDS: https://wiki.kavitareader.com/guides/features/opds/
- Internet Archive developer resources: https://archivesupport.zendesk.com/hc/en-us/articles/360001495812-Developer-Resources
- eReolen supported access: https://ereolen.dk/

## Implementation order and release gates

1. Implement and test the awake policy and old-preference migration independently of appearance/reflow changes. Changing timeout must not pause guided reading or trigger page recounting.
2. Build Sources/Downloads UI, the source interface and OPDS parsing using deterministic fixtures, including relative links, search templates, pagination and unsupported loan/sample/DRM acquisitions.
3. Add persistent downloading, local TXT/book-HTML conversion and atomic import, exercising cancellation, interruption, offline retry, disk-full, invalid/oversized responses and duplicate downloads. Verify complete text, Danish encoding, chapter order, illustrations, footnotes and rights notices in converted fixtures. Verify progress/library/annotations remain intact.
4. Integrate Gutenberg against the currently permitted live endpoints. Complete at least one English and one Danish download-to-offline-reading test, including illustrations and narration. Release cannot call the source supported until this works.
5. Integrate Wikisource complete-work discovery and EPUB export for English and Danish. Test one complete book in each language through preparation, download, library addition and offline reading/narration; check chapter order and supplied images/footnotes. Exclude incomplete or unsupported exports from the Download action. Keep custom catalogs and other providers outside 0.8.
6. Run targeted regressions for narration, guide, pagination, imports, backups and signed upgrades from 0.7.1. Update offline version notes, increment versionName/versionCode, sign with the retained original key and attach matching source archives to the stable GitHub release. Verify the public release digest and actual update from 0.7.1 before claiming the in-app update path validated.

Screen policy is a small change. Catalog browsing plus reliable downloads is the main development work; source access and export quality are the uncertainty. No new speech model or voice-weight downloads are required. Catalog/download storage grows with selected books and caches; measure the build-size change after implementation rather than predicting an exact APK size.
