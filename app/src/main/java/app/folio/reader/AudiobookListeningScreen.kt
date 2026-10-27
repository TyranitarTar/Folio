@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private data class AudioJump(val track:Int,val position:Long,val label:String)

@Composable internal fun AudiobookPlayerScreen(book:Audiobook,store:AudiobookStore,back:()->Unit,read:(String)->Unit) {
    val context=LocalContext.current
    val active by AudiobookController.state.collectAsStateWithLifecycle()
    val state=active.takeIf{it.id==book.id}
    val scope=rememberCoroutineScope()
    val epubs by (context.applicationContext as FolioApp).library.books.collectAsStateWithLifecycle()
    val linkedBook=book.bookId?.takeIf{linked->epubs.any{it.id==linked}}
    var section by rememberSaveable(book.id){mutableStateOf<String?>(null)}
    var menu by remember{mutableStateOf(false)}
    var speedMenu by remember{mutableStateOf(false)}
    var timer by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var jump by remember{mutableStateOf<AudioJump?>(null)}
    val index=(state?.trackIndex ?: book.positionTrack).coerceIn(book.tracks.indices)
    val position=state?.positionMs ?: book.positionMs
    val duration=state?.durationMs?.takeIf{it>0} ?: book.tracks[index].durationMs
    val speed=state?.speed ?: book.speed
    val effective=remember(book,duration,index){book.copy(tracks=book.tracks.mapIndexed{i,t->if(i==index)t.copy(durationMs=duration) else t})}
    val chapters=remember(effective.tracks){effective.listeningChapters()}
    val chapterIndex=chapters.currentChapter(index,position)
    val chapter=chapters[chapterIndex]
    val elapsed=effective.tracks.take(index).sumOf{it.durationMs}+position
    val remaining=if(effective.durationKnown)remainingListeningMs(effective.totalMs,elapsed,speed) else null
    val cue=state?.cue ?: book.tracks[index].cues.firstOrNull{position>=it.startMs && position<it.endMs}
    fun seek(track:Int,offset:Long) {
        if(state==null)AudiobookController.start(context,book.id,track,offset,false)
        else AudiobookController.seekTrack(context,track,offset)
    }
    fun skip(seconds:Int) {
        if(state!=null)AudiobookController.skip(context,seconds)
        else if(effective.durationKnown){val target=effective.positionAt((elapsed+seconds*1000L).coerceAtLeast(0));seek(target.track,target.positionMs)}
        else seek(index,(position+seconds*1000L).coerceAtLeast(0))
    }
    BackHandler(onBack=back)
    Scaffold(topBar={PublicationHeader(book.title,back,actions={
        Box {
            IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Player menu")}
            DropdownMenu(menu,{menu=false}) {
                linkedBook?.let{linked->DropdownMenuItem(text={Text("Read book")},onClick={menu=false;read(linked)})}
                DropdownMenuItem(text={Text("Add bookmark")},onClick={menu=false;scope.launch{runCatching{store.addBookmark(book.id,index,position)}.onFailure{error=it.message}}})
                DropdownMenuItem(text={Text("Sleep timer")},enabled=state!=null,onClick={menu=false;timer=true})
                DropdownMenuItem(text={Text("Bluetooth & playback settings")},onClick={menu=false;settings=true})
                DropdownMenuItem(text={Text("Recover a previous position")},onClick={menu=false;section="Position recovery"})
            }
        }
    })}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.width(104.dp).height(140.dp)){AudiobookCover(book,epubs,Modifier.fillMaxSize())}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(book.author.isNotBlank())Text(book.author,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("Chapter ${chapterIndex+1} of ${chapters.size}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        if(chapters.size>1 && chapter.title!=book.title)Text(chapter.title,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(book.preview)Text("Preview recording",style=MaterialTheme.typography.labelMedium)
                        linkedBook?.let{linked->TextButton(onClick={read(linked)},contentPadding=PaddingValues(0.dp)){Icon(Icons.Outlined.AutoStories,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Read FolioBook")}}
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Book",style=MaterialTheme.typography.labelLarge)
                    Text(if(remaining!=null)"${audioTime(remaining)} left at ${speed}×" else "Duration loading",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress={if(effective.durationKnown && effective.totalMs>0)(elapsed.toDouble()/effective.totalMs).toFloat().coerceIn(0f,1f) else 0f},
                    modifier=Modifier.fillMaxWidth().padding(vertical=10.dp).height(5.dp).semantics{contentDescription="Book position"})
                Text(if(effective.durationKnown)"${audioTime(elapsed)} / ${audioTime(effective.totalMs)}" else audioTime(elapsed),style=MaterialTheme.typography.titleMedium)
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    AudioSkipButton(-60,state?.loading!=true){skip(-60)}
                    AudioSkipButton(-10,state?.loading!=true){skip(-10)}
                    FilledIconButton(onClick={when{state?.playing==true->AudiobookController.pause(context);state==null->AudiobookController.start(context,book.id);else->AudiobookController.play(context)}},
                        modifier=Modifier.size(72.dp),enabled=state?.loading!=true) {
                        Icon(if(state?.playing==true)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if(state?.playing==true)"Pause audiobook" else "Play audiobook",Modifier.size(38.dp))
                    }
                    AudioSkipButton(10,state?.loading!=true){skip(10)}
                    AudioSkipButton(60,state?.loading!=true){skip(60)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick={speedMenu=true}){Text("${speed}× speed")}
                        DropdownMenu(speedMenu,{speedMenu=false}) { listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f).forEach{rate->
                            DropdownMenuItem(text={Text("${rate}×")},onClick={speedMenu=false
                                if(state!=null)AudiobookController.speed(context,rate)
                                else scope.launch{runCatching{store.setPlaybackSpeed(book.id,rate)}.onFailure{error=it.message}}
                            }) }
                        }
                    }
                    TextButton(onClick={AudiobookController.stop(context)},enabled=state!=null){Icon(Icons.Outlined.Stop,null,Modifier.size(20.dp));Text("Stop")}
                    state?.sleepRemainingSeconds?.let{seconds->TextButton(onClick={timer=true}){Icon(Icons.Outlined.Bedtime,null,Modifier.size(16.dp));Text(audioTime(seconds*1000))}}
                }
                if(state?.loading==true){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading audio…")}
                (state?.error ?: error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            }
            cue?.text?.takeIf{it.isNotBlank()}?.let{text->item {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Current passage",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                        Text(text,Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyLarge)
                    }
                }
            }}
            item(key="chapter-position") { ChapterPositionControl(chapterIndex,position-chapter.startMs,chapter.durationMs,state?.loading!=true){seek(chapter.track,chapter.startMs+it)} }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    TextButton(onClick={section="Chapters"}){Icon(Icons.Outlined.List,null,Modifier.size(18.dp));Text("Chapters")}
                    TextButton(onClick={section="Bookmarks"}){Icon(Icons.Outlined.Bookmarks,null,Modifier.size(18.dp));Text("Bookmarks")}
                }
            }
        }
    }
    section?.let{title->ModalBottomSheet(onDismissRequest={section=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=620.dp),contentPadding=PaddingValues(bottom=32.dp)) {
            item{Text(title,Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.headlineSmall)}
            when(title) {
                "Chapters" -> items(chapters.indices.toList()){i->val c=chapters[i]
                    ListItem(headlineContent={Text(c.title)},supportingContent={Text(if(c.durationMs>0)audioTime(c.durationMs) else "Duration pending")},
                        leadingContent={Text("${i+1}")},trailingContent={if(i==chapterIndex)Icon(Icons.Outlined.Headphones,"Current chapter")},
                        modifier=Modifier.fillMaxWidth().clickable{jump=AudioJump(c.track,c.startMs,"chapter ${i+1}")})
                }
                "Bookmarks" -> {
                    item{TextButton(onClick={scope.launch{runCatching{store.addBookmark(book.id,index,position)}.onFailure{error=it.message}}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.BookmarkAdd,null);Text("Bookmark this position")}}
                    if(book.bookmarks.isEmpty())item{Text("No bookmarks yet.",Modifier.padding(20.dp))}
                    items(book.bookmarks,key={it.id}){mark->val c=chapters.currentChapter(mark.track,mark.positionMs)
                        ListItem(headlineContent={Text(mark.label.ifBlank{chapters[c].title})},supportingContent={Text("Chapter ${c+1} · ${audioTime(mark.positionMs-chapters[c].startMs)}")},
                            trailingContent={IconButton(onClick={scope.launch{runCatching{store.removeBookmark(book.id,mark.id)}.onFailure{error=it.message}}}){Icon(Icons.Outlined.DeleteOutline,"Remove audio bookmark")}},
                            modifier=Modifier.fillMaxWidth().clickable{jump=AudioJump(mark.track,mark.positionMs,"this bookmark")})
                    }
                }
                else -> {
                    item{Text("Return to a point before an accidental jump. Opening this list does not change playback.",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall)}
                    if(book.history.isEmpty())item{Text("No earlier listening points.",Modifier.padding(20.dp))}
                    items(book.history.asReversed()){point->val c=chapters.currentChapter(point.track,point.positionMs)
                        ListItem(headlineContent={Text("${point.reason} · ${chapters[c].title}")},supportingContent={Text("Chapter ${c+1} · ${audioTime(point.positionMs-chapters[c].startMs)}")},
                            modifier=Modifier.fillMaxWidth().clickable{jump=AudioJump(point.track,point.positionMs,"this earlier position")})
                    }
                }
            }
        }
    }}
    jump?.let{target->AlertDialog(onDismissRequest={jump=null;section=null},title={Text("Jump to ${target.label}?")},
        text={Text("Playback will move from its current position. The previous point stays available in position recovery.")},
        confirmButton={TextButton(onClick={jump=null;section=null;seek(target.track,target.position)}){Text("Jump")}},
        dismissButton={TextButton(onClick={jump=null;section=null}){Text("Cancel")}})}
    if(timer) {
        val options=AudioPreferences.load(context)
        AlertDialog(onDismissRequest={timer=false},title={Text("Sleep timer")},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            listOf(options.sleepMinutes,5,15,30,45,60,90).distinct().forEach{minutes->
                TextButton(onClick={AudiobookController.sleep(context,minutes);timer=false},modifier=Modifier.fillMaxWidth()){Text("$minutes minutes${if(minutes==options.sleepMinutes)" · preset" else ""}")}
            }
        }},confirmButton={TextButton(onClick={AudiobookController.clearSleep(context);timer=false}){Text("Turn off timer")}},dismissButton={TextButton(onClick={timer=false}){Text("Close")}})
    }
    if(settings)AudioSettingsSheet{settings=false}
}

