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
    scanRequest: Int=0,scanningChanged: (Boolean)->Unit={},importRequest:Int=0,shelf:String="All books",manageShelves:()->Unit={}) {
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
        if (uri != null) importing { persistDocumentAccess(context,uri);store.importAudio(audiobookFolder(context, uri),preserveOrder=true) }
    }
    var imports by rememberSaveable { mutableStateOf(false) }
    var consumedImport by rememberSaveable{mutableIntStateOf(importRequest)}
    LaunchedEffect(importRequest){if(consumedImport!=importRequest){consumedImport=importRequest;imports=true}}
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
    var assigning by remember{mutableStateOf<Audiobook?>(null)}
    var splitting by remember{mutableStateOf<Audiobook?>(null)}
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),floatingActionButton={ FloatingActionButton(onClick={imports=true}) { Icon(Icons.Outlined.Add,"Add audiobooks") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            sourceMessage?.let { Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(it,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                IconButton(onClick={sourceMessage=null}){Icon(Icons.Outlined.Close,"Dismiss scan result")}
            } }
            if(busy)TextButton(onClick={importTask?.cancel()}) { Text("Cancel scan / import") }
            if(!busy && metadata!=null)Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(metadata.orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={store.cancelMetadata()}) { Text("Stop metadata") }
            }
            message?.let { Text(it,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall) }
            LibraryStatusPager(prefs.status,{update(prefs.copy(status=it))}) { page ->
            val visible=remember(entries,prefs,query,page,shelf){prefs.copy(status=page).visibleAudiobooks(entries,query,shelf)}
            if(visible.isEmpty())Column(Modifier.padding(24.dp)) {
                Icon(Icons.Outlined.Headphones,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
                Text(if(entries.isEmpty())"Your audiobook library" else "No matching audiobooks",style=MaterialTheme.typography.titleLarge)
                Text(if(entries.isEmpty())"Use + to add files, a narrated book or a library folder." else "Try another search or status.")
            } else if(prefs.layout=="List") LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=72.dp)) {
                items(visible,key={it.id}) { audio ->
                    val fraction=if(audio.durationKnown && audio.totalMs>0)(audio.elapsedMs.toFloat()/audio.totalMs).coerceIn(0f,1f) else 0f
                    LibraryTitleRow(audio.title,audio.author,if(prefs.showProgress)audioLibraryBadge(audio,playback.id==audio.id && playback.playing) else "",
                        click={openAudiobook(context,audio.id)},longClick={actions=audio},actionLabel="Listen ${audio.title}",action={openAudiobook(context,audio.id,true)},folioBook=audio.bookId!=null) {
                        AudiobookCover(audio,epubs,Modifier.fillMaxSize())
                    }
                }
            } else androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns=androidx.compose.foundation.lazy.grid.GridCells.Fixed(prefs.columns.coerceIn(2,4)),modifier=Modifier.fillMaxSize(),
                contentPadding=PaddingValues(start=8.dp,end=8.dp,top=4.dp,bottom=72.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(visible.size,key={visible[it].id}) { index ->
                    val audio=visible[index]
                    val fraction=if(audio.durationKnown && audio.totalMs>0)(audio.elapsedMs.toFloat()/audio.totalMs).coerceIn(0f,1f) else 0f
                    LibraryCoverTile(audio.title,audioLibraryBadge(audio,playback.id==audio.id && playback.playing),fraction,prefs,
                        click={openAudiobook(context,audio.id)},longClick={actions=audio},actionLabel="Listen ${audio.title}",action={openAudiobook(context,audio.id,true)},folioBook=audio.bookId!=null) {
                        AudiobookCover(audio,epubs,Modifier.fillMaxSize())
                    }
                }
            }
            }
        }
    }
    actions?.let { audio -> ModalBottomSheet(onDismissRequest={actions=null}) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(audio.title,style=MaterialTheme.typography.titleLarge)
            AudioMenuRow(if(audio.started)"Resume listening" else "Listen"){actions=null;openAudiobook(context,audio.id,true)}
            epubs.firstOrNull{it.id==audio.bookId}?.let { linked -> AudioMenuRow("Read book"){actions=null;read(linked)} }
            listOf("New","Reading","Finished").forEach{status->AudioMenuRow("Mark ${status.lowercase()}"){actions=null;scope.launch{runCatching{store.setStatus(audio.id,status)}.onFailure{message=it.message}}}}
            AudioMenuRow("Assign shelves"){actions=null;assigning=audio}
            if(audio.bookId==null && audio.sourceRoot!=null && audio.tracks.size>1)AudioMenuRow("Split files into separate books…"){actions=null;splitting=audio}
            AudioMenuRow("Delete from device…"){actions=null;removing=audio}
        }
    } }
    assigning?.let{audio->
        val labels by (context.applicationContext as FolioApp).library.shelves.collectAsStateWithLifecycle()
        ShelfDialog(labels,true,audio.shelves.toSet(),{assigning=null},{assigning=null;manageShelves()},
            {selected->scope.launch{runCatching{store.assignShelves(audio.id,selected.orEmpty())}.onSuccess{assigning=null}.onFailure{message=it.message}}},{},{})
    }
    splitting?.let{audio->
        var working by remember(audio.id){mutableStateOf(false)}
        var failure by remember(audio.id){mutableStateOf<String?>(null)}
        AlertDialog(onDismissRequest={if(!working)splitting=null},title={Text("Split ${audio.tracks.size} files into books?")},text={Column {
            Text("Each source audio file becomes its own library entry. Its bookmarks and listening position follow the file. The source files stay in place.")
            audio.tracks.take(5).forEach{Text(it.title,Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall)}
            failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(working)LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(enabled=!working,onClick={working=true;scope.launch{
            runCatching{sources.splitFiles(audio)}.onSuccess{splitting=null;message="Files split into separate books. Future rescans keep this grouping."}.onFailure{failure=it.message;working=false}
        }}){Text("Split files")}},dismissButton={TextButton(enabled=!working,onClick={splitting=null}){Text("Cancel")}})
    }
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
            Text("Forgetting a source stops rescanning that folder. Existing books keep their sources and saved positions.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={imports=false},enabled=!busy) { Text("Done") }
        }
    }
    removing?.let { audio ->
        var keepOriginals by remember(audio.id){mutableStateOf(false)}
        var deleteFolder by remember(audio.id){mutableStateOf(false)}
        val folderTarget=remember(audio.id){sources.bookFolder(audio)}
        var deleting by remember(audio.id){mutableStateOf(false)}
        var deleteError by remember(audio.id){mutableStateOf<String?>(null)}
        val originals=(audio.tracks.mapNotNull{it.sourceUri}+audio.originalSources).distinct()
        AlertDialog(onDismissRequest={if(!deleting)removing=null},title={Text("Delete ${audio.title}?")},text={Column(Modifier.heightIn(max=380.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text(if(originals.isEmpty())"Delete Folio’s stored audio and listening bookmarks. This older import has no recorded original source location." else "Delete the source files below, Folio’s stored audio and listening bookmarks. Other library entries are protected.")
            originals.take(5).forEach{Text(android.net.Uri.decode(it).substringAfter("/document/").takeLast(160),Modifier.padding(top=5.dp),style=MaterialTheme.typography.bodySmall)}
            if(originals.size>5)Text("And ${originals.size-5} more source files.",style=MaterialTheme.typography.bodySmall)
            if(audio.bookId!=null)Text("The eBook and reading annotations remain.",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(keepOriginals,{if(!deleting)keepOriginals=it});Text("Keep original files · remove Folio entry only",style=MaterialTheme.typography.bodySmall)}
            if(folderTarget!=null && !keepOriginals) {
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(deleteFolder,{if(!deleting)deleteFolder=it});Text("Also delete the book folder and its remaining contents",style=MaterialTheme.typography.bodySmall)}
                if(deleteFolder)Text(android.net.Uri.decode(folderTarget.toString()).substringAfter("/document/"),style=MaterialTheme.typography.bodySmall)
            }
            if(deleting)LinearProgressIndicator(Modifier.fillMaxWidth())
            deleteError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!deleting,onClick={deleting=true;scope.launch{
            runCatching{if(keepOriginals)store.remove(audio.id) else store.deleteFromDevice(audio.id,if(deleteFolder)folderTarget else null)}
                .onSuccess{removing=null}.onFailure{deleteError=it.message;deleting=false}
        }}){Text(if(keepOriginals)"Remove entry" else "Delete")}},dismissButton={TextButton(enabled=!deleting,onClick={removing=null}){Text("Cancel")}})
    }
}

