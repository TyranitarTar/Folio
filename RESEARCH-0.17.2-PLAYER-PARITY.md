# Audiobook design references and Smart AudioBook Player parity

14 November 2026. Research and planning only. No 0.17.2 application changes,
build, device installation, model work or publication are authorized yet.
Current local Folio baseline: 0.17.1 / versionCode 24.

## Evidence and limits

The original E: download is no longer at its old path, but the archived copy is
present at `reference/smart-audiobook/reference.apk`: 12,289,214 bytes,
package `ak.alizandro.smartaudiobookplayer`, version 11.9.0 / code 301190.
It was inspected statically, not installed or executed. Existing DEX/component
inspection was checked alongside a fresh Android resource-table dump. Component
and resource presence support a feature inventory, not proof of exact runtime
behavior, reliability, scan speed or equality with the official distribution.
The supplied filename described a modified APK, so the official listing is an
independent cross-check rather than an assumption that this binary is pristine.

Fresh resources are recorded privately in
`audit/smart-player-resources-2026-11-14.txt`; prior evidence is in
`audit/smart-player-static-review.json` and related `smart-player-*` files.
Reference APK/disassemblies and screenshot files must stay out of release ZIPs.

The official Google Play listing was read on 14 November. Its feature list
confirms root-folder refresh, progress/status, cover download, bookmarks,
characters, shake-to-resume sleep behavior, recovery history, Cast and widgets.
The listing currently also advertises Gemini features; these are not proposed
for Folio's current scope.

## Visual shortlist — a design judgment, not an objective ranking

Developer screenshots were actually inspected for Voice and Prologue, and the
developer-supplied App Store screenshots for BookPlayer and Bound. Store assets
can lag the latest binary; this is not a hands-on assessment of those apps.

