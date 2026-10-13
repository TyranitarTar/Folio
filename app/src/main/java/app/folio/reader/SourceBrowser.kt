@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceBrowserState(val source: BookSource? = null, val language: String = "en", val query: String = "",
    val books: List<SourceBook> = emptyList(), val next: String? = null, val busy: Boolean = false,
    val cached: Boolean = false, val error: String? = null, val selected: SourceBook? = null,
    val editionBusy: Boolean = false, val editionError: String? = null)

class SourceBrowserModel(app: Application) : AndroidViewModel(app) {
    val library = (app as FolioApp).library
    val downloads = (app as FolioApp).bookDownloads
    private val preferences = app.getSharedPreferences("book-sources", 0)
    val state = MutableStateFlow(SourceBrowserState(language = preferences.getString("language", "en").takeIf { it in setOf("en", "da") } ?: "en"))
    private val catalog = BookCatalog(SourceHttp(File(app.cacheDir, "source-catalogs")))
    private var searchJob: Job? = null
    private var editionJob: Job? = null
    private var searchGeneration = 0L
    private var editionGeneration = 0L
    fun source(source: BookSource?) {
        searchJob?.cancel(); searchGeneration++; closeEdition()
        state.value = state.value.copy(source = source, query = "", books = emptyList(), next = null, error = null, cached = false, busy = false)
        if (source != null) search()
    }
    fun language(language: String) {
        if (language !in setOf("en", "da")) return
        preferences.edit().putString("language", language).apply()
        closeEdition(); state.value = state.value.copy(language = language, books = emptyList(), next = null)
        search()
    }
    fun query(query: String) { state.value = state.value.copy(query = query.take(200)); search(debounce = true) }
    fun search(more: Boolean = false, debounce: Boolean = false) {
        val snapshot = state.value
        val source = snapshot.source ?: return
        val next = if (more) snapshot.next ?: return else null
        val ticket = ++searchGeneration
        searchJob?.cancel()
        state.value = state.value.copy(busy = true, error = null, books = if (more) snapshot.books else emptyList(), next = if (more) next else null)
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(450)
                val page = withContext(Dispatchers.IO) { catalog.search(source, snapshot.language, snapshot.query, next) }
                if (ticket == searchGeneration) state.value = state.value.copy(books = ((if (more) snapshot.books else emptyList()) + page.books).distinctBy { it.key },
                    next = page.next, busy = false, cached = page.cached)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (ticket == searchGeneration) state.value = state.value.copy(busy = false, error = e.message ?: "The source could not be loaded.") }
        }
    }
    fun select(book: SourceBook) {
        val ticket = ++editionGeneration
        editionJob?.cancel()
        state.value = state.value.copy(selected = book, editionBusy = true, editionError = null)
        editionJob = viewModelScope.launch {
            try {
                val edition = withContext(Dispatchers.IO) { catalog.edition(book) }
                if (ticket == editionGeneration) state.value = state.value.copy(selected = edition, editionBusy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (ticket == editionGeneration) state.value = state.value.copy(editionBusy = false, editionError = e.message ?: "This edition is unavailable.") }
        }
    }
    fun closeEdition() { editionGeneration++; editionJob?.cancel(); state.value = state.value.copy(selected = null, editionBusy = false, editionError = null) }
    fun download(book: SourceBook) {
        viewModelScope.launch { try { withContext(Dispatchers.IO) { downloads.enqueue(book) } }
            catch (e: Exception) { state.value = state.value.copy(editionError = e.message ?: "The download could not be queued.") } }
    }
    fun cancel(key: String) { viewModelScope.launch(Dispatchers.IO) { downloads.cancel(key) } }
    fun clearFinished() { viewModelScope.launch(Dispatchers.IO) { downloads.clearFinished() } }
}

