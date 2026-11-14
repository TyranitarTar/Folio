@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

internal fun openBookNotebook(context:Context,id:String) {
    context.startActivity(Intent(context,BookNotebookActivity::class.java).putExtra("audiobook",id))
}

internal class BookNotebookModel(application:Application):AndroidViewModel(application) {
    private val store=(application as FolioApp).audiobooks
    var book by mutableStateOf<Audiobook?>(null);private set
    var value by mutableStateOf(TextFieldValue());private set
    var loaded by mutableStateOf(false);private set
    var status by mutableStateOf("Opening notes…");private set
    var failed by mutableStateOf(false);private set
    var canUndo by mutableStateOf(false);private set
    var canRedo by mutableStateOf(false);private set
    private var opened=false
    private var pending:Job?=null
    private var order=0L
    private var savedText=""
    private var originalText:String?=null
    var editorScroll:Int?=null
    val document:String get()=NotebookEditing.document(value.text,originalText)
    private var durable=true
    private val undo=ArrayDeque<TextFieldValue>()
    private val redo=ArrayDeque<TextFieldValue>()
    fun open(id:String) {
        if(opened)return;opened=true
        viewModelScope.launch {
            try {
                if(store.books.value.none{it.id==id})store.load()
                val current=store.books.value.firstOrNull{it.id==id} ?: error("This audiobook is no longer available.")
                val document=store.notebooks.open(current)
                book=current;savedText=document.text;originalText=document.text;value=NotebookEditing.begin(document.text);loaded=true;status="Saved"
            } catch(e:CancellationException){throw e} catch(e:Exception){failed=true;status=e.message ?: "Notes could not open. Existing files have been kept."}
        }
    }
    fun edit(next:TextFieldValue) {
        if(!loaded)return
        val edited=NotebookEditing.edit(value,next)
        if(NotebookEditing.document(edited.text,originalText).length>BookNotebookStore.MAX_CHARS){status="Notebook full. Export the text before adding more.";return}
        if(edited.text==value.text){value=edited;return}
        undo.addLast(value);trim(undo);redo.clear()
        value=edited;changed()
    }
    private fun trim(stack:ArrayDeque<TextFieldValue>) {
        while(stack.size>40 || stack.sumOf{it.text.length}>2_000_000)stack.removeFirst()
    }
    fun undo() {if(undo.isNotEmpty()){redo.addLast(value);trim(redo);value=undo.removeLast();changed()}}
    fun redo() {if(redo.isNotEmpty()){undo.addLast(value);trim(undo);value=redo.removeLast();changed()}}
    fun insert(text:String) {
        val range=value.selection;val next=value.text.replaceRange(range.min,range.max,text)
        edit(TextFieldValue(next,TextRange(range.min+text.length)))
    }
    fun find(query:String):Boolean {
        if(query.isBlank())return false
        val start=value.text.indexOf(query,value.selection.max,ignoreCase=true).takeIf{it>=0} ?: value.text.indexOf(query,ignoreCase=true)
        if(start<0)return false
        value=value.copy(selection=TextRange(start,start+query.length));return true
    }
    private fun changed() {
        canUndo=undo.isNotEmpty();canRedo=redo.isNotEmpty()
        val current=document
        if(current==savedText && pending==null)return
        val id=book?.id ?: return
        // Assign the observation order before dispatch. Cancelled older writes cannot win.
        order=store.notebooks.nextOrder(id);val ticket=order
        durable=false
        pending?.cancel();status="Saving…";failed=false
        pending=viewModelScope.launch {delay(350);save(id,current,ticket)}
    }
    private suspend fun save(id:String,text:String,ticket:Long) {
        try {
            val result=store.notebooks.save(id,text,ticket)
            if(ticket==order){savedText=text;durable=true;status=if(result.recovered)"Draft saved · retry on leaving" else "Saved";failed=result.recovered;pending=null}
        } catch(e:CancellationException){throw e} catch(e:Exception){if(ticket==order){failed=true;status="Not saved · ${e.message ?: "storage unavailable"}";pending=null}}
    }
    /** A small atomic file is flushed before the Activity can leave or lose its process. */
    fun flush():Boolean {
        if(!loaded)return true
        pending?.cancel();pending=null
        val id=book?.id ?: return true;val text=document
        if(text==savedText && !failed){status="Saved";durable=true;return true}
        order=store.notebooks.nextOrder(id)
        runBlocking {save(id,text,order)}
        return durable
    }
}

