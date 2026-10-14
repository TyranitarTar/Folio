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
