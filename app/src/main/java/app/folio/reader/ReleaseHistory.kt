package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class VersionNote(val version: String, val changes: List<String>)
internal val versionHistory = listOf(
    VersionNote("0.16.1",listOf("Larger audiobook controls, an explicit player lock and a dedicated playback-speed dialog with presets, fine adjustment and Apply.",
        "Chapter positioning moves into Chapters & position. Unlock the slider, adjust, then use the same button to Apply; redundant filenames are removed from the player.",
        "FolioBook passages remain visible between cues and across file transitions in a stable, scrollable text area. Screen-awake protection follows reader settings during read-along and after pause.",
        "Sleep tools add stop after chapter/passage, optional timed fade and timer extension. Saved highlights & quotes groups existing eBook highlights and saved FolioBook passages by book, with text/audio links.")),
    VersionNote("0.16.0",listOf("Safer audiobook controls: read-only book progress, speed-adjusted time remaining and separate 10-second/1-minute skip buttons. Chapter seeking requires Adjust and Apply.",
        "Chapters and bookmarks open on demand. Position recovery, sleep timer and extra tools move to the player menu; the current passage remains visible.",
        "Bluetooth/headset and interruption settings, with guarded resume after temporary audio-focus loss. Manual pause, stop and headphone disconnection cancel automatic resume.",
        "eBooks/Audiobooks share shelf controls and top-aligned 2–4-column layouts. FolioBooks have a small marker, list play buttons are larger and long-press menus use full-width rows.",
        "Delete source files with explicit confirmation, provider permissions and retry receipts. Remove-entry-only remains an explicit option. Reading positions follow matched FolioBook audio cues.",
        "Ambiguous linked playlists can be split into one book per file while retaining positions/bookmarks. Named folder artwork and larger bounded cover images are supported. Voice models are unchanged.")),
    VersionNote("0.15.0",listOf("Swipe through All, New, Reading and Finished in both libraries. Shelves move into the Reading title selector; compact navigation and fewer stacked rows leave more room for books.",
        "Audiobook covers load from embedded artwork or folder covers and are cached for the library, player and mini-player. Author/series folders are scanned as containers, with overlapping roots deduplicated.",
        "M4B embedded chapters, chapter selection and separate chapter/book seek bars. Listening uses clearer transport, speed, sleep and bookmark controls, with a recovery history.",
        "Configurable rewind/forward intervals and optional automatic rewind after longer pauses. Existing saved positions and original audio files are preserved.",
        "Version history is inside About Folio. Update discovery, download, progress and installation share one control area at the top. Existing voice models are unchanged.")),
    VersionNote("0.14.1", listOf("Audiobooks has a toolbar rescan for saved roots. Ordinary audio folders link original files without full-library copying/hashing; durations fill afterwards in the background. FolioBooks and explicit file imports retain copying.", "Linked media keeps positions/bookmarks across refresh and restart, with explicit root relinking. Source files must remain available; removing a Folio entry preserves originals.", "Reading and Audiobooks share cover grids, compact/list layouts and Filter/Sort/Display controls; audiobook preferences are saved separately.", "Matching book/player headers and a direct Library action, with background audio controls in the library. Reading replaces the Books label; the redundant Read + listen shortcut is removed.", "Page turns reuse verified chapter geometry; duplicate measurements are coalesced and chapter-boundary lookup avoids rescanning the document prefix. Exact new-layout book totals still calculate in the background.", "Shorter voice-pack cards keep samples and main actions together, with technical details expandable.")),
    VersionNote("0.14.0", listOf("Matching Books/Audiobooks status filters, manual read status, searchable audio library and a separate + import screen.", "Persistent audiobook root folders with refresh, relinking and nested CD ordering. Existing listening positions and EPUB annotations are preserved.", "Book and rendered chapter page counts, Copy and online Danish dictionary actions, and full saved passage display.", "Simpler guided reading and read-aloud controls, compact voice packs, permanent centre Café navigation and a one-second tap-zone hint.", "Configurable passage/lookahead/startup buffering and expanded on-device performance diagnostics with milestones, elapsed time, an optional audible live-generation trial and clearly labelled buffer simulation. Bookmark Cat launcher icon selected by the user.")),
    VersionNote("0.13.0", listOf("Separate Books and Audiobooks tabs. Narrated EPUBs are linked from both; reading history is now under More.", "Offline audiobook imports, chapter tracks, saved audio positions and bookmarks, playback speeds up to 3×, sleep timer and background media controls.", "Desktop-generated .foliobook imports preserve the original EPUB and use recorded sentence timing for highlighting, following and listening from selected text.", "A resumable desktop Røst 3 exporter prepares Danish narration outside the phone. Existing live TTS voice packs remain available.")),
    VersionNote("0.12.0", listOf("Optional Cat Café with Miso, three short animated treat interactions and six original Danish story chapters.", "Earn one treat per active reading/listening minute. Spend treats to unlock stories; no daily penalties or hoarding bonuses.", "A separate in-app test café can open every chapter, preview all interactions and exercise progression rules.", "Resumable stories, reduced motion and a separate café export/restore backup. New cat-and-book app icon.")),
    VersionNote("0.11.0", listOf("Experimental Plapre 3 Danish narration in an optional FP32 voice pack, with five voice samples before downloading.", "Plapre 3 shares the checkpointed player and background queue, with its own voice/thread choices, preparation ahead and on-device benchmark.", "Audiobooks now has a coming-later section on the library home screen. Audiobook playback and a joint UI cleanup are future work.")),
    VersionNote("0.10.2", listOf("Pico inference thread choices from 1 to 8, with an on-device performance test comparing generation stages and bounded sentence groups.", "Prepare chapter audio ahead while paused, within a bounded cache, then resume from the same listening bookmark.", "Voice storage shows installed models, resumable downloads and reclaimable files. Cleanup protects current and loaded voices, shared components and reading progress.", "Automatic Pico threads keep existing audio bookmarks. Changing the thread count safely repeats the current passage.")),
    VersionNote("0.10.1", listOf("Pico removes excess quiet padding around each passage while preserving short speech margins and pauses inside sentences.", "Pico keeps the existing background audio queue and GitHub-hosted voice pack; no model download is needed.", "Updated Pico audio-cache identity prevents reuse of padded clips. Older bookmarks repeat their current passage once to protect listening progress.")),
    VersionNote("0.10.0", listOf("Experimental Plapre Pico Danish narration with five voices in an optional download.", "Pico voice samples before downloading, offline previews, visible engine loading and cancellation.", "Pico uses the existing continuous player and saved listening position; speed changes reuse source audio.", "Plapre 3, Røst v3 and the reading cat game remain planned future work.")),
    VersionNote("0.9.0", listOf("A smaller app with optional Supertonic and Kokoro voice-pack downloads; existing prepared voices are reused.", "English and Danish samples let you compare voices before downloading. Installed voices also offer a local preview.", "Moving guided windows retain their overlapping words continuously; added 300 WPM.", "Narration speeds now include 2.5× and 3×.", "Tap the page counter for cached word counts, reading time and chapter distribution.")),
    VersionNote("0.8.1", listOf("Fixed an updater crash when download progress switches to APK verification.")),
    VersionNote("0.8.0", listOf("Reader screen-awake choices: device timeout, timed interaction window or always on.", "Guided word windows use one continuous, uniformly colored highlight.", "Project Gutenberg and Wikisource downloads add English and Danish works directly to the library.", "Downloads persist across reopening, with local text-to-EPUB preparation and preserved source attribution.")),
    VersionNote("0.7.1", listOf("Guided-reading skips move by sentence or paragraph independently of focus mode.", "Stop is directly available in both reader players.", "Faster cache-first pagination and adaptive chapter measurement.", "Version history and user-initiated GitHub updates with verified APKs.")),
    VersionNote("0.7.0", listOf("Persistent narration controls, visible loading and cancellation.", "Supertonic speed changes reuse source audio and saved samples.", "Word windows, groups, rendered lines and optional reading practice.", "Voice preview, sleep timers and always-on highlighting/following.")),
    VersionNote("0.6.0", listOf("Transactional library storage, portable backup and recovery.", "Incremental cached page counts and requested guided-reading speed presets.", "Reliability fixes for saved positions, annotations and voice assets.")),
    VersionNote("0.5.0", listOf("Mihon-inspired Library, History, Browse and More navigation for EPUB books.", "Shelves, book details, library display preferences and continuous narration improvements.")),
    VersionNote("0.4.0", listOf("Bundled Supertonic 3 English and Danish voices alongside Kokoro.")),
    VersionNote("0.3.0", listOf("Local Kokoro English speech with Emma and Heart voices.")),
    VersionNote("0.2.0", listOf("Guided WPM/CPS reading, native Android speech and typography-dependent page counts.")),
    VersionNote("0.1.0", listOf("Initial local EPUB library and reader with illustrations and saved progress."))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VersionHistorySheet(close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Version history", style = MaterialTheme.typography.headlineSmall) }
            items(versionHistory) { note ->
                Text("Folio ${note.version}", style = MaterialTheme.typography.titleMedium)
                note.changes.forEach { Text("• $it", Modifier.padding(top = 6.dp)) }
                HorizontalDivider(Modifier.padding(top = 16.dp))
            }
        }
    }
}
