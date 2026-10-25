@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable internal fun AudiobookPlayerScreen(book:Audiobook,store:AudiobookStore,back:()->Unit,read:(String)->Unit) {
    val context=LocalContext.current
    val active by AudiobookController.state.collectAsStateWithLifecycle()
    val state=active.takeIf{it.id==book.id}
    val scope=rememberCoroutineScope()
    var section by rememberSaveable(book.id){mutableStateOf("Chapters")}
    var menu by remember{mutableStateOf(false)}
    var speedMenu by remember{mutableStateOf(false)}
    var timer by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    var options by remember{mutableStateOf(AudioPreferences.load(context))}
    var error by remember{mutableStateOf<String?>(null)}
    val index=(state?.trackIndex ?: book.positionTrack).coerceIn(book.tracks.indices)
    val position=state?.positionMs ?: book.positionMs
    val duration=state?.durationMs?.takeIf{it>0} ?: book.tracks[index].durationMs
    val speed=state?.speed ?: book.speed
    val effective=remember(book,duration,index){book.copy(tracks=book.tracks.mapIndexed{i,t->if(i==index)t.copy(durationMs=duration) else t})}
    val chapters=remember(effective.tracks){effective.listeningChapters()}
    val chapterIndex=chapters.currentChapter(index,position)
    val chapter=chapters[chapterIndex]
    val elapsed=effective.tracks.take(index).sumOf{it.durationMs}+position
    fun seek(track:Int,offset:Long) {
        if(state==null)AudiobookController.start(context,book.id,track,offset,false)
        else AudiobookController.seekTrack(context,track,offset)
    }
    BackHandler(onBack=back)
    Scaffold(topBar={PublicationHeader(book.title,back,actions={
        Box {
            IconButton(onClick={menu=true}) { Icon(Icons.Outlined.MoreVert,"Player menu") }
            DropdownMenu(menu,{menu=false}) {
                book.bookId?.let{linked->DropdownMenuItem(text={Text("Read book")},onClick={menu=false;read(linked)})}
                DropdownMenuItem(text={Text("Playback settings")},onClick={menu=false;settings=true})
                DropdownMenuItem(text={Text("Listening history")},onClick={menu=false;section="History"})
            }
        }
    })}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    val epubs by (context.applicationContext as FolioApp).library.books.collectAsStateWithLifecycle()
                    Box(Modifier.width(104.dp).height(140.dp)){AudiobookCover(book,epubs,Modifier.fillMaxSize())}
                    Column(Modifier.weight(1f)) {
                        Text(book.title,style=MaterialTheme.typography.titleLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                        if(book.author.isNotBlank())Text(book.author,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(book.preview)Text("Preview recording",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        book.bookId?.let{linked->TextButton(onClick={read(linked)},contentPadding=PaddingValues(0.dp)){Text("Read book")}}
                        Text(chapter.title,Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.primary,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("Chapter ${chapterIndex+1} of ${chapters.size}",style=MaterialTheme.typography.bodySmall)
                    }
                }
                AudioSeekBar("Chapter",position-chapter.startMs,chapter.durationMs,state?.loading!=true){seek(chapter.track,chapter.startMs+it)}
                AudioSeekBar("Book",elapsed,effective.totalMs,effective.durationKnown && state?.loading!=true){val point=effective.positionAt(it);seek(point.track,point.positionMs)}
                Text(if(effective.durationKnown)"${audioTime(((effective.totalMs-elapsed).coerceAtLeast(0)/speed).toLong())} left at ${speed}×" else "Book duration is still loading",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={if(state==null)seek(chapter.track,chapter.startMs) else AudiobookController.previous(context)},enabled=chapterIndex>0 || position>chapter.startMs) { Icon(Icons.Outlined.SkipPrevious,"Previous chapter") }
                    TextButton(onClick={if(state==null)seek(index,(position-options.backSeconds*1000L).coerceAtLeast(0)) else AudiobookController.rewind(context)}) { Icon(Icons.Outlined.Replay,null);Text("${options.backSeconds}s") }
                    FilledIconButton(onClick={when{state?.playing==true->AudiobookController.pause(context);state==null->AudiobookController.start(context,book.id);else->AudiobookController.play(context)}},
                        modifier=Modifier.size(64.dp),enabled=state?.loading!=true) { Icon(if(state?.playing==true)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(state?.playing==true)"Pause audiobook" else "Play audiobook",Modifier.size(32.dp)) }
                    TextButton(onClick={if(state==null)seek(index,(position+options.forwardSeconds*1000L).coerceAtMost(duration)) else AudiobookController.forward(context)}) { Text("${options.forwardSeconds}s");Icon(Icons.Outlined.Forward,null) }
                    IconButton(onClick={if(state==null)chapters.getOrNull(chapterIndex+1)?.let{seek(it.track,it.startMs)} else AudiobookController.next(context)},enabled=chapterIndex<chapters.lastIndex) { Icon(Icons.Outlined.SkipNext,"Next chapter") }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick={speedMenu=true}) { Text("${speed}×") }
                        DropdownMenu(speedMenu,{speedMenu=false}) { listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f).forEach{rate->
                            DropdownMenuItem(text={Text("${rate}×")},onClick={speedMenu=false;AudiobookController.speed(context,rate)}) }
                        }
                    }
                    TextButton(onClick={timer=true}) { Icon(Icons.Outlined.Bedtime,null);Text(state?.sleepRemainingSeconds?.let{audioTime(it*1000)} ?: "Sleep") }
                    IconButton(onClick={scope.launch{runCatching{store.addBookmark(book.id,index,position)}.onFailure{error=it.message}}}) { Icon(Icons.Outlined.BookmarkAdd,"Add audio bookmark") }
                    TextButton(onClick={AudiobookController.stop(context)}) { Icon(Icons.Outlined.Stop,null);Text("Stop") }
                }
                if(state?.loading==true){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading audio…")}
                (state?.error ?: error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                state?.cue?.text?.takeIf{it.isNotBlank()}?.let{Text(it,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)}
                PrimaryScrollableTabRow(listOf("Chapters","Bookmarks","History").indexOf(section).coerceAtLeast(0),edgePadding=0.dp) {
                    listOf("Chapters","Bookmarks","History").forEach{label->Tab(section==label,{section=label},text={Text(label)})}
                }
            }
            when(section) {
                "Chapters" -> items(chapters.indices.toList()){i->val c=chapters[i]
                    ListItem(headlineContent={Text(c.title)},supportingContent={Text(if(c.durationMs>0)audioTime(c.durationMs) else "Duration pending")},
                        leadingContent={Text("${i+1}")},trailingContent={if(i==chapterIndex)Icon(Icons.Outlined.Headphones,"Current chapter")},
                        modifier=Modifier.clickable{seek(c.track,c.startMs)})
                }
                "Bookmarks" -> {
                    if(book.bookmarks.isEmpty())item{Text("Save a listening point with the bookmark button.")}
                    items(book.bookmarks,key={it.id}){mark->val c=chapters.currentChapter(mark.track,mark.positionMs)
                        ListItem(headlineContent={Text(mark.label.ifBlank{chapters[c].title})},supportingContent={Text("Chapter ${c+1} · ${audioTime(mark.positionMs-chapters[c].startMs)}")},
                            trailingContent={IconButton(onClick={scope.launch{runCatching{store.removeBookmark(book.id,mark.id)}.onFailure{error=it.message}}}){Icon(Icons.Outlined.DeleteOutline,"Remove audio bookmark")}},
                            modifier=Modifier.clickable{seek(mark.track,mark.positionMs)})
                    }
                }
                else -> {
                    if(book.history.isEmpty())item{Text("Pauses and positions before seeking will appear here.")}
                    items(book.history.asReversed()){point->val c=chapters.currentChapter(point.track,point.positionMs)
                        ListItem(headlineContent={Text("${point.reason} · ${chapters[c].title}")},supportingContent={Text("Chapter ${c+1} · ${audioTime(point.positionMs-chapters[c].startMs)} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(point.at))}")},modifier=Modifier.clickable{seek(point.track,point.positionMs)})
                    }
                }
            }
        }
    }
    if(timer)AlertDialog(onDismissRequest={timer=false},title={Text("Sleep timer")},text={
        Column { listOf(5,15,30,60).forEach { minutes ->
            TextButton(onClick={AudiobookController.sleep(context,minutes);timer=false}) { Text("$minutes minutes") }
        } }
    },
        confirmButton={TextButton(onClick={AudiobookController.clearSleep(context);timer=false}){Text("Turn off timer")}},dismissButton={TextButton(onClick={timer=false}){Text("Close")}})
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Playback settings",style=MaterialTheme.typography.headlineSmall)
            fun save(value:AudioPreferences){options=value;value.save(context)}
            ChoiceRow("Back · seconds",listOf("5","10","15","30","60"),options.backSeconds.toString()){save(options.copy(backSeconds=it.toInt()))}
            ChoiceRow("Forward · seconds",listOf("5","10","15","30","60"),options.forwardSeconds.toString()){save(options.copy(forwardSeconds=it.toInt()))}
            Text("Skip intervals also apply to headset and notification controls.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Switch(options.autoRewind,{save(options.copy(autoRewind=it))});Text("Rewind after a longer pause",Modifier.padding(start=8.dp))}
            if(options.autoRewind) {
                ChoiceRow("Rewind · seconds",listOf("3","5","10","15"),options.rewindSeconds.toString()){save(options.copy(rewindSeconds=it.toInt()))}
                ChoiceRow("After · minutes",listOf("1","5","15"),options.afterMinutes.toString()){save(options.copy(afterMinutes=it.toInt()))}
            }
            TextButton(onClick={settings=false}){Text("Done")}
        }
    }
}

@Composable private fun AudioSeekBar(label:String,position:Long,duration:Long,enabled:Boolean,seek:(Long)->Unit) {
    var dragged by remember(label,duration){mutableStateOf<Float?>(null)}
    val fraction=dragged ?: if(duration>0)(position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f
    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Text(if(duration>0)"${audioTime((fraction*duration).toLong())} / ${audioTime(duration)}" else "Loading duration…",style=MaterialTheme.typography.bodySmall)
    }
    Slider(value=fraction,onValueChange={dragged=it},valueRange=0f..1f,enabled=enabled && duration>0,
        modifier=Modifier.fillMaxWidth().semantics{contentDescription="$label position"},onValueChangeFinished={dragged?.let{seek((it*duration).toLong())};dragged=null})
}