| Reference | Useful visual qualities | What fits Folio |
|---|---|---|
| [Voice](https://voice.woitaschek.de/) | Cover-derived colour, strong type hierarchy, clear primary transport, grouped secondary tools. The inspected player uses a large cover and expressive progress styling. | Best Android visual reference. Borrow restrained colour/hierarchy; retain Folio's compact layout and avoid copying the large art or decorative waveform by default. |
| [Prologue](https://prologue.audio/) | Quiet dark reading surface, warm accent, clear reader/player destinations and a compact secondary-controls tray. | Strong reference for FolioBook read/listen transitions and secondary controls. Its official site now also describes read-along; that capability should not be claimed as unique to Folio. |
| [BookPlayer](https://apps.apple.com/us/app/bookplayer/id1138219998) | Clear cover, chapter, progress and transport grouping; secondary tools are visually subordinate. | Reference for hierarchy and showing useful information without explanatory paragraphs. Official screenshots are marketing references, not a claim about every current platform screen. |
| [Bound](https://apps.apple.com/us/app/bound-audiobook-player/id1041727137) | Restrained library rows, readable cover/title/author alignment and a simple player. | Reference for sparse typography and orderly lists. Some supplied marketing screenshots use older phone frames. |

Recommendation: Voice for Android visual hierarchy, Prologue for reader/listener
organization, and Smart AudioBook Player for practical depth. Preserve Mihon-like
Folio libraries, the compact cover option, stable passage height and readable
controls. A comprehensive visual reskin is separate from the 0.17.2 notes fix.

## User scope decision — 14 November 2026

The user approved the notebook/release-notes patch and rejected across-book queue,
widgets, structured character tools, aggregate listening statistics and advanced
sleep gestures. Android Auto, Chromecast, EQ/boost and silence skipping are not
needed now. The matrix below is reference research, not an authorization or active
backlog for those items. Apple TV from Android is a separate future feasibility
question, assessed in ROADMAP-0.17.2.md.

## Feature-parity matrix

"Implemented" means code and existing qualification exist; it does not assert
equivalent reliability on every Samsung device/provider. No percentage parity
score is meaningful without a versioned checklist and the same runtime tests.

| Area | Reference evidence | Folio 0.17.1 | Remaining work / priority |
|---|---|---|---|
| Root folders, rescan, status filters, source-file playback | Official listing; folder/document/media APIs in APK | Implemented; metadata deferred and source identity preserved | Test the user's large folders, Mars Trilogy grouping and missing-cover cases on actual files. High acceptance priority. |
| Saved position, speed, chapters, skips, background/lock-screen playback | Official listing and APK playback components | Implemented; 0.17.1 adds ordered two-second saves and single-use Play intents | Longer S10/Flip7 playback, process loss, speed changes and interruptions still need physical-device acceptance. Core quality priority. |
| Headsets, disconnect, audio focus and call resume | Media/session code and Android Auto settings resources | Basic controls/settings implemented | Device-specific routing and reconnect tests; dedicated advanced remote mappings remain an audit item. Do not confuse MediaSession with Android Auto browsing. |
| Notes/bookmarks | Bookmark/character/note components and resources | Timestamped notes and bookmarks exist | User rejects current click-heavy note UI. Replace it in 0.17.2 with one directly editable notebook per book. |
| Listening history/statistics | History components; per-book/month statistics layouts | Dated sessions and recovery points exist | Aggregated daily/monthly/per-book charts and richer playback-source events remain. Lower priority than note usability and position reliability. |
| Sleep | Official shake-to-resume; sleep/force/settings resources | Duration, chapter/passage stop, fade, extension and optional rewind exist | Shake/gesture extension, schedules and finer behavior are not all implemented. Optional follow-up, off by default. |
| Sound processing | Equalizer classes; boost, pitch and skip-silence resources | Pitch-preserving speed exists; no user EQ, boost, independent pitch or silence-skipping pipeline | EQ/boost are useful later; silence skipping needs careful decoder/timeline work, especially FolioBook highlights, seek and saved-position mapping. Not a quick 0.17.2 toggle. |
| Home-screen widgets | Official listing and three AppWidget provider classes | No dedicated audiobook widget | Good later usability addition. Notification controls do not provide widget parity. |
| Android Auto | MediaBrowser service and Auto settings resources | No browsable Android Auto integration | Separate media-library/service and distribution/vehicle acceptance work. Higher effort; real car/Auto testing required. |
| Chromecast | Official listing; CastOptionsProvider | Absent | Separate network/media-serving scope for local files; do not equate Bluetooth support with Cast. |
| Book queue | BookQueueActivity/resources | Within-book track progression exists, no across-book queue | Useful follow-up, explicitly enabled; do not reintroduce auto-start on ordinary opening. |
| Covers and metadata editing | Official online covers; cover-related components | Embedded/folder covers and metadata cache exist | Manual artwork/title/author correction and optional online cover lookup need a focused audit. Do not overwrite users' edits on rescan. |
| Portable audiobook data | BookDataBackup and USB-backup resources; exact reference semantics unverified | General backup includes eBooks/preferences; it does not export the full audiobook catalog/cursors/journals | Metadata backup/restore for audio positions, bookmarks, notebook and history is a high-value future gap. Media copying can remain optional. Copy all / text export helps notes but is not full restoration. |
| Manual character list | Official list and CharacterDescription/CharactersActivity | No dedicated editor | A plain notebook can serve the immediate purpose. Separate structured character tools are optional. |
| Gemini recommendations/covers/characters, MP3→Opus utilities | Current official release text and APK identifiers | Not implemented | Explicitly outside recommended near-term parity target; neither is needed for reliable local listening. |
| FolioBook read-along, eBooks and local narration | Folio's own implementation | Implemented | Preserve these while improving ordinary audio features; they do not compensate for reliability gaps. |

Current later priorities are audio-data backup, manual metadata/artwork fixes and
physical-phone reliability/headset acceptance. Earlier research suggestions for
queue/widgets/Auto/audio enhancement/Cast/characters/statistics have been rejected
or deferred by the user; do not revive them as implementation tasks.
Literal feature-for-feature parity includes optional utilities; the useful target
is first dependable local-listening parity, followed by selected extensions.

## Primary sources read

- [Smart AudioBook Player — official listing](https://play.google.com/store/apps/details?id=ak.alizandro.smartaudiobookplayer)
- [Voice — developer site/screenshots](https://voice.woitaschek.de/)
- [Voice — source project](https://github.com/PaulWoitaschek/Voice)
- [Prologue — developer site/screenshots](https://prologue.audio/)
- [BookPlayer — developer listing](https://apps.apple.com/us/app/bookplayer/id1138219998)
- [Bound — developer listing](https://apps.apple.com/us/app/bound-audiobook-player/id1041727137)
- [Listen Audiobook Player — developer listing](https://play.google.com/store/apps/details?id=com.acmeandroid.listen) was checked as a broader audio-processing reference, not ranked from uninspected screenshots.
