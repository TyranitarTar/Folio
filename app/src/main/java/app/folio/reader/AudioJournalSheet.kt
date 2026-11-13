@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable internal fun AudioJournalSheet(book:Audiobook,store:AudioJournalStore,history:Boolean,dismiss:()->Unit) {
    val journals by store.state.collectAsStateWithLifecycle()
    val active by AudiobookController.state.collectAsStateWithLifecycle()
    val journal=journals[book.id] ?: AudioJournal()
    val scope=rememberCoroutineScope()
    val editorSaver=Saver<AudioNote?,Any>(
        save={n->n?.let{listOf(it.id,it.track,it.positionMs,it.text,it.createdAt,it.updatedAt,it.passage)}},
        restore={raw->val v=raw as List<*>;AudioNote(v[0] as String,v[1] as Int,v[2] as Long,v[3] as String,v[4] as Long,v[5] as Long,v[6] as String)})
    var editing by rememberSaveable(stateSaver=editorSaver) { mutableStateOf<AudioNote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val chapters=remember(book.tracks){book.listeningChapters()}
    fun point(track:Int,at:Long):String {
        val index=chapters.currentChapter(track.coerceIn(book.tracks.indices),at)
        return "${chapters[index].title} · ${audioTime((at-chapters[index].startMs).coerceAtLeast(0))}"
    }
    fun date(at:Long)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(at))
    LaunchedEffect(book.id){runCatching{store.load(book.id)}.onSuccess{ready=true}.onFailure{error="Notes and history could not be loaded: ${it.message}"}}
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=620.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item{Text(if(history)"Listening history" else "Notes",style=MaterialTheme.typography.headlineSmall)}
            error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
            if(!ready && error==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            if(history) {
                item{Text("Actual listening time, excluding pauses and jumps. The latest 200 sessions are retained.",style=MaterialTheme.typography.bodySmall)}
                if(journal.sessions.isEmpty() && ready)item{Text("No listening sessions yet. Opening the player does not start a session.")}
                items(journal.sessions.asReversed(),key={it.id}){session->
                    val live=!session.closed && session.id==journal.sessions.lastOrNull()?.id && active.id==book.id && active.playing
                    ListItem(headlineContent={Text(date(session.startedAt))},supportingContent={Column {
                        Text("${date(session.endedAt)} · ${audioTime(session.listenedMs)} listened")
                        if(session.originalPositions) {
                            Text("Original recording · file ${session.startTrack+1}, ${audioTime(session.startMs)} → file ${session.endTrack+1}, ${audioTime(session.endMs)}")
                        } else {
                            Text("From ${point(session.startTrack,session.startMs)}")
                            Text("To ${point(session.endTrack,session.endMs)}")
                        }
                        Text("Speed: ${session.speeds.map{it.speed}.joinToString(" → "){"${it}×"}}")
                        if(!session.closed)Text(if(live)"In progress" else "Interrupted · saved through last checkpoint")
                    }})
                }
                if(journal.sessions.isNotEmpty())item{TextButton(onClick={clearing=true},enabled=ready && !(active.id==book.id && active.playing)){Text("Clear listening history")}}
            } else {
                item{Button(onClick={
                    val playing=active.takeIf{it.id==book.id}
                    val track=playing?.trackIndex ?: book.positionTrack;val at=playing?.positionMs ?: book.positionMs
                    editing=AudioJournalStore.newNote(track,at,displayedCue(book,displayPassage(book,track,at))?.text.orEmpty())
                },enabled=ready){Icon(Icons.Outlined.NoteAdd,null);Text("Write a note")}}
                if(journal.notes.isEmpty() && ready)item{Text("No notes yet. Each note keeps the position where you wrote it.")}
                items(journal.notes,key={it.id}){note->
                    ListItem(headlineContent={Text(note.text)},supportingContent={Text("${point(note.track,note.positionMs)}\n${date(note.updatedAt)}")},
                        trailingContent={Row {
                            IconButton(onClick={editing=note}){Icon(Icons.Outlined.Edit,"Edit note")}
                            IconButton(onClick={scope.launch{runCatching{store.removeNote(book.id,note.id)}.onFailure{error=it.message}}}){Icon(Icons.Outlined.DeleteOutline,"Delete note")}
                        }})
                }
            }
        }
    }
    editing?.let{note->
        var draft by rememberSaveable(note.id){mutableStateOf(note.text)}
        var saving by remember{mutableStateOf(false)}
        AlertDialog(onDismissRequest={if(!saving)editing=null},title={Text(if(note.text.isBlank())"Write a note" else "Edit note")},text={Column {
            Text(point(note.track,note.positionMs),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft,{draft=it.take(2000)},label={Text("Your note")},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp),enabled=!saving)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick={saving=true;scope.launch{
            runCatching{store.saveNote(book.id,note.copy(text=draft,updatedAt=System.currentTimeMillis()))}
                .onSuccess{editing=null}.onFailure{error=it.message};saving=false
        }},enabled=draft.isNotBlank() && !saving){Text("Save note")}},dismissButton={TextButton(onClick={editing=null},enabled=!saving){Text("Cancel")}})
    }
    if(clearing)AlertDialog(onDismissRequest={clearing=false},title={Text("Clear listening history?")},text={Text("Your position, notes and bookmarks are kept.")},
        confirmButton={TextButton(onClick={clearing=false;scope.launch{runCatching{store.clearSessions(book.id)}.onFailure{error=it.message}}}){Text("Clear history")}},
        dismissButton={TextButton(onClick={clearing=false}){Text("Cancel")}})
}
