# Folio 0.17.2 — direct notebook and readable release notes

14 November 2026. Implementation approved by the user.
Baseline 0.17.1 / code 24; current patch 0.17.2 / code 25.
Use the standing preference: Sol High, no subagents.

See [design research and parity matrix](RESEARCH-0.17.2-PLAYER-PARITY.md).
Animal Crossing, Café redesign, Studio and new voice models are outside this patch.

## Findings

The current flow is Player → Notes sheet → Write a note → dialog → Save.
Editing an old note also requires a separate icon/dialog. Notes are separate
records, each limited to 2,000 characters, with 100 records per book. That model
does not match the requested continuous notepad.

App updates already requests GitHub's latest release, not every prior release.
It renders the returned body as one plain text block. When that version is
already installed, AppUpdateModel drops the release object entirely, so the
notes disappear. VersionHistory uses a separate hand-written, often technical
summary list; it can drift from the release body.

## 1. One tap opens a real book notebook

User flow: Player → Notes → full-page editable notebook, scrolled to the end,
caret on a fresh `- ` line and keyboard open. No intermediate list, Add button,
dialog or required Save tap.

Example layout:

    Back   Book title · Notes                         Copy all

    - Earlier thought about the chapter.
    - Another observation.
    - |                 (caret; keyboard visible)

- One continuous plain-text document per audiobook/FolioBook, with ordinary
  selection, replacement, cut/copy/paste and undo/redo.
- Enter continues a non-empty `- ` list item. Enter on an empty bullet can end
  the list; normal paragraphs and multiline pasted text remain possible. Never
  rewrite every pasted line or force bullet syntax throughout the document.
- Explicit opening starts a new entry at the end. Reuse an already-empty draft
  line instead of accumulating empty bullets on repeated visits. Opening and
  leaving without typing must not litter the saved document with `- ` lines.
- Auto-focus and keyboard opening happen once per explicit navigation action,
  after existing text has loaded. Rotation/recreation restores the same draft,
  selection and scroll position rather than appending another bullet.
- Only scroll to the end on entry/new-line actions. Users can edit older notes
  without being dragged back to the bottom on every autosave or playback tick.
- Audio keeps its current playing/paused state. Notes never seek, restart,
  implicitly pause, or rewrite the listening cursor. A small play/pause strip
  may remain above the keyboard if it fits; it must not crowd the editor.

## 2. Small toolbar, reliable editing and portability

- Back and Copy all are visible. Copy all copies the full current document,
  including actual unsaved typing, rather than only the selection or visible
  text. Do not include a never-used empty draft marker.
- Overflow can contain Share / Export .txt, Find in note and Insert playback
  timestamp. These stay secondary; writing must remain one tap.
- Optional timestamp insertion produces readable text, not mandatory timestamp
  cards or an accidental audio-navigation target. Playback history/bookmarks
  remain separate tools.
- Autosave after a short typing debounce; flush on leaving/backgrounding. Show
  a quiet Saving/Saved/Error state, and preserve a recoverable draft if committing
  fails. Use ordered atomic writes so older saves cannot overwrite newer text.
- Read existing content before permitting a save; a fast entry/exit must never
  overwrite a stored notebook with a transient empty initial value.
- Restore editor state through rotation, keyboard changes and process loss.
  Profile long documents beyond the current 2,000-character limit; set explicit
  safe bounds based on testing rather than silently truncating. Clipboard limits
  must produce a clear message and offer .txt export for oversized documents.
- Use stable book/edition identity, bounded storage and an independently saved
  text file/record. Do not rewrite the full audiobook catalog or listening
  journal on each keystroke.

## 3. Preserve the notes already written in 0.17.1

- Migrate user text in creation order into the book notebook, with compact
  chapter/time labels for the old notes. Keep original timestamps and captured
  context recoverable; do not flood the notebook with automatically captured
  long passages unless the user asks for them.
- Keep a verified legacy copy and a durable migration marker. Migration must be
  atomic/recoverable and idempotent across interrupted starts, so reopening never
  duplicates entries or loses the old notes.
- Retired timestamped records become migration/recovery evidence, not hidden
  active notes that can unexpectedly block a later FolioBook update.
- Plain notebooks survive refresh, relinking, same-edition snapshot replacement
  and signed upgrades. A regenerated audio timeline must not rewrite the user's
  free text or claim literal written timestamps were acoustically re-aligned.
- Keep genuine bookmarks/source-linked annotations on their existing safety path.
  Export .txt is useful portability, not a claim of complete audiobook-data backup.

