package app.folio.reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as FolioApp).library
    val books = store.books
    val busy = MutableStateFlow(false)
    val loading = MutableStateFlow(true)
    val notice = MutableStateFlow<String?>(null)
    init { viewModelScope.launch {
        runCatching { store.load() }.onFailure { notice.value = "The library could not be loaded. Your stored files have been kept." }
        loading.value = false
    } }
    private val importQueue = ArrayDeque<Pair<List<Uri>, ((Book) -> Unit)?>>()
    private var importJob: Job? = null
    fun cancelImport() { importQueue.clear(); importJob?.cancel() }
    fun import(uris: List<Uri>, open: ((Book) -> Unit)? = null) {
        if (uris.isEmpty()) return
        if (importQueue.size >= 16 || uris.size > 500) { notice.value = "Too many pending imports. Wait for this batch to finish."; return }
        importQueue.addLast(uris to open)
        if (importJob?.isActive == true) return
        if (busy.value) { notice.value = "Finish the current storage operation before importing."; importQueue.clear(); return }
        busy.value = true
        importJob = viewModelScope.launch {
            try { while (importQueue.isNotEmpty()) {
            val (uris, open) = importQueue.removeFirst()
            var imported = 0
            val errors = mutableListOf<String>()
            for (uri in uris) {
                try { val book = store.importUri(uri); runCatching { book }.onSuccess { book ->
                    imported++
                    if (uris.size == 1) open?.invoke(book)
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { errors += error.message ?: "This book could not be imported." }
            }
            notice.value = if (errors.isEmpty()) "$imported ${if (imported == 1) "book" else "books"} ready to read"
                else "Imported $imported of ${uris.size} books. ${errors.distinct().joinToString(" ")}"
            } } catch (cancelled: CancellationException) { notice.value = "Import cancelled. Completed books have been kept."; throw cancelled }
            finally { busy.value = false; importJob = null }
        }
    }
    fun sample(open: (Book) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            runCatching { store.importSample() }.onSuccess(open).onFailure { notice.value = "The sample could not be opened." }
            busy.value = false
        }
    }
    fun storageOperation(action: suspend () -> String, finished: () -> Unit = {}) {
        if (busy.value) { notice.value = "Wait for the current import or storage operation to finish."; return }
        busy.value = true
        viewModelScope.launch {
            try { notice.value = action(); finished() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice.value = error.message ?: "The storage operation could not finish. Existing books have been kept." }
            finally { busy.value = false }
        }
    }
    fun remove(id: String) { viewModelScope.launch {
        runCatching { store.removeBook(id) }.onFailure { notice.value = "The book could not be removed." }
    } }
}

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FolioHome(model, ::openBook, ::openSpeech) }
        handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val uri = try { when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } } catch (_: RuntimeException) { null }
        if (intent.action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
            intent.action = Intent.ACTION_MAIN
            if (uri == null || uri.scheme !in setOf("content", "file")) model.notice.value = "The shared item is not a readable EPUB file."
            else model.import(listOf(uri), ::openBook)
        }
    }
    private fun openBook(book: Book) { startActivity(Intent(this, ReaderActivity::class.java).putExtra("book", book.id)) }
    private fun openSpeech(book: Book) { startActivity(Intent(this, ReaderActivity::class.java).putExtra("book", book.id).putExtra("speechControls", true)) }
}

