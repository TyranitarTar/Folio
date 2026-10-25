@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
internal fun AudiobookLibrary(store: AudiobookStore, epubs: List<Book>, prefs: HomePreferences, query: String, update: (HomePreferences)->Unit, read: (Book) -> Unit,
    scanRequest: Int=0,scanningChanged: (Boolean)->Unit={}) {
    val context = LocalContext.current
    val entries by store.books.collectAsStateWithLifecycle()
    val playback by AudiobookController.state.collectAsStateWithLifecycle()
    val metadata by store.metadataProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var importTask by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Audiobook?>(null) }
    LaunchedEffect(store) { runCatching { store.load() }.onFailure { message = it.message ?: "Audiobooks could not be loaded." } }
    fun importing(action: suspend () -> Audiobook) {
        if (busy) return
        busy = true
        importTask=scope.launch {
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
        if (uri != null) importing { store.importAudio(audiobookFolder(context, uri),preserveOrder=true) }
    }
    var imports by rememberSaveable { mutableStateOf(false) }
    val sources=remember(store) { AudiobookSources(context,store) }
    var sourceRevision by remember { mutableIntStateOf(0) }
    var sourceMessage by remember { mutableStateOf<String?>(null) }
    var relinking by remember { mutableStateOf<android.net.Uri?>(null) }
    fun scan(root: android.net.Uri, add: Boolean=false) {
        if(busy)return
        store.cancelMetadata();busy=true;sourceMessage="Scanning audiobook folders…"
        importTask=scope.launch {
            try {
                if(add)sources.add(root)
                sourceMessage=sources.refresh(root,relinking) { sourceMessage=it }
                relinking?.let { old -> if(old!=root)sources.forget(old) };relinking=null;sourceRevision++
                store.prepareLinkedMetadata()
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){sourceMessage=error.message ?: "Folder access failed. Relink this source."}
            finally{busy=false}
        }
    }
    val rootPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null)scan(uri,true) else relinking=null
    }
    SideEffect { scanningChanged(busy) }
    DisposableEffect(Unit) { onDispose { scanningChanged(false) } }
    var consumedScan by rememberSaveable { mutableIntStateOf(scanRequest) }
    LaunchedEffect(scanRequest) {
        if(consumedScan!=scanRequest) {
            consumedScan=scanRequest
            val roots=sources.roots()
            if(roots.isEmpty()) { imports=true;sourceMessage="Add a library root folder to scan." }
            else if(!busy) {
                store.cancelMetadata();busy=true
                importTask=scope.launch {
                    val began=android.os.SystemClock.elapsedRealtime();val results=mutableListOf<String>();var failed=0
                    try {
                        roots.forEachIndexed { index,root ->
                            try { results+=sources.refresh(root){sourceMessage="Root ${index+1}/${roots.size} · $it"} }
                            catch(cancelled:CancellationException){throw cancelled}
                            catch(error:Exception){failed++;results+="Source ${index+1}: ${error.message?.take(180) ?: "Folder access failed"}"}
                        }
                        sourceMessage=results.joinToString("\n")+(if(failed>0)"\n$failed source(s) unavailable; relink in + Add audiobooks." else "")+
                            " · ${(android.os.SystemClock.elapsedRealtime()-began)/1000}s"
                        store.prepareLinkedMetadata()
                    } finally { busy=false;sourceRevision++ }
                }
            }
        }
    }
    val visible=remember(entries,prefs,query) { prefs.visibleAudiobooks(entries,query) }
    var actions by remember { mutableStateOf<Audiobook?>(null) }
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),floatingActionButton={ FloatingActionButton(onClick={imports=true}) { Icon(Icons.Outlined.Add,"Add audiobooks") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(listOf("All","New","Unread","Read").indexOf(prefs.status).coerceAtLeast(0)) {
                listOf("All","New","Unread","Read").forEach { label -> Tab(prefs.status==label,{update(prefs.copy(status=label))},text={Text(label)}) }
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            sourceMessage?.let { Text(it,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis) }
            if(busy)TextButton(onClick={importTask?.cancel()}) { Text("Cancel scan / import") }
            if(!busy && metadata!=null)Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(metadata.orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={store.cancelMetadata()}) { Text("Stop metadata") }
            }
            message?.let { Text(it,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall) }
            if(visible.isEmpty())Column(Modifier.padding(24.dp)) {
                Icon(Icons.Outlined.Headphones,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                Text(if(entries.isEmpty())"Your audiobook library" else "No matching audiobooks",style=MaterialTheme.typography.titleLarge)
                Text(if(entries.isEmpty())"Use + to add files, a narrated book or a library folder." else "Try another search or status.")
            } else if(prefs.layout=="List") LazyColumn(contentPadding=PaddingValues(bottom=90.dp)) {
                items(visible,key={it.id}) { audio ->
                    val fraction=if(audio.durationKnown && audio.totalMs>0)(audio.elapsedMs.toFloat()/audio.totalMs).coerceIn(0f,1f) else 0f
                    LibraryTitleRow(audio.title,audio.author,if(prefs.showProgress)audioLibraryBadge(audio,playback.id==audio.id && playback.playing) else "",
                        click={openAudiobook(context,audio.id)},longClick={actions=audio},actionLabel="Listen ${audio.title}",action={openAudiobook(context,audio.id,true)}) {
                        AudiobookCover(audio,epubs,Modifier.fillMaxSize())
                    }
                }
            } else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns=androidx.compose.foundation.lazy.grid.GridCells.Adaptive((340/prefs.columns).coerceAtLeast(90).dp),
                contentPadding=PaddingValues(start=8.dp,end=8.dp,top=8.dp,bottom=90.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(visible.size,key={visible[it].id}) { index ->
                    val audio=visible[index]
                    val fraction=if(audio.durationKnown && audio.totalMs>0)(audio.elapsedMs.toFloat()/audio.totalMs).coerceIn(0f,1f) else 0f
                    LibraryCoverTile(audio.title,audioLibraryBadge(audio,playback.id==audio.id && playback.playing),fraction,prefs,
                        click={openAudiobook(context,audio.id)},longClick={actions=audio},actionLabel="Listen ${audio.title}",action={openAudiobook(context,audio.id,true)}) {
                        AudiobookCover(audio,epubs,Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    actions?.let { audio -> ModalBottomSheet(onDismissRequest={actions=null}) {
        Column(Modifier.padding(24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(audio.title,style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={actions=null;openAudiobook(context,audio.id,true)}) { Text(if(audio.started)"Resume listening" else "Listen") }
            epubs.firstOrNull{it.id==audio.bookId}?.let { linked -> TextButton(onClick={actions=null;read(linked)}) { Text("Read book") } }
            TextButton(onClick={actions=null;scope.launch { runCatching{store.markRead(audio.id,!audio.isRead)}.onFailure{message=it.message} }}) { Text(if(audio.isRead)"Mark unread" else "Mark read") }
            TextButton(onClick={actions=null;removing=audio}) { Text("Remove audiobook") }
        }
    } }
    if(imports)ModalBottomSheet(onDismissRequest={if(!busy)imports=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Add audiobooks",style=MaterialTheme.typography.headlineSmall)
            Text("Root folders link audio in place for fast scanning. File imports and FolioBooks are copied for offline use.",style=MaterialTheme.typography.bodySmall)
            Button(onClick={files.launch(arrayOf("audio/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Import audio files") }
            OutlinedButton(onClick={bundle.launch(arrayOf("application/octet-stream","application/zip","*/*"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Import narrated .foliobook") }
            OutlinedButton(onClick={folder.launch(null)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Import one audiobook folder") }
            OutlinedButton(onClick={rootPicker.launch(null)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FolderOpen,null);Text("Add library root folder") }
            Text("A library root contains one folder per book; nested CD/track folders are kept together. Refresh to discover new or changed books.",style=MaterialTheme.typography.bodySmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(busy)OutlinedButton(onClick={importTask?.cancel()}) { Text("Cancel import") }
            sourceMessage?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            val roots=remember(sourceRevision) { sources.roots() }
            roots.forEach { root ->
                HorizontalDivider()
                Text(root.lastPathSegment.orEmpty().substringAfter(':').take(120),style=MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick={scan(root)},enabled=!busy) { Text("Refresh") }
                    TextButton(onClick={relinking=root;rootPicker.launch(root)},enabled=!busy) { Text("Relink") }
                    TextButton(onClick={sources.forget(root);sourceRevision++},enabled=!busy) { Text("Forget source") }
                }
            }
            Text("Forgetting a source keeps imported books. Changed audio is imported as another edition; previous audio is retained.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={imports=false},enabled=!busy) { Text("Done") }
        }
    }
    removing?.let { audio -> AlertDialog(onDismissRequest={removing=null},title={Text("Remove audiobook?")},
        text={Text(if(audio.sourceRoot!=null)"Remove ${audio.title} and its listening bookmarks from Folio? Source audio files stay in their folder." else "Remove ${audio.title}, its local audio and listening bookmarks? The EPUB and its reading annotations remain.")},
        confirmButton={TextButton(onClick={removing=null;scope.launch { runCatching { store.remove(audio.id) }.onFailure { message=it.message } }}) { Text("Remove") }},
        dismissButton={TextButton(onClick={removing=null}) { Text("Cancel") }}) }
}

internal fun audioLibraryBadge(book: Audiobook,playing: Boolean=false): String = when {
    playing -> "Playing"
    book.isRead -> "Read"
    book.preview -> "Preview"
    !book.started -> "New"
    !book.durationKnown -> "In progress"
    else -> "${if(book.totalMs>0)(100.0*book.elapsedMs/book.totalMs).toInt().coerceIn(0,100) else 0}%"
}

@Composable
private fun AudiobookCover(book: Audiobook,epubs: List<Book>,modifier: Modifier) {
    val context=LocalContext.current
    val linked=epubs.firstOrNull{it.id==book.bookId}
    if(linked!=null)BookCover(linked,(context.applicationContext as FolioApp).library,modifier)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Headphones,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
            Text(book.title,color=MaterialTheme.colorScheme.onPrimaryContainer,maxLines=4,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
        }
    }
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
                if (book != null) AudiobookPlayerScreen(book, store, { returnToLibrary(this,"Audiobooks") }, { linked ->
                    startActivity(Intent(this, ReaderActivity::class.java).putExtra("book", linked).putExtra("recordedFollow", true))
                }) else Scaffold { padding -> Column(Modifier.padding(padding).padding(24.dp)) {
                    Text(error ?: if (loaded) "This audiobook is no longer in the library." else "Opening audiobook…")
                    TextButton(onClick = { returnToLibrary(this@AudiobookActivity,"Audiobooks") }) { Text("Library") }
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
    androidx.activity.compose.BackHandler(onBack=back)
    Scaffold(topBar = { PublicationHeader(book.title,back,actions={ book.bookId?.let { linked -> TextButton(onClick = { read(linked) }) { Text("Read book") } } }) }) { padding ->
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
                Text(if(book.durationKnown)"Book ${audioTime(elapsed)} / ${audioTime(total)} · ${audioTime(((total - elapsed).coerceAtLeast(0) / speed).toLong())} remaining at ${speed}×" else "Track ${index+1} of ${book.tracks.size} · total duration loads as tracks are played",
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
                ListItem(headlineContent = { Text(book.tracks[i].title) }, supportingContent = { Text(if(book.tracks[i].durationMs>0)audioTime(book.tracks[i].durationMs) else "Duration on first play") },
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

@Composable
internal fun LibraryAudioBar() {
    val context=LocalContext.current
    val state by AudiobookController.state.collectAsStateWithLifecycle()
    val id=state.id ?: return
    Surface(tonalElevation=3.dp) { Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={openAudiobook(context,id)},modifier=Modifier.weight(1f)) {
            Icon(Icons.Outlined.Headphones,null)
            Text(state.title,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=8.dp))
        }
        IconButton(onClick={if(state.playing)AudiobookController.pause(context) else AudiobookController.play(context)},enabled=!state.loading) {
            Icon(if(state.playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(state.playing)"Pause audiobook" else "Resume audiobook")
        }
        IconButton(onClick={AudiobookController.stop(context)}) { Icon(Icons.Outlined.Stop,"Stop audiobook") }
    } }
}
