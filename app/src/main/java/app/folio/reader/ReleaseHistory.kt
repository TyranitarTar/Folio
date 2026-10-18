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
