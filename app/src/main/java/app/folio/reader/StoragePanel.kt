package app.folio.reader

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class StorageUsage(val books: Long = 0, val models: Long = 0, val cache: Long = 0)
private suspend fun storageUsage(context: Context) = withContext(Dispatchers.IO) {
    fun bytes(file: File) = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    StorageUsage(bytes(File(context.filesDir, "library")), bytes(File(context.noBackupFilesDir, "local-voices")) + bytes(File(context.noBackupFilesDir, "voice-transfers")),
        bytes(File(context.cacheDir, "ai-audio")) + bytes(File(context.cacheDir, "pagination")) + bytes(File(context.cacheDir, "book-statistics")) + bytes(File(context.cacheDir, "voice-samples")))
}
private fun megabytes(bytes: Long) = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun StoragePanel(model: LibraryViewModel, changed: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val books by model.books.collectAsStateWithLifecycle()
    val recovery by model.store.recovery.collectAsStateWithLifecycle()
    val speech by SpeechController.state.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val usage by produceState(StorageUsage(), refresh) { value = storageUsage(context) }
    var includeBooks by remember { mutableStateOf(true) }
    var restorePrompt by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var resetKind by remember { mutableStateOf<String?>(null) }
    var bookMenu by remember { mutableStateOf(false) }
    val backup = remember(context, model.store) { LibraryBackup(context, model.store) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { model.storageOperation({ backup.export(it, includeBooks); "Backup saved." }) { refresh++; changed() } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.storageOperation({ backup.restore(it) }) { refresh++; changed() } }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Storage and recovery", style = MaterialTheme.typography.headlineSmall)
            Text("Library and annotations: ${megabytes(usage.books)}\nVoices and partial downloads: ${megabytes(usage.models)}\nGenerated audio, samples, statistics and page cache: ${megabytes(usage.cache)}")
            Text("Offline voices are optional downloads in More → Voice packs. Backups omit voice models and generated audio.", style = MaterialTheme.typography.bodySmall)
            recovery?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { model.storageOperation({ model.store.dismissRecovery(); "Recovery notice dismissed. Preserved files remain available." }) }) { Text("Dismiss recovery notice") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row { Checkbox(includeBooks, { includeBooks = it }, enabled = !busy); Text("Include EPUB files in backup", Modifier.padding(top = 12.dp)) }
            Button(onClick = { export.launch("Folio-backup.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export backup") }
            OutlinedButton(onClick = { restorePrompt = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
            OutlinedButton(onClick = { model.storageOperation({ val count = model.store.recoverStoredBooks(); "Recovered $count additional stored books." }) { refresh++; changed() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Scan stored EPUBs for recovery") }
            HorizontalDivider()
            Text("Free storage", style = MaterialTheme.typography.titleMedium)
            Text("Stop narration before clearing its cache or repairing voices. Your saved passage is retained; audio will be regenerated.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { model.storageOperation({ withContext(Dispatchers.IO) {
                SpeechCheckpointCoordinator.exclusive(context) {
                    AiListeningStore.clearCache(context)
                    listOf("pagination", "book-statistics", "voice-samples").forEach { name -> File(context.cacheDir, name).listFiles().orEmpty().forEach { it.delete() } }
                }
            }; "Generated audio, samples, statistics and page cache cleared." }) { refresh++ } }, enabled = !busy && speech.bookId == null, modifier = Modifier.fillMaxWidth()) { Text("Clear generated caches") }
            OutlinedButton(onClick = { model.storageOperation({ withContext(Dispatchers.IO) {
                File(context.noBackupFilesDir, "local-voices").listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
                    folder.listFiles().orEmpty().filter { it.name.startsWith(".ready") }.forEach { it.delete() }
                }
            }; "Voice repair requested. Files will be verified and prepared on next use." }) { refresh++ } }, enabled = !busy && speech.bookId == null, modifier = Modifier.fillMaxWidth()) { Text("Repair prepared voices") }
            HorizontalDivider()
            Text("Reset a saved mode", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { bookMenu = true }, enabled = !busy) { Text(books.firstOrNull { it.id == chosen }?.title ?: "Choose a book") }
                DropdownMenu(bookMenu, { bookMenu = false }) { books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = { chosen = book.id; bookMenu = false }) } }
            }
            listOf("guided" to "Reset guided position", "speech" to "Reset listening position").forEach { (kind, label) ->
                OutlinedButton(onClick = { resetKind = kind }, enabled = !busy && chosen != null && speech.bookId == null, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            TextButton(onClick = dismiss) { Text("Close") }
        }
    }
    if (restorePrompt) AlertDialog(onDismissRequest = { restorePrompt = false }, title = { Text("Restore and merge backup?") },
        text = { Text("Books and annotations are merged. Newer local reading progress is kept. The backup restores app settings; voice packs are prepared separately. A metadata-only backup needs its EPUBs already imported. Restore files up to 2 GB.") },
        confirmButton = { TextButton(onClick = { restorePrompt = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Choose backup") } },
        dismissButton = { TextButton(onClick = { restorePrompt = false }) { Text("Cancel") } })
    resetKind?.let { kind -> AlertDialog(onDismissRequest = { resetKind = null }, title = { Text("Reset $kind position?") },
        text = { Text("This clears only this mode's saved position for the chosen book. Annotations and ordinary reading progress are retained.") },
        confirmButton = { TextButton(onClick = {
            val id = chosen!!; resetKind = null
            model.storageOperation({
                if (kind == "guided") GuidedProgress.delete(context, id) { ReaderCursorStore(context).forget(id, kind) }
                else SpeechCheckpointCoordinator.deleteBook(context, id) {}
                "Saved $kind position reset."
            })
        }) { Text("Reset") } }, dismissButton = { TextButton(onClick = { resetKind = null }) { Text("Cancel") } }) }
}
