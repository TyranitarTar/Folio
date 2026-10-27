package app.folio.reader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Portable, versioned merge backup. Models and generated audio are deliberately regenerable. */
class LibraryBackup(private val context: Context, private val store: LibraryStore) {
    private val manifestLimit = 20 * 1024 * 1024
    private val totalLimit = 2L * 1024 * 1024 * 1024
    private val preferences = listOf("appearance", "navigation", "pace", "speech", "home", "audioHome", "audiobook-playback")
    suspend fun export(uri: Uri, includeBooks: Boolean) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { exportTo(it, includeBooks) }
            ?: throw IllegalArgumentException("The backup destination could not be opened.")
    }
    suspend fun exportTo(output: OutputStream, includeBooks: Boolean) {
        val books = store.snapshot()
        require(books.size <= 5000) { "A backup supports at most 5,000 books." }
        val ids = books.map { it.id }.toSet()
        val stateModified = JSONObject()
        fun readStates(kinds: Set<String>): JSONObject {
            val json = JSONObject()
            File(context.filesDir, "reader-state").listFiles().orEmpty().map { file ->
                if (file.name.endsWith(".bak")) File(file.path.removeSuffix(".bak")) else file
            }.distinctBy { it.name }.filter {
                it.name.matches(Regex("[a-f0-9]{64}-(speech|guided|pace|ai)\\.json")) &&
                    it.name.substringBefore('-') in ids && it.name.substringAfter('-').substringBefore('.') in kinds
            }.forEach { file ->
                val raw = AtomicFile(file).openRead().use { input -> val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (out.size() <= 65536) { val n = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                    out.toByteArray() }
                require(raw.size <= 65536) { "A saved position is too large to back up." }
                val value = BoundedJson.objectFrom(raw, 65536, 32, 8192)
                validateState(file.name, value); json.put(file.name, value)
                stateModified.put(file.name, file.lastModified())
            }
            return json
        }
        val states = SpeechCheckpointCoordinator.exclusive(context) { readStates(setOf("speech", "ai")) }
        val guided = GuidedProgress.exclusive { readStates(setOf("guided", "pace")) }
        guided.keys().forEach { key -> states.put(key, guided.get(key)) }
        val settings = JSONObject()
        preferences.forEach { name -> settings.put(name, JSONObject(context.getSharedPreferences(name, 0).all)) }
        val manifest = JSONObject().put("format", "Folio").put("version", 1).put("created", System.currentTimeMillis())
            .put("booksIncluded", includeBooks).put("books", JSONArray(books.map { it.json() })).put("shelves", JSONArray(store.shelves.value))
            .put("states", states).put("settings", settings)
            .put("stateModified", stateModified)
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= manifestLimit) { "The backup metadata exceeds the 20 MB restore limit." }
        BoundedJson.check(manifestBytes.toString(Charsets.UTF_8))
        if (includeBooks) {
            var total = manifestBytes.size.toLong()
            books.forEach { book ->
                val file = store.file(book.id)
                require(file.isFile) { "${book.title} is missing. Recover it before making a full backup." }
                require(file.length() <= 200L * 1024 * 1024) { "A backup book exceeds 200 MB." }
                total += file.length()
            }
            require(total <= totalLimit) { "The full backup exceeds the 2 GB restore limit. Use a metadata backup or export fewer books." }
        }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
            if (includeBooks) books.forEach { book ->
                currentCoroutineContext().ensureActive()
                require(store.file(book.id).exists()) { "${book.title} is missing. Recover it before making a full backup." }
                zip.putNextEntry(ZipEntry("books/${book.id}.epub"))
                store.file(book.id).inputStream().use { copyBounded(it, zip, 200L * 1024 * 1024) }
                zip.closeEntry()
            }
        }
    }
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { restoreFrom(it) }
            ?: throw IllegalArgumentException("The backup could not be opened.")
    }
    suspend fun restoreFrom(input: InputStream): String {
        val stage = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null; var bytes = 0L; val entries = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(entries.add(entry.name) && entries.size <= 5001 && !entry.isDirectory) { "The backup has duplicate or excessive entries." }
                    val limit = minOf(if (entry.name == "manifest.json") manifestLimit.toLong() else 200L * 1024 * 1024, totalLimit - bytes)
                    require(entry.name == "manifest.json" || entry.name.matches(Regex("books/[a-f0-9]{64}\\.epub"))) { "The backup contains an unexpected path." }
                    val target = File(stage, if (entry.name == "manifest.json") "manifest.json" else entry.name.substringAfter('/'))
                    val count = target.outputStream().use { copyBounded(zip, it, limit) }
                    bytes += count
                    if (entry.name == "manifest.json") manifest = BoundedJson.objectFrom(target.readBytes(), manifestLimit)
                    zip.closeEntry()
                }
            }
            val data = requireNotNull(manifest) { "The backup manifest is missing." }
            require(data.getString("format") == "Folio" && data.getInt("version") == 1) { "This backup format is not supported." }
            val array = data.getJSONArray("books")
            require(array.length() <= 5000)
            val books = (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                for (kind in listOf("bookmarks", "highlights")) json.optJSONArray(kind)?.let { marks ->
                    for (i in 0 until marks.length()) require(Mark.from(marks.getJSONObject(i)).position() != null) { "The backup has an invalid annotation." }
                }
                Book.from(json).also { require(it.locator == null || it.position() != null) { "The backup has an invalid reading position." } }
            }
            require(books.map { it.id }.distinct().size == books.size) { "The backup contains duplicate book records." }
            val states = data.optJSONObject("states") ?: JSONObject()
            val stateNames = states.keys().asSequence().toList()
            val modified = data.optJSONObject("stateModified")
            modified?.keys()?.forEach { name ->
                require(name in stateNames && modified.get(name) is Number && modified.getLong(name) >= 0) { "A backup position timestamp is invalid." }
            }
            stateNames.forEach { name ->
                require(name.substringBefore('-') in books.map { it.id })
                validateState(name, states.getJSONObject(name))
            }
            val settings = data.optJSONObject("settings") ?: JSONObject()
            validatePreferences(settings)
            val shelfArray = data.optJSONArray("shelves") ?: JSONArray()
            val shelves = (0 until shelfArray.length()).map { shelfArray.getString(it) }
            require(shelves.all { it.isNotBlank() && it.length <= 40 && it != "All books" })
            // Validate every contained book before mutating the live library.
            val files = stage.listFiles().orEmpty().filter { it.extension == "epub" }
            for (file in files) {
                require(file.nameWithoutExtension in books.map { it.id }) { "The backup contains an unlisted book." }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { source -> val buffer = ByteArray(65536); while (true) { val n = source.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                require(digest.digest().joinToString("") { "%02x".format(it) } == file.nameWithoutExtension) { "A backup book is damaged." }
                EpubValidator.validate(file)
                store.engine.open(file).close()
            }
            store.load()
            val previous = store.books.value.associateBy { it.id }
            SpeechController.stop(context)
            for (file in files) store.importStream { file.inputStream() }
            store.mergeRestored(books)
            val available = store.books.value.map { it.id }.toSet()
            val restoredIds = stateNames.map { it.substringBefore('-') }.distinct().filter { it in available }
            // Revoke old writers without deleting independent modes absent from this backup.
            restoredIds.forEach { id -> GuidedProgress.delete(context, id) {}; SpeechCheckpointCoordinator.invalidateBook(context, id) }
            SpeechCheckpointCoordinator.exclusive(context) {
                val directory = File(context.filesDir, "reader-state").apply { mkdirs() }
                stateNames.filter { name ->
                    val id = name.substringBefore('-')
                    val file = File(directory, name)
                    val localModified = maxOf(file.lastModified(), File(file.path + ".bak").lastModified())
                    id in available && if (modified?.has(name) == true) localModified <= modified.getLong(name)
                        else (previous[id]?.lastRead ?: -1) <= books.first { it.id == id }.lastRead
                }.forEach { name ->
                    DurableJson.write(AtomicFile(File(directory, name)), states.getJSONObject(name).toString().toByteArray())
                }
            }
            shelves.forEach { store.addShelf(it) }
            preferences.forEach { name -> settings.optJSONObject(name)?.let { values ->
                val edit = context.getSharedPreferences(name, 0).edit()
                values.keys().forEach { key -> when (val value = values.get(key)) {
                    is Boolean -> edit.putBoolean(key, if (name == "speech" && key == "follow") true else value)
                    is String -> edit.putString(key, value)
                    is Number -> if (name=="audiobook-playback" || (name == "appearance" && key == "awakeMinutes") || (name in setOf("home","audioHome") && key in setOf("columns","statusSchema")) || (name == "speech" && key in setOf("aiVoice", "supertonicVoice", "picoVoice", "picoThreads", "plapre3Voice", "plapre3Threads", "sentencePauseMillis", "paragraphPauseMillis", "passageTargetWords", "lookaheadPassages", "startBufferSeconds")) || (name == "pace" && key in setOf("focusWords", "countdown", "practiceMinutes"))) edit.putInt(key, value.toInt()) else edit.putFloat(key, value.toFloat())
                } }
                if (name == "appearance" && values.has("awake") && !values.has("awakeMode")) edit.remove("awakeMode").remove("awakeMinutes")
                check(edit.commit()) { "Settings could not be restored." }
            } }
            val missing = books.count { it.id !in available }
            return "Restored metadata and positions for ${books.size - missing} books." + if (missing > 0) " $missing EPUBs were not included; import them before restoring this metadata backup again." else ""
        } finally { stage.deleteRecursively() }
    }
    private suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(65536); var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            bytes += n; require(bytes <= limit) { "A backup resource exceeds its size limit." }
            output.write(buffer, 0, n)
        }
        return bytes
    }
    private fun validateState(name: String, json: JSONObject) {
        val serialized = json.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= 65536) { "A backup position exceeds its size limit." }
        BoundedJson.check(serialized, 32, 8192)
        require(name.matches(Regex("[a-f0-9]{64}-(speech|guided|pace|ai)\\.json")) && json.getInt("version") == 1)
        require(json.getInt("section") >= 0 && json.getInt("word") >= 0)
        if (name.endsWith("-ai.json")) {
            require(json.getString("key").matches(Regex("[a-f0-9]{64}")) && json.getInt("played") >= 0)
            if (json.has("audioDigest") && !json.isNull("audioDigest")) require(json.getString("audioDigest").matches(Regex("[a-f0-9]{64}")))
        }
        // Readium consumes known location keys while parsing; validation must not mutate the backup.
        else require(Locator.fromJSON(JSONObject(json.getJSONObject("locator").toString())) != null)
    }
    private fun validatePreferences(settings: JSONObject) {
        require(settings.keys().asSequence().all { it in preferences })
        preferences.forEach { name -> settings.optJSONObject(name)?.let { values ->
            require(values.length() <= 6000)
            values.keys().forEach { key ->
                require(key.length <= 128)
                val v = values.get(key)
                if(name=="audiobook-playback")require(key in setOf("backSeconds","forwardSeconds","autoRewind","rewindSeconds","afterMinutes","headsetChapters","pauseForNotifications","resumeAfterInterruption","pauseOnDisconnect","sleepMinutes"))
                val numeric = when (name) {
                    "appearance" -> key in setOf("fontSize", "lineHeight", "margins", "awakeMinutes")
                    "pace" -> key in setOf("speed", "focusWords", "countdown", "practiceMinutes")
                    "speech" -> key in setOf("rate", "aiVoice", "supertonicVoice", "picoVoice", "picoThreads", "plapre3Voice", "plapre3Threads", "sentencePauseMillis", "paragraphPauseMillis", "passageTargetWords", "lookaheadPassages", "startBufferSeconds")
                    "home", "audioHome" -> key in setOf("columns","statusSchema")
                    "audiobook-playback" -> key in setOf("backSeconds","forwardSeconds","rewindSeconds","afterMinutes","sleepMinutes")
                    else -> false
                }
                val boolean = when (name) {
                    "appearance" -> key in setOf("scroll", "publisher", "awake", "volume")
                    "pace" -> key in setOf("punctuation", "longWords", "gradualIncrease")
                    "speech" -> key == "follow"
                    "home", "audioHome" -> key in setOf("descending", "annotated", "showProgress")
                    "audiobook-playback" -> key in setOf("autoRewind","headsetChapters","pauseForNotifications","resumeAfterInterruption","pauseOnDisconnect")
                    else -> false
                }
                require(if (numeric) v is Number else if (boolean) v is Boolean else v is String) { "A backup setting has an invalid type." }
                if (v is String) require(v.length <= 65536)
                if (v is Number) {
                    val number = v.toDouble(); require(number.isFinite())
                    val range = when (key) { "rate" -> .5..3.0; "columns" -> 2.0..6.0; "aiVoice" -> 3.0..21.0; "supertonicVoice" -> 0.0..9.0; "picoVoice" -> 0.0..4.0; "picoThreads", "plapre3Threads" -> 0.0..8.0; "plapre3Voice" -> 0.0..4.0
                        "statusSchema" -> 1.0..3.0; "passageTargetWords" -> 0.0..35.0; "lookaheadPassages" -> 1.0..32.0; "startBufferSeconds" -> 0.0..30.0;
                        "focusWords" -> 1.0..8.0; "countdown" -> 0.0..5.0; "practiceMinutes" -> 0.0..30.0;
                        "sentencePauseMillis", "paragraphPauseMillis" -> 0.0..5000.0; "speed" -> 1.0..1200.0; "fontSize" -> .5..3.0; "lineHeight" -> .8..3.0; "margins" -> .0..3.0; else -> .0..10000.0 }
                    require(number in range) { "A backup setting is outside its supported range." }
                    if(name=="audiobook-playback")require(number==number.toInt().toDouble() && when(key){"backSeconds","forwardSeconds"->number.toInt() in setOf(5,10,15,30,60);"rewindSeconds"->number.toInt() in setOf(3,5,10,15);"afterMinutes"->number.toInt() in setOf(1,5,15);"sleepMinutes"->number.toInt() in setOf(5,15,30,45,60,90);else->false})
                    if(key in setOf("statusSchema","passageTargetWords","lookaheadPassages","startBufferSeconds")) require(number==number.toInt().toDouble()) { "A whole-number backup setting is invalid." }
                    if (name == "appearance" && key == "awakeMinutes") require(number in setOf(2.0, 5.0, 10.0, 15.0, 30.0))
                }
                if (name == "appearance" && key == "awakeMode") require(v in setOf("device", "timed", "always"))
                if (name == "navigation") {
                    require(key == "global" || key == "defaults" || key.matches(Regex("[a-f0-9]{64}")))
                    val nav = JSONObject(v as String)
                    require(nav.optString("layout", "Default") in NavigationSettings.layouts)
                    require(nav.optString("direction", "Book") in setOf("Book", "LTR", "RTL"))
                    require(nav.optString("inversion", "None") in setOf("None", "Horizontal", "Vertical", "Both"))
                    for (flag in listOf("animate", "overlay", "invertVolume")) if (nav.has(flag)) require(nav.get(flag) is Boolean)
                }
                if (name in setOf("home","audioHome") && key == "shelves") {
                    val names = JSONArray(v as String); require(names.length() <= 5000)
                    for (i in 0 until names.length()) require(names.getString(i).length <= 40)
                }
            }
        } }
    }
}
