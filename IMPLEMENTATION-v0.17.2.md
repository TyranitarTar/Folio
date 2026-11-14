# Folio 0.17.2 — book notebook and release notes

14 November 2026. Version code 25, based on signed 0.17.1 / code 24.
Qualification and public release status are recorded in the separate output
validation/publication files, rather than inferred from this implementation note.

## Direct notebook

Notes in the player footer and menu launch a full-page editor directly. On explicit
entry, the loaded document gets a fresh `- ` line and keyboard focus at the end.
Rotation keeps the live ViewModel, selection and scroll state; it does not append
another entry. Enter continues a nonempty bullet, while Enter on an empty bullet
can end the list. Multiline paste and ordinary paragraphs are preserved.

Copy all includes the current document, not just selected/visible text. Undo/redo
have a bounded history; optional Find, Export .txt and Insert playback timestamp
are in the menu. None of these actions seek, start or pause the recording.

Each book has a separate versioned notebook.json. Saves are debounced 350 ms,
atomic and verified, with observation ordering to reject stale writes. Leaving or
backgrounding flushes the current document. A failed main commit attempts an
independent notebook-draft.json recovery file; opening chooses the newest valid
revision. Explicit Back remains in the editor if neither file can save, so Copy
all/export remain available. A storage failure combined with immediate OS process
termination cannot guarantee recovery of text that never reached disk.

The document limit is 250,000 UTF-16 characters with a 2 MiB serialized bound.
Near that limit, entry opens at the existing end so an added draft bullet cannot
block deletion. The native Android EditText owns scrolling, contextual selection/copy/paste and
caret visibility. Its own text-state saving is disabled; the ViewModel/file owns
large documents. Landscape uses a compact toolbar to retain space above the keyboard.
Copy all detects UTF-8 content above 500,000 bytes and recommends .txt export to
avoid silently truncating or overflowing the clipboard transaction. Undo history
is limited to 40 snapshots and two million characters. No notebook is put into
an Activity Bundle. Ordered atomic saves protect text; after actual process loss,
the editor reopens at a new final line rather than restoring an unsaved selection.

## Migration and library operations

Old 0.17.1 notes migrate in creation order, with compact original chapter/time
labels. Original records, timestamps and captured passages remain in a verified
notes-legacy.json recovery copy. Durable migrated IDs make retry idempotent.
Only after the notebook commit succeeds are the old active journal notes retired;
listening sessions and cleared-session tombstones are preserved.

Notebooks are independent of audio alignment. Same-edition snapshot updates copy
the notebook and legacy recovery file while holding its save lock through the
directory transaction. Text is never silently acoustically remapped. Playlist
splitting copies shared unstructured notes to each resulting book and retains
existing child text; notes cannot reliably be subdivided by audio track. Root
refresh and relinking continue using stable book identity. Removing the book
with its app data also removes its notebook: use export for a portable copy.

## Release notes

release-notes.json is the canonical bundled changelog. About Folio shows compact
version/date/summary rows with expandable full notes. release_notes.py generates
the GitHub release body from the same changes; historical records preserve the
previous in-app archive, with unknown dates omitted.

Latest remote metadata is cached in app-release-notes preferences, separately from
download eligibility and download cancellation. App updates shows remote latest
notes even when installed, plus their last-check time, or correctly labelled
bundled installed notes offline. A development build newer than the public release
shows its own installed notes. The primary update action remains at the top.
Existing repository URL, checksum, APK package/version and signing-key checks are
unchanged; retaining notes never makes an installed version downloadable.

## Scope and limits

No Studio, voice model, runtime, casting, queue, widget, character-list, statistics
or Café feature is added. The user explicitly excluded those player extras or
deferred them. Android-to-Apple-TV is assessed in the roadmap as an optional future
AirPlay sender/bridge path, not advertised as tested Folio support.

Automated qualification uses the existing Android emulator, not a physical Galaxy
S10/Flip7. Actual Samsung keyboard, Bluetooth/calls, heat/battery and Apple TV
compatibility remain device acceptance checks. No claim of full Smart AudioBook
Player feature parity is made.
