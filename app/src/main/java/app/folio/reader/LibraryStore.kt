package app.folio.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import android.util.Size
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

data class Mark(val id: String, val locator: String, val label: String, val note: String = "") {
    fun json() = JSONObject().put("id", id).put("locator", JSONObject(locator)).put("label", label).put("note", note)
    fun position(): Locator? = runCatching { Locator.fromJSON(JSONObject(locator)) }.getOrNull()
    companion object {
        fun from(j: JSONObject) = Mark(j.getString("id"), j.getJSONObject("locator").toString(), j.getString("label"), j.optString("note"))
    }
}

data class Book(
    val id: String, val title: String, val author: String, val added: Long,
    val lastRead: Long = 0, val locator: String? = null, val progress: Double = 0.0,
    val bookmarks: List<Mark> = emptyList(), val highlights: List<Mark> = emptyList(),
    val shelves: List<String> = emptyList()
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("author", author)
        .put("added", added).put("lastRead", lastRead).put("locator", locator?.let(::JSONObject))
        .put("progress", progress).put("bookmarks", JSONArray(bookmarks.map { it.json() }))
        .put("highlights", JSONArray(highlights.map { it.json() })).put("shelves", JSONArray(shelves))
    fun position(): Locator? = locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    companion object {
        fun from(j: JSONObject): Book {
            val id = j.getString("id")
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid book identifier" }
            return Book(id, j.getString("title"), j.optString("author"), j.getLong("added"),
                j.optLong("lastRead"), j.optJSONObject("locator")?.toString(), j.optDouble("progress", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                j.optJSONArray("bookmarks").marks(), j.optJSONArray("highlights").marks(),
                j.optJSONArray("shelves")?.let { a -> (0 until a.length()).map { a.getString(it) }.distinct() }.orEmpty())
        }
    }
}

private fun JSONArray?.marks() = if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { Mark.from(getJSONObject(it)) }.getOrNull() }

@OptIn(org.readium.r2.shared.InternalReadiumApi::class)
class BookEngine(context: Context) {
    private val http = DefaultHttpClient()
    private val retriever = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))
    suspend fun open(file: File): Publication {
        val asset = retriever.retrieve(file).getOrElse { throw IllegalArgumentException("This file is not a readable EPUB.") }
        // The per-open hook is required by Readium 3.1.2; its constructor hook is shadowed.
        val publication = opener.open(asset, allowUserInteraction = false,
            onCreatePublication = { if (container !is SafePublicationContainer) container = SafePublicationContainer(container) }).getOrElse {
            asset.close()
            throw IllegalArgumentException("This EPUB could not be opened. Protected books are not supported.")
        }
        if (!publication.conformsTo(Publication.Profile.EPUB) || publication.readingOrder.isEmpty() || publication.isRestricted) {
            publication.close()
            throw IllegalArgumentException("Please choose an unprotected EPUB book.")
        }
        return publication
    }
}