@Composable private fun AudioMenuRow(label:String,action:()->Unit) {
    ListItem(headlineContent={Text(label)},modifier=Modifier.fillMaxWidth().clickable(onClick=action))
}

internal fun audioLibraryBadge(book: Audiobook,playing: Boolean=false): String = when {
    playing -> "Playing"
    book.isRead -> "Finished"
    book.preview -> "Preview"
    !book.started -> "New"
    !book.durationKnown -> "In progress"
    else -> "${if(book.totalMs>0)(100.0*book.elapsedMs/book.totalMs).toInt().coerceIn(0,100) else 0}%"
}

@Composable
internal fun AudiobookCover(book: Audiobook,epubs: List<Book>,modifier: Modifier) {
    val context=LocalContext.current
    val linked=epubs.firstOrNull{it.id==book.bookId}
    val art=(context.applicationContext as FolioApp).audiobooks.artworkFile(book)
    if(art!=null)coil.compose.AsyncImage(art,"Cover of ${book.title}",modifier,contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else if(linked!=null)BookCover(linked,(context.applicationContext as FolioApp).library,modifier)
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

/** The compact recorded-audio controls stay available directly on the reading page. */
@Composable
internal fun RecordedPlaybackBar(bookId: String) {
    val context = LocalContext.current
    val options=AudioPreferences.load(context)
    val state by AudiobookController.state.collectAsStateWithLifecycle()
    if (state.bookId != bookId || state.id == null) return
    Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { AudiobookController.rewind(context) }) { Icon(Icons.Outlined.Replay, "Audio back ${options.backSeconds} seconds") }
        IconButton(onClick = { if (state.playing) AudiobookController.pause(context) else AudiobookController.play(context) }, enabled = !state.loading) {
            Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause recorded narration" else "Resume recorded narration")
        }
        TextButton(onClick = { state.id?.let { openAudiobook(context, it) } }, Modifier.weight(1f)) {
            Text(if (state.loading) "Loading narration…" else "${audioTime(state.positionMs)} · ${state.speed}×", maxLines = 1)
        }
        IconButton(onClick = { AudiobookController.forward(context) }) { Icon(Icons.Outlined.Forward, "Audio forward ${options.forwardSeconds} seconds") }
        IconButton(onClick = { AudiobookController.stop(context) }) { Icon(Icons.Outlined.Stop, "Stop recorded narration") }
    } }
}

