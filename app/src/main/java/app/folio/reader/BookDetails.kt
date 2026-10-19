@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

internal data class BookChapter(val label: String, val depth: Int, val locator: Locator)
private data class BookInfo(val loading: Boolean = true, val description: String = "", val chapters: List<BookChapter> = emptyList(), val error: String? = null)

@Composable
internal fun BookDetails(book: Book, store: LibraryStore, read: (Book) -> Unit, speech: (Book) -> Unit,
    go: (Locator) -> Unit, back: () -> Unit, shelf: () -> Unit, remove: () -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val recordings by (context.applicationContext as FolioApp).audiobooks.books.collectAsStateWithLifecycle()
    val recording = RecordedNarration.preferred(recordings, book.id)
    val info by produceState(BookInfo(), book.id) {
        try {
            value = withContext(Dispatchers.IO) {
                val publication = (context.applicationContext as FolioApp).engine.open(store.file(book.id))
                try {
                    var chapterCount = 0
                    fun chapters(links: List<Link>, depth: Int = 0): List<BookChapter> {
                        require(depth <= 32 && chapterCount + links.size <= 10000) { "This book has an excessively nested contents list." }
                        chapterCount += links.size
                        return links.flatMap { link ->
                        val locator = publication.locatorFromLink(link)
                        (if (locator == null) emptyList() else listOf(BookChapter(link.title ?: "Section", depth, locator))) + chapters(link.children, depth + 1)
                    } }
                    BookInfo(false, publication.metadata.description?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }.orEmpty(),
                        chapters(publication.tableOfContents.ifEmpty { publication.readingOrder }))
                } finally { publication.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { value = BookInfo(false, error = "Book details could not be loaded. You can still try opening the reader.") }
    }
    var tab by rememberSaveable(book.id) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Book details") }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
    }, actions = { Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Book menu") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Assign shelf") }, onClick = { menu = false; shelf() })
            DropdownMenuItem(text = { Text("Remove book") }, onClick = { menu = false; remove() })
        }
    } }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Surface(shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { read(book) }, Modifier.weight(1f)) { Icon(Icons.Outlined.PlayArrow, null); Text(if (book.lastRead > 0) "Resume reading" else "Read book") }
                recording?.let { audio -> OutlinedButton(onClick = { openAudiobook(context, audio.id) }) { Icon(Icons.Outlined.Headphones, "Listen to recording") } }
                OutlinedButton(onClick = { speech(book) }) { Icon(Icons.Outlined.RecordVoiceOver, "Read aloud") }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    BookCover(book, store, Modifier.width(120.dp).height(176.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.headlineSmall)
                        Text(book.author.ifBlank { "Unknown author" }, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) {
                            Text(if (recording != null) "EPUB + narration · Local" else "EPUB · Local", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(if (book.lastRead == 0L) "Unread" else "${(book.progress * 100).toInt()}% read", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (book.shelves.isNotEmpty()) Text(book.shelves.joinToString(" · "), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
                if (info.description.isNotBlank()) Text(info.description, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                TabRow(tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Chapters${if (info.loading) "" else " · ${info.chapters.size}"}") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Annotations · ${book.bookmarks.size + book.highlights.size}") })
                }
                if (info.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                info.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (tab == 0) items(info.chapters) { chapter ->
                Row(Modifier.fillMaxWidth().clickable { go(chapter.locator) }.padding(start = (16 + chapter.depth * 14).dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Article, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chapter.label, Modifier.padding(start = 16.dp).weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            } else {
                val marks = book.bookmarks + book.highlights
                if (marks.isEmpty()) item { Text("Bookmarks, highlights and notes appear here. Long-press text in the reader to add a note.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(marks, key = { it.id }) { mark ->
                    Column(Modifier.fillMaxWidth().clickable { mark.position()?.let(go) }.padding(16.dp)) {
                        Text(mark.label, style = MaterialTheme.typography.bodyLarge)
                        if (mark.note.isNotBlank()) Text(mark.note, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
