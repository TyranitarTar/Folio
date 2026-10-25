# Smart AudioBook Player comparison and Folio adaptations

25 October 2026. Reference: the user-supplied APK, package
`ak.alizandro.smartaudiobookplayer`, version 11.9.0 / 301190. The APK was inspected
statically, without installing it. Folio changes use its own implementation.

## What was confirmed in the reference

Android SDK DEX inspection of the player's media wrapper confirms file-path
handling, `ContentResolver.openFileDescriptor` and
`MediaPlayer.setDataSource(Context, Uri)` for source playback. The APK includes
root-folder controls, folder/document traversal, `MetadataHolder` records and
SQLite transaction/query APIs. It also has playback-history, bookmarks, sleep
timer, playback-speed, auto-rewind and queue components. API/component presence
does not establish every runtime policy, timing or database schema; no speed
benchmark of Smart AudioBook Player itself was performed.

The important verified architectural difference: Folio previously copied and
SHA256-hashed every selected track, then opened every copied track for metadata,
before publishing an audiobook. This made root import proportional to the media
bytes, not just the number of folders/files. The root scanner already received
file names/sizes/timestamps but the import repeated per-file provider queries.

## Applied in this follow-up

| Area | Folio change / current parity |
|---|---|
| Library scan | Ordinary root-folder audio is indexed by persisted document URI. Media is not copied, hashed or decoded during discovery. Existing copied editions stay intact. |
| Reload | A rescan icon is on the Audiobooks toolbar. It scans saved roots, discovers additions, reports progress/results and supports cancellation. No root opens the add-folder controls. |
| Incremental refresh | Discovery fingerprints use existing directory metadata. Unchanged linked entries skip media reads, including providers without modification times. Such providers cannot reliably reveal an overwrite with unchanged IDs/metadata. |
| Stable identity | A linked title is identified by its root/folder rather than volatile timestamps. Added tracks remap positions/bookmarks by URI. Changed playlists are deferred while loaded in the player; removed/renamed tracks retain the old entry for deliberate repair instead of silently discarding marks. |
| Metadata | Titles appear first. Durations are read/cached afterwards in a cancellable background job which yields to narration. The first played track also supplies its decoded duration. Unknown totals are labelled explicitly. |
| Source playback | The player opens the granted original content URI. The source folder/device/provider must remain available. Linked entries remain in the catalog after access loss so positions can be recovered. |
| Root relinking | Explicit relinking can retain identity, positions, speed and bookmarks when one prior folder/title and its relative ordered track names match unambiguously. An uncertain match is not silently mapped. |
| Library presentation | Reading/Audiobooks share cover tiles, compact/comfortable/list modes, search and Filter/Sort/Display controls, with separate preferences. Imports stay behind +. |
| Leave player | Shared headers provide a direct Library action. Library audio controls keep pause/resume/stop available while playback continues. |
| Playback basics | Existing saved positions, track transitions, seeking, bookmarks, sleep timers, speed through 3x, background media controls and audio focus remain. |

FolioBook archives still require extraction and validation for their EPUB,
recorded audio and synchronized cues. Individual file imports and explicit
one-book folder imports retain private offline copying. Ordinary root-folder
links have a different availability contract and explain it in the import UI.
Removing a linked catalog entry removes Folio data, not the source media.

Catalog/position data is still local. The existing audio catalog format accepts
optional source-root/track-URI fields; older private imports retain their paths
and checksums. No voice model, native speech runtime or existing EPUB is changed.

## Useful next adaptations, kept separate from this release

- [ ] Optional automatic rewind after a long pause, adjustable and off by
  default, without rewriting the saved bookmark when simply opening a player.
- [ ] Folder artwork / embedded-cover discovery with a thumbnail cache.
- [ ] A visible listening history with recent seek/rewind points for recovery.
- [ ] A book queue, with deliberate controls for starting the next title.
- [ ] Configurable skip intervals, consistent across full player, mini-player,
  notification and headset actions.
- [ ] Measure large-library JSON/catalog writes before replacing them with
  incremental SQLite transactions. The reference's database APIs motivate the
  investigation; they do not prove a particular speedup for Folio.
- [ ] Consider seekable, uncompressed FolioBook audio without full extraction
  only after archive offset/length, provider seeking and corruption validation.

Raw static inspection is retained locally in `audit/smart-player-*`. Those
reference disassemblies/APK are excluded from Folio source/release archives.
