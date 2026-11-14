@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable internal fun AudioJournalSheet(book:Audiobook,store:AudioJournalStore,dismiss:()->Unit) {
    val journals by store.state.collectAsStateWithLifecycle()
    val active by AudiobookController.state.collectAsStateWithLifecycle()
    val journal=journals[book.id] ?: AudioJournal()
    val scope=rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val chapters=remember(book.tracks){book.listeningChapters()}
    fun point(track:Int,at:Long):String {
        val index=chapters.currentChapter(track.coerceIn(book.tracks.indices),at)
        return "${chapters[index].title} · ${audioTime((at-chapters[index].startMs).coerceAtLeast(0))}"
    }
    fun date(at:Long)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(at))
    LaunchedEffect(book.id){runCatching{store.load(book.id)}.onSuccess{ready=true}.onFailure{error="History could not be loaded: ${it.message}"}}
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=620.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item{Text("Listening history",style=MaterialTheme.typography.headlineSmall)}
            error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
            if(!ready && error==null)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
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
        }
    }
    if(clearing)AlertDialog(onDismissRequest={clearing=false},title={Text("Clear listening history?")},text={Text("Your position, notes and bookmarks are kept.")},
        confirmButton={TextButton(onClick={clearing=false;scope.launch{runCatching{store.clearSessions(book.id)}.onFailure{error=it.message}}}){Text("Clear history")}},
        dismissButton={TextButton(onClick={clearing=false}){Text("Cancel")}})
}