class LibraryStore(private val context: Context, val engine: BookEngine) {
    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val database = AtomicFile(File(directory, "library.json"))
    private val mutex = Mutex()
    private var catalog: LibraryDatabase? = null
    val recovery = MutableStateFlow<String?>(null)
    val shelves = MutableStateFlow<List<String>>(emptyList())
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books = _books.asStateFlow()
    private var loaded = false
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressLock = Any()
    private val progressVersions = mutableMapOf<String, Long>()
    private val readingSessions = mutableMapOf<String, Long>()
    private val progressJobs = mutableMapOf<String, Job>()
    fun file(id: String) = File(directory, "$id.epub")
    fun cover(id: String) = File(directory, "$id.jpg")

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            val issues = mutableListOf<String>()
            val path = File(directory, "library.sqlite")
            val db = try { LibraryDatabase(path) } catch (damaged: android.database.sqlite.SQLiteDatabaseCorruptException) {
                val stamp = System.currentTimeMillis()
                for (source in listOf(path, File(path.path + "-wal"), File(path.path + "-shm"), File(path.path + "-journal"))) {
                    if (source.exists()) check(source.renameTo(File(directory, source.name + ".corrupt-$stamp"))) { "The damaged database could not be preserved. Your files have been kept." }
                }
                issues += "The database was damaged. Recovery copies were preserved; restore a backup for newer annotations and use the stored-EPUB scan for missing books."
                LibraryDatabase(path)
            }
            catalog = db
            if (!db.migrated()) {
                val items = mutableListOf<Book>()
                if (database.baseFile.exists() || File(database.baseFile.path + ".bak").exists()) {
                    // Retain the exact original; migration never rewrites or deletes library.json.
                    val preserved = File(directory, "library-v1-preserved.json")
                    database.openRead().use { input -> if (!preserved.exists()) preserved.outputStream().use { input.copyTo(it) } }
                    val raw = if (database.baseFile.length() <= 20L * 1024 * 1024) database.openRead().bufferedReader().use { it.readText() } else ""
                    if (raw.isEmpty()) issues += "The old library exceeds the safe metadata limit. Its original was kept; use a backup or scan stored EPUBs."
                    val array = try {
                        BoundedJson.check(raw)
                        val json = JSONObject(raw)
                        require(json.getInt("version") == 1) { "Unsupported library version" }
                        json.getJSONArray("books")
                    } catch (error: Exception) {
                        issues += "The old library was damaged. Its original file has been preserved."
                        salvageRecords(raw)
                    }
                    for (i in 0 until array.length()) runCatching { Book.from(array.getJSONObject(i)) }
                        .onSuccess { book -> if (items.none { it.id == book.id }) items += book }
                        .onFailure { issues += "A damaged book record was isolated." }
                }
                db.transaction {
                    items.forEach { db.put(it) }
                    HomePreferences.load(context).shelves.forEach { db.addShelf(it) }
                    db.finishMigration()
                }
            }
            _books.value = db.read { issues += it }
            shelves.value = db.shelves()
            if (issues.isNotEmpty()) db.putState("recovery", "Recovered ${_books.value.size} books. ${issues.distinct().joinToString(" ")} Use storage recovery to scan for other EPUBs.")
            recovery.value = db.state("recovery")
            loaded = true
        }
    }
    private fun salvageRecords(raw: String): JSONArray {
        val result = JSONArray(); var start = -1; var depth = 0; var quoted = false; var escaped = false
        // Each object within the books array can be recovered independently from a truncated tail.
        val begin = raw.indexOf("\"books\"").takeIf { it >= 0 } ?: 0
        for (i in begin until raw.length) {
            val c = raw[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false; continue }
            if (c == '"') { quoted = true; continue }
            if (c == '{') { if (depth == 0) start = i; depth++ }
            if (c == '}' && depth > 0) { depth--; if (depth == 0 && start >= 0) runCatching {
                val record = raw.substring(start, i + 1)
                BoundedJson.check(record)
                JSONObject(record).takeIf { it.has("id") }?.let { result.put(it) }
            } }
        }
        return result
    }
    private fun save(items: List<Book>) {
        val db = checkNotNull(catalog)
        val previous = _books.value.associateBy { it.id }
        val current = items.associateBy { it.id }
        db.transaction {
            previous.keys.filter { it !in current }.forEach { db.remove(it) }
            items.forEach { if (it != previous[it.id]) db.put(it, previous[it.id]) }
        }
        _books.value = items
        shelves.value = db.shelves()
    }
    suspend fun snapshot(): List<Book> {
        synchronized(progressLock) { progressJobs.values.toList() }.forEach { it.join() }
        load(); return mutex.withLock { books.value.toList() }
    }
    suspend fun mergeRestored(items: List<Book>) = withContext(Dispatchers.IO) {
        load(); mutex.withLock {
            val restored = items.associateBy { it.id }
            items.filter { incoming -> _books.value.firstOrNull { it.id == incoming.id }?.lastRead?.let { it <= incoming.lastRead } ?: true }.forEach { revokeReading(it.id) }
            save(_books.value.map { old -> restored[old.id]?.let { incoming ->
                val latest = if (incoming.lastRead > old.lastRead) incoming else old
                old.copy(locator = latest.locator, progress = latest.progress, lastRead = latest.lastRead,
                    bookmarks = (old.bookmarks + incoming.bookmarks).distinctBy { it.id },
                    highlights = (old.highlights + incoming.highlights).distinctBy { it.id }, shelves = (old.shelves + incoming.shelves).distinct())
            } ?: old } + items.filter { it.id !in _books.value.map { b -> b.id } && file(it.id).exists() })
        }
    }
    suspend fun recoverStoredBooks(): Int = withContext(Dispatchers.IO) {
        load(); var found = 0
        directory.listFiles().orEmpty().filter { it.extension == "epub" && it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}")) }.forEach { epub ->
            currentCoroutineContext().ensureActive()
            if (books.value.none { it.id == epub.nameWithoutExtension }) runCatching {
                val publication = engine.open(epub)
                val book = try { Book(epub.nameWithoutExtension, publication.metadata.title ?: "Recovered book",
                    publication.metadata.authors.joinToString(", ") { it.name }, epub.lastModified()) } finally { publication.close() }
                mutex.withLock { if (_books.value.none { it.id == book.id }) { save(_books.value + book); found++ } }
            }
        }
        recovery.value = if (found > 0) "Recovered $found additional books. Original damaged metadata is retained for recovery." else "No additional stored EPUBs were found. Original recovery files are retained."
        mutex.withLock { catalog!!.putState("recovery", recovery.value) }
        found
    }
    suspend fun dismissRecovery() = withContext(Dispatchers.IO) { load(); mutex.withLock { catalog!!.putState("recovery", null); recovery.value = null } }
    suspend fun addShelf(label: String) = withContext(Dispatchers.IO) {
        require(label.isNotBlank() && label.length <= 40 && label != "All books")
        load(); mutex.withLock { catalog!!.addShelf(label); shelves.value = catalog!!.shelves() }
    }
    suspend fun renameShelf(old: String, new: String) = withContext(Dispatchers.IO) {
        require(new.isNotBlank() && new.length <= 40 && new != "All books")
        load(); mutex.withLock {
            require(new !in shelves.value || new == old) { "That shelf already exists." }
            catalog!!.transaction {
                _books.value.filter { old in it.shelves }.forEach { book -> catalog!!.put(book.copy(shelves = book.shelves.map { if (it == old) new else it }.distinct()), book) }
                catalog!!.removeShelf(old); catalog!!.addShelf(new)
            }
            _books.value = _books.value.map { it.copy(shelves = it.shelves.map { name -> if (name == old) new else name }.distinct()) }
            shelves.value = catalog!!.shelves()
        }
    }
    suspend fun importUri(uri: Uri): Book = importStream {
        context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("The selected file could not be read.")
    }

    suspend fun importSample(): Book = importStream { context.assets.open("sample.epub") }

    suspend fun importStream(input: () -> InputStream): Book = withContext(Dispatchers.IO) {
        load()
        val temporary = File.createTempFile("import-", ".epub", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            input().use { stream ->
                temporary.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 200L * 1024 * 1024) { "Please choose an EPUB smaller than 200 MB." }
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            mutex.withLock { _books.value.find { it.id == id } }?.let { return@withContext it }
            EpubValidator.validate(temporary)
            val publication = engine.open(temporary)
            val book = try {
                val title = publication.metadata.title?.takeIf { it.isNotBlank() } ?: "Untitled book"
                val author = publication.metadata.authors.joinToString(", ") { it.name }
                val book = Book(id, title, author, System.currentTimeMillis())
                runCatching {
                    publication.coverFitting(Size(400, 600))?.let { bitmap ->
                        cover(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    }
                }
                book
            } finally { publication.close() }
            mutex.withLock {
                _books.value.find { it.id == id }?.let { return@withContext it }
                // Readium closes ZIP handles asynchronously. Allow that close to finish on
                // filesystems which prevent renaming an open file (including Windows tests).
                var stored = temporary.renameTo(file(id))
                repeat(20) {
                    if (!stored) { delay(25); stored = temporary.renameTo(file(id)) }
                }
                check(stored) { "The book could not be stored." }
                try { save(_books.value + book) } catch (e: Throwable) { file(id).delete(); cover(id).delete(); throw e }
            }
            book
        } finally { temporary.delete() }
    }

    suspend fun update(id: String, transform: (Book) -> Book) = withContext(Dispatchers.IO) {
        mutex.withLock { check(loaded); save(_books.value.map { if (it.id == id) transform(it) else it }) }
    }
    fun beginReading(id: String): Long = synchronized(progressLock) {
        progressTicket(id)
        ((readingSessions[id] ?: 0L) + 1).also { readingSessions[id] = it }
    }
    private fun revokeReading(id: String) = synchronized(progressLock) { readingSessions[id] = (readingSessions[id] ?: 0L) + 1; progressTicket(id); Unit }
    private fun progressTicket(id: String): Long = synchronized(progressLock) {
        progressJobs.remove(id)?.cancel()
        ((progressVersions[id] ?: 0L) + 1).also { progressVersions[id] = it }
    }
    suspend fun progress(id: String, locator: Locator) {
        val ticket = progressTicket(id)
        persistProgress(id, locator, ticket)
    }
    fun queueProgress(id: String, locator: Locator, immediate: Boolean = false, session: Long? = null, failed: () -> Unit = {}) {
        if (session != null && synchronized(progressLock) { readingSessions[id] != session }) return
        val ticket = progressTicket(id)
        val job = progressScope.launch {
            try {
                if (!immediate) delay(300)
                persistProgress(id, locator, ticket, session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { recovery.value = "A reading position could not be saved. Check available storage."; failed() }
        }
        synchronized(progressLock) { if (progressVersions[id] == ticket) progressJobs[id] = job else job.cancel() }
    }
    private suspend fun persistProgress(id: String, locator: Locator, ticket: Long, session: Long? = null) = withContext(Dispatchers.IO) {
        load(); mutex.withLock {
            if (synchronized(progressLock) { progressVersions[id] != ticket || (session != null && readingSessions[id] != session) }) return@withLock
            val old = _books.value.firstOrNull { it.id == id } ?: return@withLock
            val point = locator.toJSON().toString()
            if (old.locator == point) return@withLock
            val updated = old.copy(locator = point, progress = locator.locations.totalProgression ?: old.progress, lastRead = System.currentTimeMillis())
            catalog!!.transaction { catalog!!.put(updated, old) }
            _books.value = _books.value.map { if (it.id == id) updated else it }
        }
    }
    suspend fun addMark(id: String, locator: Locator, highlight: Boolean, note: String = "") {
        val label = locator.text.highlight?.takeIf { it.isNotBlank() }?.take(200)
            ?: locator.title ?: "${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}% into the book"
        val mark = Mark(UUID.randomUUID().toString(), locator.toJSON().toString(), label, note)
        update(id) { book ->
            if (highlight) book.copy(highlights = book.highlights + mark)
            else if (book.bookmarks.any { it.locator == mark.locator }) book
            else book.copy(bookmarks = book.bookmarks + mark)
        }
    }
    suspend fun removeMark(id: String, markId: String) = update(id) {
        it.copy(bookmarks = it.bookmarks.filterNot { m -> m.id == markId }, highlights = it.highlights.filterNot { m -> m.id == markId })
    }
    suspend fun removeBook(id: String) = removeBooks(setOf(id))
    suspend fun assignShelves(ids: Set<String>, labels: List<String>) = withContext(Dispatchers.IO) {
        require(labels.all { it.isNotBlank() && it.length <= 40 })
        mutex.withLock { check(loaded); save(_books.value.map { if (it.id in ids) it.copy(shelves = labels.distinct()) else it }) }
    }
    suspend fun deleteShelf(label: String) = withContext(Dispatchers.IO) {
        load(); mutex.withLock {
            save(_books.value.map { it.copy(shelves = it.shelves.filterNot { name -> name == label }) })
            catalog!!.removeShelf(label); shelves.value = catalog!!.shelves()
        }
    }
    suspend fun removeBooks(ids: Set<String>) = withContext(Dispatchers.IO) {
        load()
        ids.filter { id -> books.value.any { it.id == id } }.forEach { id ->
            revokeReading(id)
            GuidedProgress.delete(context, id) {}
            if (SpeechController.state.value.bookId == id) SpeechController.stop(context)
            mutex.withLock { SpeechCheckpointCoordinator.deleteBook(context, id) {
                save(_books.value.filterNot { it.id == id })
                file(id).delete(); cover(id).delete()
                File(context.filesDir, "reader-state").listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
                File(context.cacheDir, "pagination").listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
                context.getSharedPreferences("navigation", 0).edit().remove(id).commit()
                BookText.forget(id)
            } }
        }
    }

    suspend fun exportNotes(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val book = books.value.first { it.id == id }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { out ->
            out.appendLine(book.title); out.appendLine(book.author); out.appendLine()
            for (mark in book.highlights) {
                out.appendLine(mark.label)
                if (mark.note.isNotBlank()) out.appendLine("Note: ${mark.note}")
                out.appendLine()
            }
        } ?: throw IllegalArgumentException("The notes could not be exported.")
    }
}
