@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.folio.reader

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@Composable internal fun SavedQuotesSheet(store: LibraryStore, open: (Book) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val books by store.books.collectAsStateWithLifecycle()
    val audio by (context.applicationContext as FolioApp).audiobooks.books.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var remove by remember { mutableStateOf<Pair<Book,Mark>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest=dismiss, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill=false), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("Saved highlights & quotes", style=MaterialTheme.typography.headlineSmall) }
            if(books.none { it.highlights.isNotEmpty() }) item {
                Text("Save selected text with Highlight in an eBook, or choose Save passage as quote in a FolioBook player.")
            }
            error?.let { item { Text(it, color=MaterialTheme.colorScheme.error) } }
            books.filter { it.highlights.isNotEmpty() }.sortedBy { it.title.lowercase() }.forEach { book ->
                item(key="book:${book.id}") { Text(book.title, style=MaterialTheme.typography.titleLarge) }
                items(book.highlights, key={ "${book.id}:${it.id}" }) { mark ->
                    val recording=audio.firstOrNull { it.id==mark.audioId }
                        ?: RecordedNarration.preferred(audio,book.id)
                    val audioTarget=if(recording?.id==mark.audioId && mark.audioTrack!=null && mark.audioPositionMs!=null)
                        RecordedNarration.Target(mark.audioTrack,mark.audioPositionMs)
                        else recording?.let { RecordedNarration.find(it,mark.position()) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(mark.quote, style=MaterialTheme.typography.bodyLarge)
                            mark.position()?.href?.toString()?.let { Text(it.substringAfterLast('/'), style=MaterialTheme.typography.labelSmall) }
                            if(mark.note.isNotBlank()) Text(mark.note)
                            audioTarget?.let { Text("Audio · file ${it.track+1} · ${audioTime(it.positionMs)}", style=MaterialTheme.typography.labelMedium) }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick={scope.launch {
                                    runCatching {
                                        if(AudiobookController.state.value.bookId==book.id) {
                                            AudiobookController.pause(context)
                                            withTimeout(3000){AudiobookController.state.first { it.bookId!=book.id || !it.playing }}
                                        }
                                        if(SpeechController.state.value.bookId==book.id) {
                                            SpeechController.pause(context)
                                            withTimeout(3000){SpeechController.state.first { it.bookId!=book.id || !it.playing }}
                                        }
                                        val locator=mark.position() ?: throw IllegalStateException("Saved location unavailable")
                                        store.progress(book.id,locator); dismiss(); open(book)
                                    }
                                        .onFailure { error="This quote could not be opened." }
                                }}) { Text("Open text") }
                                if(recording!=null && audioTarget!=null && audioTarget.track in recording.tracks.indices)
                                    TextButton(onClick={
                                        AudiobookController.start(context,recording.id,audioTarget.track,audioTarget.positionMs,false)
                                        context.startActivity(Intent(context,AudiobookActivity::class.java).putExtra("audiobook",recording.id))
                                        dismiss()
                                    }) { Text("Open audio") }
                                TextButton(onClick={remove=book to mark}) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { (book,mark) -> AlertDialog(onDismissRequest={remove=null}, title={Text("Remove saved quote?")},
        text={Text("This removes the original highlight and its saved quote from ${book.title}.")},
        confirmButton={TextButton(onClick={scope.launch { runCatching { store.removeMark(book.id,mark.id) }
            .onSuccess { remove=null }.onFailure { error="The quote could not be removed.";remove=null } }}) { Text("Remove") }},
        dismissButton={TextButton(onClick={remove=null}) { Text("Keep") }}) }
}