@Composable private fun AudioSkipButton(seconds:Int,enabled:Boolean,click:()->Unit) {
    FilledTonalButton(onClick=click,enabled=enabled,modifier=Modifier.width(52.dp).height(64.dp)
        .semantics{contentDescription="${if(seconds<0)"Back" else "Forward"} ${kotlin.math.abs(seconds)} seconds"},contentPadding=PaddingValues(2.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(if(seconds<0)Icons.Outlined.Replay else Icons.Outlined.Forward,null,Modifier.size(22.dp))
            Text((if(seconds<0)"−" else "+")+(if(kotlin.math.abs(seconds)==60)"1m" else "10s"),style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun ChapterPositionControl(chapter:Int,position:Long,duration:Long,enabled:Boolean,seek:(Long)->Unit) {
    var adjusting by rememberSaveable(chapter){mutableStateOf(false)}
    var proposed by rememberSaveable(chapter){mutableStateOf<Float?>(null)}
    val fraction=proposed ?: if(duration>0)(position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(if(duration>0)"Chapter · ${audioTime(position.coerceAtLeast(0))} / ${audioTime(duration)}" else "Chapter duration loading",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={adjusting=!adjusting;proposed=null},enabled=enabled && duration>0){Icon(if(adjusting)Icons.Outlined.Close else Icons.Outlined.Lock,null,Modifier.size(14.dp));Text(if(adjusting)"Cancel" else "Adjust")}
        }
        if(adjusting) {
            Slider(fraction,{proposed=it},enabled=enabled,valueRange=0f..1f,modifier=Modifier.semantics{contentDescription="Chapter position"})
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Move to ${audioTime((fraction*duration).toLong())}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={seek((fraction*duration).toLong());adjusting=false;proposed=null},enabled=proposed!=null){Text("Apply position")}
            }
        } else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(3.dp).semantics{contentDescription="Chapter progress · locked"})
    }
}
