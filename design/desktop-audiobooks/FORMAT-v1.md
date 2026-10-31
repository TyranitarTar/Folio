# `.foliobook` companion bundle, version 1

`.foliobook` is a ZIP bundle that pairs the original EPUB with locally rendered chapter audio
and sentence cues. It lets a reader synchronize against the original publication without changing
its XHTML, adding spans, or moving existing anchors. It is a Folio companion format; it does not
claim to be an EPUB 3 Media Overlay.

## Archive layout

```text
manifest.json
book.epub
audio/chapter-0001.mp3
audio/chapter-0002.mp3
...
```

Audio and the nested EPUB are stored without ZIP compression. `manifest.json` is UTF-8 JSON. Every
archive path is relative, uses `/`, has no `..` segment, and names only files included in the bundle.
There are no remote URLs. The `book.epub` entry is the original source byte-for-byte; its SHA-256
must equal `sourceSha256`.

## Manifest

Required root fields are `format: "folio-audiobook"`, `version: 1`, `title`, `author`, `language`,
`sourceSha256`, `epub: "book.epub"`, `model`, and `chapters`. Exporters also include `textSha256`
for the normalized sentence cues. Additional fields may be ignored by
readers. `preview` marks intentionally incomplete trial exports; preview files use the
`.preview.foliobook` extension. A preview can contain an incomplete final chapter and must never
be presented as a completed audiobook.

Each chapter record has `file`, `title`, `durationMs`, and `cues`. `complete`, `sentenceCount`, and
`sourceSentenceCount` distinguish a full track from a trial track. There is one MP3 per chapter,
not one file per sentence. The encoder writes Xing/gapless metadata. Times are milliseconds on the
decoded, gapless MP3 timeline; readers should use the presented media clock and must not assume
that container byte position is elapsed time.

Each cue has `startMs`, `endMs`, `href`, `fragment`, and exact source quote `text`. Optional
`before` and `after` strings contain up to 80 adjacent source characters for text-quote disambiguation.
`cssSelector` is present when the sentence's containing block has no existing element ID. It is a
structural selector against the unchanged XHTML. `progression` is a normalized `0.0..1.0` position
within the spoken text blocks of that source document; it is a coarse fallback, not a replacement
for quote matching. `fragment` is the existing element ID or an empty string. `href` is the
archive-root-relative XHTML entry path, without the fragment, so it cannot contain traversal.

Cue intervals must satisfy `0 <= startMs < endMs <= durationMs`; cue starts are ordered. Cues mark
sentences only. There is no word-level alignment. A reader can locate a cue using the original href,
then the existing fragment, quote plus context, and finally the CSS selector or progression as
fallbacks. It should retain the cue text as the source quote and not rewrite the original EPUB.

## Incremental editions (Studio 0.6 / Folio 0.17)

The additive version-1 snapshot fields are `editionId` (`folio-` followed by a
64-character lowercase hexadecimal digest), positive integer `revision`,
`revisionId` (a 64-character hexadecimal producer content identifier), and
Boolean `incomplete`. An edition keeps the same source EPUB bytes. Every chapter
has a stable, unique `chapterId` and an `audioSha256` verified against its actual
file. Publish a complete, validated ZIP with atomic replacement, never a growing
ZIP that another process could import halfway through.

`generatedSentenceCount` / `totalSentenceCount` report passage-link coverage;
`generatedChapterCount` / `totalChapterCount` report audio coverage. A matched
original audiobook can have all its audio and only some linked passages.
Finishing the available audio of a partial generated book does not mean the
whole book is finished. `incomplete` and `preview` have separate purposes.

Revisions increase monotonically within an edition; old revisions are ignored
and conflicting data at the same revision is rejected. `revisionId` is opaque
to readers: JSON number canonicalization is not specified across languages.
Readers must still verify EPUB/audio digests and detect conflicting metadata.

Folio keeps the existing edition, annotations and listening settings. Stop
playback before updating that edition. Unchanged audio keeps its audio cursor;
corrected cues can update saved quote targets. Changed audio requires unique
source-passage anchors for every used cursor, bookmark, history or quote target.
Unmappable positions or removed/reordered chapters reject the update and retain
the previous edition. Durable rollback receipts protect interrupted imports.
Legacy bundles without edition fields remain readable as independent imports.

## Model metadata and privacy

`model` records the public model repository and pinned revision, the SHA-256 of the local model
component list, runtime version, CPU device, sample rate, generation settings, and optional voice
reference hash. The `.foliobook` contains no model weights, voice reference, local filesystem path,
source job file, or resumable WAV chunks.

Job plans and generated assets can contain the entire book text and audio. Keep them in the private
user job directory. Do not add job directories, model weights, bundles, original books, or audio to
source archives, app releases, or GitHub.