## 4. Latest notes in App updates; a consistent version archive

- App updates always shows the most recent published release's notes with its
  version/date, even when it is already installed. Before a successful check,
  bundled installed-version notes can be shown with a correct label; cached
  remote notes need their version and last-check context.
- Separate latest-release metadata from download eligibility. Simply retaining
  today's `release` field would incorrectly expose Download for an installed
  version; do not introduce that regression.
- Keep Check / Download / progress / Verify / Install together at the top.
  Notes below contain only that release, rendered as readable headings/bullets
  and paragraphs rather than raw Markdown or a cumulative manual.
- About → Version history remains the archive: version/date and concise changes,
  expandable to the same full notes. It should not be a contradictory separately
  authored abridgement or another duplicate wall of text.
- Use one structured changelog source to generate compact history, bundled
  current notes and public release notes. Distinguish user-facing changes from
  optional technical qualification details. Do not copy old release histories,
  installer manuals, hashes or long testing logs into every update's body.
- Keep offline history useful. Preserve URL allowlists, APK size/hash/signature
  checks, cancellation and all existing download/install behavior.

## 5. Visual scope

Use Folio's existing typography and colours for this patch. A full-page quiet
editor and coherent toolbar solve the immediate complaint without a player-wide
reskin. The research recommends Voice's hierarchy/cover palette and Prologue's
compact secondary tools as future references. Retain compact covers, the stable
spoken-passage viewport, protected book progress and Mihon-style libraries.

## Approved implementation and qualification order

1. Implement the approved notebook flow, migration, storage and direct editor.
2. Add Copy all, .txt export/optional timestamp insertion and safe autosave.
3. Refactor release metadata versus download eligibility and changelog presentation.
4. Run focused unit/storage tests and emulator UI/lifecycle tests, then normal
   lint/build/signing/source checks. Produce a reviewable signed 0.17.2 APK.
5. Publish through the existing verified GitHub release workflow only under the
   authorized release scope, preserving the signing identity and user library.

Acceptance:

- One Notes tap exposes a focused editor and keyboard at the new final bullet.
- Copy all is one tap and includes all actual text. Paste/selection/undo work.
- Old notes survive migration; repeated and interrupted migration never duplicates.
- Rapid typing/back/open, rotation, backgrounding, forced termination and disk
  write failure preserve saved content/recoverable draft and ordering.
- Test long notes, Danish characters, emoji, large fonts, small phones, landscape,
  keyboard insets and Fold/Flip-style recreation without hidden toolbar/caret.
- Opening/editing/copying notes leaves audio playing/paused as it was and never seeks.
- FolioBook replacement and root refresh preserve notebook, cursor, marks and history.
- Up-to-date, newer-release, offline/cache, empty notes and legacy release bodies
  all display the correct version; no installed APK becomes offered as an update.
- Signed 0.17.1 → 0.17.2 upgrade preserves existing user data and migrated notes.

## Later feature-parity work

Do not bundle these into this small patch: portable audiobook metadata backup,
metadata/artwork correction. Across-book queue, widgets, structured character
lists, aggregate listening statistics and advanced sleep gestures are explicitly
excluded. Android Auto, Chromecast, equalizer/volume boost and silence skipping
are deferred by the user, not work for this release.
The accompanying matrix distinguishes existing features from missing or unverified
ones. Physical-device reliability remains a prerequisite to claiming mature
Smart AudioBook Player parity.


## Android → Apple TV: future assessment, not a 0.17.2 feature

Feasible through an AirPlay sender/bridge, rather than assuming Android Google
Cast targets Apple TV. A third-party sender could capture eligible Android audio,
or Folio could eventually implement a sender or TV companion. Receiver firmware,
background operation, reconnection, controls and read-along latency require a real
Apple TV test before promising support. No casting library or dependency added.

The AirMusic developer documents Android 10+ audio capture, Apple TV receivers
and app-dependent capture support: https://www.airmusic.app/ . Its site currently
says Play distribution is suspended, so it is feasibility evidence, not an
unqualified recommendation to install. AirServer's older Connect helper is not
suitable audio evidence: its support page explicitly says that app cannot send
audio (https://support.airserver.com/support/solutions/articles/43000516013).

Recommendation: if this becomes a priority, first test an external sender with
Folio and the actual Apple TV, then decide whether integrating a sender is worth
the maintenance. Keep the Android player independent of this optional feature.