@Composable
fun SourceBrowser(open: (Book) -> Unit, importLocal: () -> Unit, sample: () -> Unit) {
    val model: SourceBrowserModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val jobs by model.downloads.entries.collectAsStateWithLifecycle()
    val books by model.library.books.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("Sources") }
    BackHandler(state.selected != null || (section == "Sources" && state.source != null)) {
        if (state.selected != null) model.closeEdition() else model.source(null)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Sources", "Downloads", "Local").forEach { name ->
                FilterChip(section == name, { section = name }, label = { Text(if (name == "Downloads" && jobs.any { !it.finished }) "$name (${jobs.count { !it.finished }})" else name) })
            }
        }
        when (section) {
            "Local" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SourceAction("Local EPUB files", "Import books from your device", Icons.Outlined.FolderOpen, importLocal) }
                item { SourceAction("The Quiet Hour", "Open the English and Danish sample", Icons.Outlined.AutoStories, sample) }
                item { Text("Imported books are copied into your private library.", style = MaterialTheme.typography.bodyMedium) }
            }
            "Downloads" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (jobs.isEmpty()) item { Text("Your book downloads will appear here.") }
                if (jobs.any { it.finished }) item {
                    TextButton(onClick = model::clearFinished) { Text("Clear finished downloads") }
                    Text("Frees download files and clears this list's finished entries. Library books remain available.", style = MaterialTheme.typography.bodySmall)
                }
                items(jobs, key = { it.key }) { job -> DownloadCard(job, books.firstOrNull { it.id == job.bookId }, open, { model.cancel(job.key) }, { model.download(job.book) }) }
            }
            else -> if (state.source == null) LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("Book sources", style = MaterialTheme.typography.titleMedium) }
                item { SourceAction("Project Gutenberg", "English and Danish classics · EPUB downloads", Icons.Outlined.Public) { model.source(BookSource.GUTENBERG) } }
                item { SourceAction("Wikisource", "English and Danish works · prepared EPUBs", Icons.Outlined.Public) { model.source(BookSource.WIKISOURCE) } }
                item { Text("Choose a source, find a work, and download it directly into your library. Downloaded books are available offline.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.source(null) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to sources") }
                    Text(state.source!!.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { model.search() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "Refresh source") }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.language == "en", { model.language("en") }, label = { Text("English") })
                    FilterChip(state.language == "da", { model.language("da") }, label = { Text("Dansk") })
                }
                OutlinedTextField(state.query, model::query, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
                    label = { Text("Search this source") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (state.cached) Text("Showing saved catalog results; the source could not be refreshed.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                state.error?.let { error -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f)); TextButton(onClick = { model.search() }) { Text("Retry") }
                } }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.busy && state.error == null && state.books.isEmpty()) item { Text("No matching works found. Try a different search.") }
                    items(state.books, key = { it.key }) { book ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { model.select(book) }) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (book.coverUrl != null) AsyncImage(book.coverUrl, null, Modifier.size(48.dp, 68.dp), contentScale = ContentScale.Fit)
                                Column(Modifier.weight(1f)) {
                                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (book.summary.isNotBlank() && book.summary != book.author) Text(book.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (book.language == "da") "Dansk" else "English", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (state.next != null) item { TextButton(onClick = { model.search(more = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more works") } }
                }
            }
        }
    }
    state.selected?.let { book ->
        val job = jobs.firstOrNull { it.key == book.key }
        val imported = books.firstOrNull { it.id == job?.bookId || it.origin?.key == book.key }
        ModalBottomSheet(onDismissRequest = model::closeEdition) {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(book.title, style = MaterialTheme.typography.headlineSmall) }
                if (book.author.isNotBlank()) item { Text(book.author) }
                item { Text("${book.source.label} · ${if (book.language == "da") "Dansk" else "English"}", style = MaterialTheme.typography.labelLarge) }
                if (state.editionBusy) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Checking edition…") } }
                state.editionError?.let { error -> item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.select(book) }) { Text("Retry edition") }
                } }
                if (book.summary.isNotBlank()) item { Text(book.summary, style = MaterialTheme.typography.bodyMedium) }
                if (book.rights.isNotBlank()) item { Text(book.rights, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    when {
                        imported != null -> Button(onClick = { model.closeEdition(); open(imported) }, Modifier.fillMaxWidth()) { Text("Read in library") }
                        job != null && !job.finished -> DownloadCard(job, null, open, { model.cancel(job.key) }, { model.download(book) })
                        !state.editionBusy && state.editionError == null && book.downloadUrl != null -> Button(onClick = { model.download(book) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(if (job?.state == "Failed") "Retry download" else "Download to library")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = click)) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, leadingContent = { Icon(icon, null) })
    }
}

@Composable
private fun DownloadCard(job: BookDownload, imported: Book?, open: (Book) -> Unit, cancel: () -> Unit, retry: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(job.book.title, style = MaterialTheme.typography.titleSmall)
        Text("${job.book.source.label} · ${job.state}", style = MaterialTheme.typography.labelMedium)
        if (job.message.isNotBlank()) Text(job.message, style = MaterialTheme.typography.bodySmall)
        if (!job.finished) {
            if (job.total > 0) LinearProgressIndicator(progress = { (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (job.state == "Downloading") Text(if (job.total > 0) "${job.bytes / 1024} / ${job.total / 1024} KB" else "${job.bytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = cancel) { Text("Cancel download") }
        } else if (imported != null) Button(onClick = { open(imported) }) { Text("Read") }
        else TextButton(onClick = retry) { Text(if (job.state == "Complete") "Download again" else "Retry") }
    } }
}