class BookNotebookActivity:ComponentActivity() {
    private val model:BookNotebookModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getStringExtra("audiobook") ?: run{finish();return}
        model.open(id)
        setContent {
            val prefs=remember{HomePreferences.load(this)}
            FolioTheme(prefs.theme=="Dark" || prefs.theme=="System" && isSystemInDarkTheme()) {
                BookNotebookScreen(model,{if(model.flush())finish()})
            }
        }
    }
    override fun onStop(){model.flush();super.onStop()}
}

@Composable private fun BookNotebookScreen(model:BookNotebookModel,back:()->Unit) {
    BackHandler(onBack=back)
    val context=LocalContext.current
    val compact=LocalConfiguration.current.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val scope=rememberCoroutineScope()
    var menu by remember{mutableStateOf(false)}
    var finding by remember{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var notice by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(model.value.text){notice=null}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null) {
            val text=model.document
            scope.launch {notice=try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{it.write(text.toByteArray(Charsets.UTF_8))}};"Notes exported"}
                catch(_:Exception){"Notes could not be exported. Your notebook is kept."}}
        }
    }
    @Composable fun headerActions() {

            TextButton(enabled=model.loaded,onClick={
                val text=model.document
                if(text.toByteArray(Charsets.UTF_8).size>500_000)notice="This notebook is too large for the clipboard. Use Export .txt."
                else try {(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Book notes",text));notice="All notes copied"}
                catch(_:Exception){notice="Could not copy. Use Export .txt."}
            }){Text("Copy all")}
            Box {IconButton(onClick={menu=true},enabled=model.loaded){Icon(Icons.Outlined.MoreVert,"Notebook menu")}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("Export .txt")},onClick={menu=false;export.launch("${model.book?.title?.replace(Regex("[\\\\/:*?\"<>|]"),"_")?.take(100) ?: "Book"} - Notes.txt")})
                    DropdownMenuItem(text={Text("Find in notes")},onClick={menu=false;finding=true})
                    DropdownMenuItem(text={Text("Insert playback timestamp")},onClick={
                        menu=false;val book=model.book ?: return@DropdownMenuItem;val state=AudiobookController.state.value.takeIf{it.id==book.id}
                        val track=state?.trackIndex ?: book.positionTrack;val at=state?.positionMs ?: book.positionMs
                        val chapters=book.listeningChapters();val chapter=chapters[chapters.currentChapter(track,at)]
                        model.insert("[${chapter.title} · ${audioTime((at-chapter.startMs).coerceAtLeast(0))}] ")
                    })
                }
            }
    }
    Scaffold(topBar={
        if(compact)Surface {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to player")}
                Text(notice ?: if(model.failed)model.status else "Notes",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2)
                IconButton(onClick=model::undo,enabled=model.canUndo){Icon(Icons.AutoMirrored.Outlined.Undo,"Undo note edit")}
                IconButton(onClick=model::redo,enabled=model.canRedo){Icon(Icons.AutoMirrored.Outlined.Redo,"Redo note edit")}
                headerActions()
            }
        } else TopAppBar(title={Column{Text("Notes",style=MaterialTheme.typography.titleLarge);Text(model.book?.title.orEmpty(),style=MaterialTheme.typography.labelMedium,maxLines=1)}},
            navigationIcon={IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to player")}},actions={headerActions()})
    }) {padding->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if(!compact)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(model.failed)model.status else notice ?: model.status,Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.labelSmall,color=if(model.failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick=model::undo,enabled=model.canUndo){Icon(Icons.AutoMirrored.Outlined.Undo,"Undo note edit")}
                IconButton(onClick=model::redo,enabled=model.canRedo){Icon(Icons.AutoMirrored.Outlined.Redo,"Redo note edit")}
            }
            if(model.loaded) {
                NotebookTextEditor(model,MaterialTheme.colorScheme.onSurface.toArgb(),MaterialTheme.colorScheme.primary.toArgb(),Modifier.weight(1f).fillMaxWidth())

            }
            else if(!model.failed)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if(finding)AlertDialog(onDismissRequest={finding=false},title={Text("Find in notes")},text={OutlinedTextField(query,{query=it},singleLine=true,label={Text("Search text")})},
        confirmButton={TextButton(onClick={if(model.find(query)){finding=false;notice=null} else notice="No matching text"}){Text("Find next")}},
        dismissButton={TextButton(onClick={finding=false}){Text("Close")}})
}
