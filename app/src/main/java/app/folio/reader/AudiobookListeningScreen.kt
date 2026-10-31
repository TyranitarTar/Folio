@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.rememberScrollState
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
    var locked by rememberSaveable(book.id){mutableStateOf(true)}
    var passageSettings by remember{mutableStateOf(false)}
    var quotes by remember{mutableStateOf(false)}
    var savedNotice by remember{mutableStateOf<String?>(null)}
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
    val currentCue=if(state!=null)state.cue.takeIf{!state.loading} else book.tracks[index].cues.firstOrNull{position>=it.startMs && position<it.endMs}
    val display=displayPassage(effective,index,position)
    val retained=androidx.lifecycle.viewmodel.compose.viewModel<PlayerPassageState>(key="passage:${book.id}").passage
    val cue=retained.resolve(effective,display,state?.seekRevision ?: 0L)
    val awakeModifier=playerAwakeModifier(book.id,linkedBook!=null,state?.playing==true || state?.loading==true)
    val controlsEnabled=state?.loading!=true
    val library=(context.applicationContext as FolioApp).library
    fun saveQuote() {
        val passage=cue ?: return
        val linked=linkedBook ?: return
        val locator=RecordedNarration.locator(passage) ?: return
        val passageTrack=retained.track
        scope.launch{runCatching{library.addMark(linked,locator,true,audioId=book.id,audioTrack=passageTrack,audioPositionMs=passage.startMs)}
            .onSuccess{savedNotice="Passage saved to highlights & quotes."}.onFailure{error="The passage could not be saved."}}
    }
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
        IconButton(onClick={locked=!locked}) {
            Icon(if(locked)Icons.Outlined.Lock else Icons.Outlined.LockOpen,if(locked)"Unlock chapter seeking" else "Lock chapter seeking")
        }
        Box {
            IconButton(onClick={menu=true}){Icon(Icons.Outlined.MoreVert,"Player menu")}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text("Current passage settings")},onClick={menu=false;passageSettings=true})
                DropdownMenuItem(text={Text("Chapters & position")},onClick={menu=false;section="Chapters"})
                DropdownMenuItem(text={Text("Saved highlights & quotes")},onClick={menu=false;quotes=true})
                if(linkedBook!=null && cue!=null)DropdownMenuItem(text={Text("Save passage as quote")},onClick={menu=false;saveQuote()})
                linkedBook?.let{linked->DropdownMenuItem(text={Text("Read book")},onClick={menu=false;read(linked)})}
                DropdownMenuItem(text={Text("Add bookmark")},onClick={menu=false;scope.launch{runCatching{store.addBookmark(book.id,index,position)}.onFailure{error=it.message}}})
                DropdownMenuItem(text={Text("Sleep timer")},enabled=state!=null,onClick={menu=false;timer=true})
                DropdownMenuItem(text={Text("Bluetooth & playback settings")},onClick={menu=false;settings=true})
                DropdownMenuItem(text={Text("Recover a previous position")},onClick={menu=false;section="Position recovery"})
            }
        }
    })}) { padding ->
        LazyColumn(Modifier.fillMaxSize().then(awakeModifier).padding(padding),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                    Box(Modifier.width(104.dp).height(140.dp)){AudiobookCover(book,epubs,Modifier.fillMaxSize())}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(book.author.isNotBlank())Text(book.author,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("Chapter ${chapterIndex+1} of ${chapters.size}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        if(book.preview)Text("Preview recording",style=MaterialTheme.typography.labelMedium)
                        if(book.editionIncomplete) Text(if(book.totalSentenceCount>0)
                            "${if(book.audioIncomplete) "Incomplete recording" else "Incomplete passage links"} · ${book.generatedSentenceCount} of ${book.totalSentenceCount} passages available"
                            else "${if(book.audioIncomplete) "Incomplete recording" else "Incomplete passage links"} · ${book.tracks.size} audio files available",style=MaterialTheme.typography.labelMedium)
                        linkedBook?.let{linked->TextButton(onClick={read(linked)},contentPadding=PaddingValues(0.dp)){Icon(Icons.Outlined.AutoStories,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Read FolioBook")}}
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(if(book.audioIncomplete) "Available audio" else "Book",style=MaterialTheme.typography.labelLarge)
                    Text(if(remaining!=null)"${audioTime(remaining)} ${if(book.audioIncomplete) "available" else "left"} at ${speed}×" else "Duration loading",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress={if(effective.durationKnown && effective.totalMs>0)(elapsed.toDouble()/effective.totalMs).toFloat().coerceIn(0f,1f) else 0f},
                    modifier=Modifier.fillMaxWidth().padding(vertical=10.dp).height(5.dp).semantics{contentDescription=if(book.audioIncomplete) "Available audio position" else "Book position"})
                Text(if(effective.durationKnown)"${audioTime(elapsed)} / ${audioTime(effective.totalMs)}" else audioTime(elapsed),style=MaterialTheme.typography.titleMedium)
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    AudioSkipButton(-60,controlsEnabled,Modifier.weight(1f)){skip(-60)}
                    AudioSkipButton(-10,controlsEnabled,Modifier.weight(1f)){skip(-10)}
                    FilledIconButton(onClick={when{state?.playing==true->AudiobookController.pause(context);state==null->AudiobookController.start(context,book.id);else->AudiobookController.play(context)}},
                        modifier=Modifier.padding(horizontal=6.dp).weight(1.3f).height(84.dp).semantics{contentDescription=if(state?.playing==true)"Pause audiobook" else "Play audiobook"},enabled=controlsEnabled) {
                        Icon(if(state?.playing==true)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            null,Modifier.size(38.dp))
                    }
                    AudioSkipButton(10,controlsEnabled,Modifier.weight(1f)){skip(10)}
                    AudioSkipButton(60,controlsEnabled,Modifier.weight(1f)){skip(60)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={speedMenu=true},enabled=true){Text("${speed}× speed")}
                    TextButton(onClick={AudiobookController.stop(context)},enabled=state!=null){Icon(Icons.Outlined.Stop,null,Modifier.size(20.dp));Text("Stop")}
                    state?.sleepRemainingSeconds?.let{seconds->TextButton(onClick={timer=true}){Icon(Icons.Outlined.Bedtime,null,Modifier.size(16.dp));Text(audioTime(seconds*1000))}}
                }
                if(locked)Text("Chapter seeking locked",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                state?.sleepLabel?.let{Text(it,style=MaterialTheme.typography.labelMedium)}
                savedNotice?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                if(state?.loading==true){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading audio…")}
                (state?.error ?: error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            }
            if(linkedBook!=null || cue!=null)item(key="spoken-passage") {
                Box(Modifier.fillMaxWidth()) {
                    val passageHeight=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.22f).coerceIn(128.dp,220.dp)
                    Surface(Modifier.fillMaxWidth().height(passageHeight).clickable{passageSettings=true},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Current passage",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            Crossfade(cue?.text,label="Spoken passage",modifier=Modifier.weight(1f)) { text ->
                                val scroll=rememberScrollState()
                                LaunchedEffect(text){scroll.scrollTo(0)}
                                Text(text ?: "Timed passages will appear when spoken.",Modifier.fillMaxSize().verticalScroll(scroll).padding(top=6.dp),style=MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            item {
                ChapterPositionControl(chapterIndex,position-chapter.startMs,chapter.durationMs,locked,controlsEnabled,{locked=!locked}){seek(chapter.track,chapter.startMs+it)}
            }
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    TextButton(onClick={section="Chapters"}){Icon(Icons.Outlined.List,null,Modifier.size(18.dp));Text("Chapters & position")}
                    TextButton(onClick={section="Bookmarks"}){Icon(Icons.Outlined.Bookmarks,null,Modifier.size(18.dp));Text("Bookmarks")}
                }
            }
        }
    }
    section?.let{title->ModalBottomSheet(onDismissRequest={section=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=620.dp),contentPadding=PaddingValues(bottom=32.dp)) {
            item{Text(title,Modifier.padding(horizontal=20.dp,vertical=12.dp),style=MaterialTheme.typography.headlineSmall)}
            when(title) {
                "Chapters" -> {
                    item { TextButton(onClick={locked=!locked},modifier=Modifier.padding(horizontal=20.dp)) {
                        Icon(if(locked)Icons.Outlined.Lock else Icons.Outlined.LockOpen,null,Modifier.size(18.dp))
                        Text(if(locked) "Unlock chapter seeking" else "Lock chapter seeking")
                    } }
                    items(chapters.indices.toList()){i->val c=chapters[i]
                    ListItem(headlineContent={Text(c.title)},supportingContent={Text(if(c.durationMs>0)audioTime(c.durationMs) else "Duration pending")},
                        leadingContent={Text("${i+1}")},trailingContent={if(i==chapterIndex)Icon(Icons.Outlined.Headphones,"Current chapter")},
                        modifier=Modifier.fillMaxWidth().clickable(enabled=!locked){jump=AudioJump(c.track,c.startMs,"chapter ${i+1}")})
                }
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
    if(speedMenu)AudioSpeedDialog(speed,{rate->
        if(state!=null)AudiobookController.speed(context,rate)
        else scope.launch{runCatching{store.setPlaybackSpeed(book.id,rate)}.onFailure{error=it.message}}
    },{speedMenu=false})
    if(timer)AudioSleepDialog(state,AudioPreferences.load(context).sleepMinutes,currentCue!=null && state?.loading!=true,
        {minutes,fade->AudiobookController.sleep(context,minutes,fade)},
        {passage->AudiobookController.sleepBoundary(context,passage)},
        {AudiobookController.extendSleep(context)},{AudiobookController.clearSleep(context)},{timer=false})
    if(passageSettings) PassageTimingDialog(book.passageOffsetMs, { offset ->
        scope.launch { runCatching { store.setPassageOffset(book.id,offset) }.onFailure { error="Passage timing could not be saved." } }
        passageSettings=false
    }, { passageSettings=false })
    if(quotes)SavedQuotesSheet(library,{b->quotes=false;context.startActivity(android.content.Intent(context,ReaderActivity::class.java).putExtra("book",b.id))},{quotes=false})
    if(settings)AudioSettingsSheet{settings=false}
}

@Composable private fun AudioSkipButton(seconds:Int,enabled:Boolean,modifier:Modifier=Modifier,click:()->Unit) {
    FilledTonalButton(onClick=click,enabled=enabled,modifier=modifier.padding(horizontal=2.dp).height(80.dp)
        .semantics{contentDescription="${if(seconds<0)"Back" else "Forward"} ${kotlin.math.abs(seconds)} seconds"},contentPadding=PaddingValues(2.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(if(seconds<0)Icons.Outlined.Replay else Icons.Outlined.Forward,null,Modifier.size(22.dp))
            Text((if(seconds<0)"−" else "+")+(if(kotlin.math.abs(seconds)==60)"1m" else "10s"),style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun ChapterPositionControl(chapter:Int,position:Long,duration:Long,locked:Boolean,enabled:Boolean,toggle:()->Unit,seek:(Long)->Unit) {
    var proposed by rememberSaveable(chapter){mutableStateOf<Float?>(null)}
    LaunchedEffect(locked,chapter) { proposed=null }
    val fraction=proposed ?: if(duration>0)(position.toDouble()/duration).toFloat().coerceIn(0f,1f) else 0f
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(if(duration>0)"Chapter · ${audioTime(position.coerceAtLeast(0))} / ${audioTime(duration)}" else "Chapter duration loading",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=toggle){Icon(if(locked)Icons.Outlined.Lock else Icons.Outlined.LockOpen,null,Modifier.size(14.dp));Text(if(locked)"Unlock" else "Lock")}
        }
        if(!locked) {
            Slider(fraction,{proposed=it},enabled=enabled && duration>0,valueRange=0f..1f,modifier=Modifier.semantics{contentDescription="Chapter position"})
            if(proposed!=null) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Move to ${audioTime((fraction*duration).toLong())}",style=MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick={proposed=null}){Text("Cancel")}; TextButton(onClick={seek((fraction*duration).toLong());proposed=null},enabled=enabled){Text("Apply")} }
            }
        } else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(3.dp).semantics{contentDescription="Chapter progress · locked"})
    }
}

@Composable private fun PassageTimingDialog(offsetMs:Long, apply:(Long)->Unit, dismiss:()->Unit) {
    var value by rememberSaveable { mutableFloatStateOf(offsetMs / 1000f) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Current passage timing")},text={
        Column {
            Text("Show passages earlier or later than their recorded timing. Audio playback does not change.")
            Text("${if(value>0) "+" else ""}${"%.1f".format(java.util.Locale.US,value)} seconds · ${if(value>0) "later" else if(value<0) "earlier" else "original timing"}")
            Slider(value,{value=it},valueRange=-30f..30f,steps=119,modifier=Modifier.semantics{contentDescription="Passage timing offset"})
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={value=(kotlin.math.round(value*10f)-1f).div(10f).coerceAtLeast(-30f)}){Text("Earlier 0.1 s")}
                TextButton(onClick={value=(kotlin.math.round(value*10f)+1f).div(10f).coerceAtMost(30f)}){Text("Later 0.1 s")}
                TextButton(onClick={value=0f}){Text("Reset")}
            }
        }
    },confirmButton={TextButton(onClick={apply(kotlin.math.round(value*1000).toLong())}){Text("Apply")}},dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})
}
