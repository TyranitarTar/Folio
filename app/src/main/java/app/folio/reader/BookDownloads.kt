package app.folio.reader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BookDownload(val key: String, val book: SourceBook, val state: String = "Queued", val bytes: Long = 0,
    val total: Long = -1, val message: String = "", val bookId: String? = null, val updated: Long = System.currentTimeMillis(), val retryAt: Long = 0) {
    val finished get() = state in setOf("Complete", "Failed", "Cancelled")
    fun json() = JSONObject().put("key", key).put("book", book.json()).put("state", state).put("bytes", bytes).put("total", total)
        .put("message", message).put("bookId", bookId).put("updated", updated).put("retryAt", retryAt)
    companion object { fun from(j: JSONObject) = BookDownload(j.getString("key"), SourceBook.from(j.getJSONObject("book")),
        j.optString("state", "Queued"), j.optLong("bytes"), j.optLong("total", -1), j.optString("message"),
        j.optString("bookId").takeIf { it.isNotBlank() }, j.optLong("updated"), j.optLong("retryAt")) }
}

class BookDownloads(private val context: Context) {
    private val lock = Any()
    internal val folder = File(context.filesDir, "book-downloads").apply { mkdirs() }
    private val database = SQLiteDatabase.openOrCreateDatabase(File(folder, "downloads.sqlite").absolutePath, null,
        DatabaseErrorHandler { damaged -> runCatching { damaged.close() } })
    private val mutable = MutableStateFlow<List<BookDownload>>(emptyList())
    val entries = mutable.asStateFlow()
    init {
        database.execSQL("CREATE TABLE IF NOT EXISTS downloads (key TEXT PRIMARY KEY, data TEXT NOT NULL)")
        synchronized(lock) { refresh() }
    }
    private fun refresh() {
        mutable.value = database.rawQuery("SELECT data FROM downloads ORDER BY rowid DESC", null).use { cursor -> buildList {
            while (cursor.moveToNext()) runCatching { BookDownload.from(JSONObject(cursor.getString(0))) }.getOrNull()?.let { add(it) }
        } }
    }
    internal fun get(key: String): BookDownload? = synchronized(lock) { mutable.value.firstOrNull { it.key == key } }
    internal fun set(value: BookDownload) = synchronized(lock) {
        require(value.key.matches(Regex("[a-f0-9]{64}")) && value.key == value.book.key)
        database.insertWithOnConflict("downloads", null, ContentValues().apply { put("key", value.key); put("data", value.json().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
        refresh()
    }
    internal fun change(key: String, transform: (BookDownload) -> BookDownload) = synchronized(lock) {
        get(key)?.let { set(transform(it).copy(updated = System.currentTimeMillis())) }
    }
    fun enqueue(book: SourceBook) {
        require(book.format in setOf("epub", "txt", "html", "wiki") && book.downloadUrl != null) { "Choose a supported edition first." }
        val current = get(book.key)
        if (current != null && !current.finished) return
        require(mutable.value.count { !it.finished } < 20) { "Finish or cancel some queued downloads first." }
        set(BookDownload(book.key, book, bytes = current?.bytes ?: 0, retryAt = current?.retryAt ?: 0))
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>().setInputData(workDataOf("key" to book.key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("folio-book-download").build()
        WorkManager.getInstance(context).enqueueUniqueWork("folio-book-${book.key}", ExistingWorkPolicy.REPLACE, request)
    }
    fun cancel(key: String) {
        change(key) { if (it.state == "Complete") it else it.copy(state = "Cancelled", message = "Cancelled") }
        WorkManager.getInstance(context).cancelUniqueWork("folio-book-$key")
    }
    fun clearFinished() = synchronized(lock) {
        mutable.value.filter { it.finished }.forEach { entry ->
            database.delete("downloads", "key=?", arrayOf(entry.key))
            val directory = File(folder, entry.key)
            directory.walkBottomUp().forEach { it.delete() }
        }
        refresh()
    }
}

class BookDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object { private val serial = Mutex(); private const val LIMIT = 200L * 1024 * 1024 }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as FolioApp
        val downloads = app.bookDownloads
        val key = inputData.getString("key") ?: return@withContext Result.failure()
        val initial = downloads.get(key) ?: return@withContext Result.failure()
        if (initial.state == "Cancelled" || initial.state == "Complete") return@withContext Result.success()
        serial.withLock {
            try {
                if (downloads.get(key)?.state == "Cancelled") return@withLock Result.success()
                val now = System.currentTimeMillis()
                if (initial.retryAt > now) return@withLock Result.retry()
                app.library.load()
                app.library.books.value.firstOrNull { it.origin?.key == initial.book.key && app.library.file(it.id).exists() }?.let { existing ->
                    downloads.change(key) { it.copy(state = "Complete", bookId = existing.id, message = "Already in your library") }
                    return@withLock Result.success()
                }
                val working = File(downloads.folder, key).apply { mkdirs() }
                require(working.usableSpace > 30L * 1024 * 1024) { "Free some device storage before downloading." }
                val http = SourceHttp(File(applicationContext.cacheDir, "source-catalogs"))
                val book = initial.book
                val output = File(working, "prepared.epub")
                val converted = when (book.format) {
                    "wiki" -> {
                        downloads.change(key) { it.copy(state = "Preparing", message = "Preparing Wikisource text", bytes = 0, total = -1) }
                        BookConversion.fromWikisource(book, working, output, http) { label, current, total ->
                            downloads.change(key) { it.copy(state = "Preparing", message = "$label: $current of $total", bytes = current.toLong(), total = total.toLong()) }
                        }
                    }
                    else -> {
                        val original = SourceTransfer.fetch(book, working, http) { bytes, total -> downloads.change(key) { it.copy(state = "Downloading", bytes = bytes, total = total, message = "Downloading book") } }
                        when (book.format) {
                            "epub" -> ConvertedBook(original, hashFile(original))
                            "txt" -> { downloads.change(key) { it.copy(state = "Converting", message = "Preparing text EPUB") }; BookConversion.fromText(book, original, output) }
                            "html" -> { downloads.change(key) { it.copy(state = "Converting", message = "Preparing illustrated EPUB") }; BookConversion.fromHtml(book, original, output, http) }
                            else -> error("Unsupported book format")
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (downloads.get(key)?.state == "Cancelled") return@withLock Result.success()
                downloads.change(key) { it.copy(state = "Importing", message = "Adding to library") }
                val origin = SourceOrigin(book.source.name, book.id, book.language, book.pageUrl, converted.sourceHash)
                val imported = app.library.importStream(origin) { converted.file.inputStream() }
                // A committed import is retained even if cancellation races with its final status update.
                withContext(NonCancellable) { downloads.change(key) { it.copy(state = "Complete", bookId = imported.id, message = "Ready to read", retryAt = 0) } }
                if (output.exists() && output != converted.file) output.delete()
                Result.success()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { downloads.change(key) { if (it.state in setOf("Complete", "Cancelled")) it else it.copy(state = "Queued", message = "Waiting to resume") } }
                throw cancelled
            } catch (e: Exception) {
                val transient = e is IOException && (e !is SourceHttpError || e.status == 429 || e.status >= 500)
                val delaySeconds = (e as? SourceHttpError)?.retrySeconds ?: 0
                downloads.change(key) { if (it.state == "Cancelled") it else it.copy(state = if (transient && runAttemptCount < 3) "Queued" else "Failed",
                    message = e.message ?: "The download could not finish.", retryAt = System.currentTimeMillis() + delaySeconds * 1000) }
                if (transient && runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal object SourceTransfer {
    private const val LIMIT = 200L * 1024 * 1024
    suspend fun fetch(book: SourceBook, folder: File, http: SourceHttp, progress: (Long, Long) -> Unit): File {
        val complete = File(folder, "original.download")
        if (complete.exists()) { require(complete.length() in 1..LIMIT); return complete }
        val partial = File(folder, "original.part")
        val metadata = File(folder, "transfer.json")
        val previous = runCatching { JSONObject(metadata.readText()) }.getOrNull()
        val etag = previous?.optString("etag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        val modified = previous?.optString("modified")?.takeIf { it.isNotBlank() }
        val validator = etag ?: modified
        var offset = partial.length().takeIf { previous?.optString("url") == book.downloadUrl && validator != null } ?: 0
        require(offset <= LIMIT)
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to validator!!) else emptyMap()
        var connection = http.open(book.downloadUrl!!, book.source, headers = headers)
        try {
            if (connection.responseCode == 416) {
                connection.disconnect(); offset = 0
                connection = http.open(book.downloadUrl, book.source)
            }
            http.check(connection)
            val range = connection.getHeaderField("Content-Range")
            var rangeTotal: Long? = null
            if (connection.responseCode == 206) {
                val fields = range?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }?.groupValues
                val start = fields?.get(1)?.toLongOrNull()
                val end = fields?.get(2)?.toLongOrNull()
                rangeTotal = fields?.get(3)?.toLongOrNull()
                require(offset > 0 && start == offset && end != null && rangeTotal != null && end == rangeTotal - 1 && end >= offset) {
                    "The source sent an invalid resume range. Retry this download."
                }
            } else offset = 0
            val length = connection.contentLengthLong
            val total = rangeTotal ?: if (length >= 0) length + offset else -1L
            require(rangeTotal == null || length < 0 || length + offset == rangeTotal) { "The source sent inconsistent download sizes." }
            require(total <= LIMIT) { "Choose an edition smaller than 200 MB." }
            if (total > 0) require(folder.usableSpace > (total - offset) * 2 + 10L * 1024 * 1024) { "There is not enough storage to download and import this book." }
            metadata.writeText(JSONObject().put("url", book.downloadUrl).put("etag", connection.getHeaderField("ETag"))
                .put("modified", connection.getHeaderField("Last-Modified")).put("total", total).toString())
            var bytes = offset
            var lastReport = 0L
            progress(bytes, total)
            connection.inputStream.use { input -> java.io.FileOutputStream(partial, offset > 0).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count
                    require(bytes <= LIMIT) { "Choose an edition smaller than 200 MB." }
                    output.write(buffer, 0, count)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 300) { progress(bytes, total); lastReport = now }
                }
            } }
            if (bytes == 0L || (total >= 0 && bytes != total)) throw IOException("The download was interrupted before the book was complete.")
            currentCoroutineContext().ensureActive()
            require(partial.renameTo(complete)) { "The downloaded book could not be stored." }
            progress(bytes, bytes)
            return complete
        } finally { connection.disconnect() }
    }
}
