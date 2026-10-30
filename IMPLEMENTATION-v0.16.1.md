# Folio 0.16.1 — player controls and read-along

30 October 2026. Version code 22, original application and signing identity.

The audiobook player uses larger transport targets spread across the available
width. A visible lock disables player transport, seeking, speed and settings;
unlock requires a deliberate confirmation. Android/headset controls remain
available. Book title and Chapter X of Y identify the recording; the redundant
track filename under the author is removed.

Speed opens a dialog with 0.5–3× presets, 0.05× adjustment, reset and Apply/Cancel.
Chapter selection and position share the Chapters & position sheet lower in the
player and in its menu. The position action changes from Unlock to Apply in the
same place; adjusting the slider alone never seeks. Chapter-list jumps retain
confirmation and seek recovery.

FolioBook passages retain the last actually spoken cue across gaps, loading,
completion and file changes until another cue starts. The bounded text viewport
scrolls internally, crossfades new text and reserves stable height. Switching
books clears retained text. The visible linked player shares the reader's
device/timed/always awake policy: timed mode protects playback/buffering and
grants the configured interaction window after pause (ten minutes by default).
Leaving/backgrounding clears the window flag; manual device locking is respected.

Sleep tools include timed stopping, optional last-ten-seconds fade, five-minute
extension and stopping at the current chapter or a reliable current passage.
Boundary stopping pauses rather than automatically opening the next file.

More → Saved highlights & quotes groups existing highlights by book. The player
can save its displayed passage into that same annotation collection, retaining
EPUB location and audio edition/track/time. Source text remains untruncated.
Opening quote text pauses narration for that book and opens the saved location,
without immediately following the currently spoken passage. Saved quotes reopen
text/audio and support confirmed removal of the original
highlight. Ordinary eBook selections still use Highlight; an available matching
audio location is attached when it can be resolved. Optional mark fields are
backward compatible with existing catalogs and backup serialization.

English live speech-to-text is an evaluated idea only. See
[feasibility assessment](ENGLISH-LIVE-READALONG-IDEA.md). No recognition runtime,
model download, microphone feature or cloud integration is included. Studio's
unified queue and incremental FolioBook output remain separate saved tasks.

## Verification

Qualification passed: 209 JVM tests, one existing platform skip, lint with no
errors (60 warnings), and 15 final-build emulator cases. Checks include lock and
rotation, speed Apply/Cancel, retained gap text, quote saving/reopening, screen
flags, chapter/passage stops, timer extension, M4B seeking, audio focus, actual
1.5×/3× completion and existing library/playback controls. Player and quote
screenshots were visually reviewed. The same-key signed 0.16.0 → 0.16.1 upgrade
preserved all 14 captured durable files; the installed APK matches the final
release hash. Detailed evidence is in Folio-0.16.1-validation.json.

Physical-phone listening,
Bluetooth, battery/heat, unusual providers and actual Mars Trilogy files/covers
remain user/device acceptance cases; emulator checks do not establish them.
