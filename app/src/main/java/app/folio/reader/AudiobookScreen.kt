@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun audioTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun openAudiobook(context: Context, id: String, autoplay: Boolean = false) {
    context.startActivity(Intent(context, AudiobookActivity::class.java).putExtra("audiobook", id).putExtra("autoplay", autoplay))
}

/** Audio is an independent library; linked EPUBs also remain ordinary Books entries. */
@Composable
internal fun AudiobookLibrary(store: AudiobookStore, epubs: List<Book>, read: (Book) -> Unit) {
    val context = LocalContext.current
    val entries by store.books.collectAsStateWithLifecycle()
    val playback by AudiobookController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var removing by remember { mutableStateOf<Audiobook?>(null) }
    LaunchedEffect(store) { runCatching { store.load() }.onFailure { message = it.message ?: "Audiobooks could not be loaded." } }
    fun importing(action: suspend () -> Audiobook) {
        if (busy) return
        busy = true
        scope.launch {
            try { val book = action(); message = "${book.title} is ready to listen" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "The audiobook could not be imported." }
            finally { busy = false }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importing { store.importAudio(uris) }
    }
    val bundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importing { store.importBundle(uri) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importing { store.importAudio(audiobookFolder(context, uri)) }
    }
    val visible = entries.filter { (query.isBlank() || "${it.title} ${it.author}".contains(query, true)) &&
        (filter == "All" || (it.bookId != null) == (filter == "Read + listen")) }.sortedByDescending { it.lastPlayed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Your listening library", style = MaterialTheme.typography.headlineSmall)
            Text("Audio files and narrated EPUBs, saved on this device.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { files.launch(arrayOf("audio/*", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import audio") }
                OutlinedButton(onClick = { bundle.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import narrated book") }
            }
            TextButton(onClick = { folder.launch(null) }, enabled = !busy) { Icon(Icons.Outlined.FolderOpen, null); Text("Import audiobook folder") }
            Text("Select a book’s audio files together, or import a .foliobook made by the desktop narrator.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)); Text("Importing and checking audio…", style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                label = { Text("Search audiobooks") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Read + listen", "Audio only").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            }
        }
        if (visible.isEmpty()) item {
            Text(if (entries.isEmpty()) "Import an audiobook to begin" else "No matching audiobooks", style = MaterialTheme.typography.titleMedium)
            Text("MP3, M4A/M4B, Ogg and WAV use the device’s audio decoder. Narrated books appear here and in Books.", style = MaterialTheme.typography.bodyMedium)
        }
        items(visible, key = { it.id }) { audio ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { openAudiobook(context, audio.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (audio.bookId != null) Icons.Outlined.MenuBook else Icons.Outlined.Headphones, null, Modifier.size(32.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(audio.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (audio.author.isNotBlank()) Text(audio.author, style = MaterialTheme.typography.bodySmall)
                            Text("${audio.tracks.size} ${if (audio.tracks.size == 1) "track" else "tracks"} · ${audioTime(audio.tracks.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { removing = audio }) { Icon(Icons.Outlined.DeleteOutline, "Remove audiobook") }
                    }
                    Text((if (audio.preview) "Preview · " else "") + (if (audio.bookId != null) "Read + listen" else "Audio only"), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { openAudiobook(context, audio.id, true) }) { Icon(Icons.Outlined.PlayArrow, null); Text(if (audio.lastPlayed > 0) "Resume" else "Listen") }
                        epubs.firstOrNull { it.id == audio.bookId }?.let { book -> TextButton(onClick = { read(book) }) { Text("Read") } }
                        if (playback.id == audio.id && playback.playing) Text("Playing", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item { Text("Audiobook audio and listening bookmarks are separate from EPUB ZIP backups. Keep your original audio or .foliobook file for reimport.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    removing?.let { audio -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove audiobook?") },
        text = { Text("Remove ${audio.title}, its local audio and listening bookmarks? The EPUB and its reading annotations will remain.") },
        confirmButton = { TextButton(onClick = { removing = null; scope.launch { runCatching { store.remove(audio.id) }.onFailure { message = it.message } } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

class AudiobookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("audiobook") ?: run { finish(); return }
        val store = (application as FolioApp).audiobooks
        setContent {
            val preferences = remember { HomePreferences.load(this) }
            val night = preferences.theme == "Dark" || preferences.theme == "System" && isSystemInDarkTheme()
            FolioTheme(night) {
                val books by store.books.collectAsStateWithLifecycle()
                var error by remember { mutableStateOf<String?>(null) }
                var loaded by remember { mutableStateOf(false) }
                LaunchedEffect(id) {
                    try {
                        store.load(); loaded = true
                        if (books.any { it.id == id } && (AudiobookController.state.value.id != id || intent.getBooleanExtra("autoplay", false)))
                            AudiobookController.start(this@AudiobookActivity, id, autoplay = intent.getBooleanExtra("autoplay", false))
                    } catch (e: Exception) { error = e.message ?: "The audiobook could not open." }
                }
                val book = books.firstOrNull { it.id == id }
                if (book != null) AudiobookPlayerScreen(book, store, { finish() }, { linked ->
                    startActivity(Intent(this, ReaderActivity::class.java).putExtra("book", linked).putExtra("recordedFollow", true))
                }) else Scaffold { padding -> Column(Modifier.padding(padding).padding(24.dp)) {
                    Text(error ?: if (loaded) "This audiobook is no longer in the library." else "Opening audiobook…")
                    TextButton(onClick = { finish() }) { Text("Back") }
                } }
            }
        }
    }
}

@Composable
internal fun AudiobookPlayerScreen(book: Audiobook, store: AudiobookStore, back: () -> Unit, read: (String) -> Unit) {
    val context = LocalContext.current
    val active by AudiobookController.state.collectAsStateWithLifecycle()
    val state = active.takeIf { it.id == book.id }
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(book.id) { mutableStateOf("Chapters") }
    var speedMenu by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val index = (state?.trackIndex ?: book.positionTrack).coerceIn(0, book.tracks.lastIndex.coerceAtLeast(0))
    val track = book.tracks.getOrNull(index)
    val position = state?.positionMs ?: book.positionMs
    val duration = state?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L
    val speed = state?.speed ?: book.speed
    var dragged by remember(book.id, index) { mutableStateOf<Float?>(null) }
    val elapsed = book.tracks.take(index).sumOf { it.durationMs } + position
    val total = book.tracks.sumOf { it.durationMs }
    Scaffold(topBar = { TopAppBar(title = { Text("Audiobook") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    }, actions = { book.bookId?.let { linked -> TextButton(onClick = { read(linked) }) { Text("Read book") } } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Icon(Icons.Outlined.Headphones, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                Text(book.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.headlineSmall)
                if (book.preview) Text("Preview · this recording does not cover the whole book", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.titleMedium)
                Text(track?.title ?: "Track", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
                Text("Track ${index + 1} of ${book.tracks.size}", style = MaterialTheme.typography.bodySmall)
                Slider(value = dragged ?: position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(), onValueChange = { dragged = it },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && state?.loading != true,
                    onValueChangeFinished = { dragged?.let { AudiobookController.seek(context, it.toLong()) }; dragged = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(audioTime(dragged?.toLong() ?: position)); Text("−${audioTime((duration - (dragged?.toLong() ?: position)).coerceAtLeast(0))}")
                }
                Text("Book ${audioTime(elapsed)} / ${audioTime(total)} · ${audioTime(((total - elapsed).coerceAtLeast(0) / speed).toLong())} remaining at ${speed}×",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { AudiobookController.previous(context) }, enabled = index > 0) { Icon(Icons.Outlined.SkipPrevious, "Previous track") }
                    IconButton(onClick = { AudiobookController.seek(context, (position - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Outlined.Replay10, "Back 10 seconds") }
                    FilledIconButton(onClick = {
                        if (state?.playing == true) AudiobookController.pause(context)
                        else if (state == null) AudiobookController.start(context, book.id) else AudiobookController.play(context)
                    }, Modifier.size(64.dp), enabled = state?.loading != true) { Icon(if (state?.playing == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state?.playing == true) "Pause audiobook" else "Play audiobook") }
                    IconButton(onClick = { AudiobookController.seek(context, (position + 30_000).coerceAtMost(duration)) }) { Icon(Icons.Outlined.Forward30, "Forward 30 seconds") }
                    IconButton(onClick = { AudiobookController.next(context) }, enabled = index < book.tracks.lastIndex) { Icon(Icons.Outlined.SkipNext, "Next track") }
                }
                if (state?.loading == true) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading audio…") }
                (state?.error ?: error)?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text("${speed}×") }
                        DropdownMenu(speedMenu, { speedMenu = false }) { listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f).forEach { rate ->
                            DropdownMenuItem(text = { Text("${rate}×") }, onClick = { speedMenu = false; AudiobookController.speed(context, rate) })
                        } }
                    }
                    TextButton(onClick = { timer = true }) { Icon(Icons.Outlined.Bedtime, null); Text(state?.sleepRemainingSeconds?.let { audioTime(it * 1000) } ?: "Sleep") }
                    TextButton(onClick = { scope.launch { runCatching { store.addBookmark(book.id, index, position) }.onFailure { error = it.message } } }) { Icon(Icons.Outlined.BookmarkAdd, "Add audio bookmark") }
                    TextButton(onClick = { AudiobookController.stop(context) }) { Icon(Icons.Outlined.Stop, null); Text("Stop") }
                }
                state?.cue?.text?.takeIf { it.isNotBlank() }?.let { text -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Text("Now reading", style = MaterialTheme.typography.labelMedium); Text(text, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Chapters", "Bookmarks").forEach { label -> FilterChip(section == label, { section = label }, label = { Text(label) }) }
                }
            }
            if (section == "Chapters") items(book.tracks.indices.toList()) { i ->
                ListItem(headlineContent = { Text(book.tracks[i].title) }, supportingContent = { Text(audioTime(book.tracks[i].durationMs)) },
                    leadingContent = { Text("${i + 1}") }, trailingContent = { if (i == index) Icon(Icons.Outlined.Headphones, "Current track") },
                    modifier = Modifier.clickable { AudiobookController.seekTrack(context, i, 0) })
            } else {
                if (book.bookmarks.isEmpty()) item { Text("Use the bookmark button to save a listening position.") }
                items(book.bookmarks, key = { it.id }) { mark -> ListItem(
                    headlineContent = { Text(mark.label.ifBlank { "Track ${mark.track + 1} · ${audioTime(mark.positionMs)}" }) },
                    supportingContent = { Text("Track ${mark.track + 1} · ${audioTime(mark.positionMs)}") },
                    trailingContent = { IconButton(onClick = { scope.launch { runCatching { store.removeBookmark(book.id, mark.id) }.onFailure { error = it.message } } }) { Icon(Icons.Outlined.DeleteOutline, "Remove audio bookmark") } },
                    modifier = Modifier.clickable { AudiobookController.seekTrack(context, mark.track, mark.positionMs) }) }
            }
        }
    }
    if (timer) AlertDialog(onDismissRequest = { timer = false }, title = { Text("Sleep timer") }, text = {
        Column { listOf(5,15,30,60).forEach { minutes -> TextButton(onClick = { AudiobookController.sleep(context, minutes); timer = false }) { Text("$minutes minutes") } } }
    }, confirmButton = { TextButton(onClick = { AudiobookController.clearSleep(context); timer = false }) { Text("Turn off timer") } },
        dismissButton = { TextButton(onClick = { timer = false }) { Text("Close") } })
}

/** The compact recorded-audio controls stay available directly on the reading page. */
@Composable
internal fun RecordedPlaybackBar(bookId: String) {
    val context = LocalContext.current
    val state by AudiobookController.state.collectAsStateWithLifecycle()
    if (state.bookId != bookId || state.id == null) return
    Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { AudiobookController.seek(context, (state.positionMs - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Outlined.Replay10, "Audio back 10 seconds") }
        IconButton(onClick = { if (state.playing) AudiobookController.pause(context) else AudiobookController.play(context) }, enabled = !state.loading) {
            Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause recorded narration" else "Resume recorded narration")
        }
        TextButton(onClick = { state.id?.let { openAudiobook(context, it) } }, Modifier.weight(1f)) {
            Text(if (state.loading) "Loading narration…" else "${audioTime(state.positionMs)} · ${state.speed}×", maxLines = 1)
        }
        IconButton(onClick = { AudiobookController.seek(context, (state.positionMs + 30_000).coerceAtMost(state.durationMs)) }) { Icon(Icons.Outlined.Forward30, "Audio forward 30 seconds") }
        IconButton(onClick = { AudiobookController.stop(context) }) { Icon(Icons.Outlined.Stop, "Stop recorded narration") }
    } }
}