@Composable
internal fun LibraryAudioBar() {
    val context=LocalContext.current
    val state by AudiobookController.state.collectAsStateWithLifecycle()
    val id=state.id ?: return
    val store=(context.applicationContext as FolioApp).audiobooks
    val books by store.books.collectAsStateWithLifecycle()
    val epubs by (context.applicationContext as FolioApp).library.books.collectAsStateWithLifecycle()
    val artwork=books.firstOrNull{it.id==id}?.let{store.artworkFile(it)}
    val linked=epubs.firstOrNull{it.id==books.firstOrNull{audio->audio.id==id}?.bookId}
    Surface(tonalElevation=3.dp) { Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={openAudiobook(context,id)},modifier=Modifier.weight(1f)) {
            if(artwork!=null)coil.compose.AsyncImage(artwork,null,Modifier.width(28.dp).height(36.dp),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
            else if(linked!=null)Box(Modifier.width(28.dp).height(36.dp)){BookCover(linked,(context.applicationContext as FolioApp).library,Modifier.fillMaxSize())}
            else Icon(Icons.Outlined.Headphones,null)
            Text(state.title,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=8.dp))
        }
        IconButton(onClick={if(state.playing)AudiobookController.pause(context) else AudiobookController.play(context)},enabled=!state.loading) {
            Icon(if(state.playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(state.playing)"Pause audiobook" else "Resume audiobook")
        }
        IconButton(onClick={AudiobookController.stop(context)}) { Icon(Icons.Outlined.Stop,"Stop audiobook") }
    } }
}
