package app.folio.reader

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val bookId: String? = null,
    val tracks: List<AudioTrack>,
    val positionTrack: Int = 0,
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val bookmarks: List<AudioBookmark> = emptyList(),
    val lastPlayed: Long = 0L,
    val preview: Boolean = false,
    val started: Boolean = false,
    val readState: String = "Auto",
    val sourceRoot: String? = null
) {
    val elapsedMs: Long get() = tracks.take(positionTrack).sumOf { it.durationMs } + positionMs
    val totalMs: Long get() = tracks.sumOf { it.durationMs }
    val durationKnown: Boolean get() = tracks.all { it.durationMs>0 }
    val isRead: Boolean get() = if(readState=="Read") true else if(readState=="Unread") false
        else durationKnown && totalMs>0 && elapsedMs>=totalMs-1000 && positionTrack==tracks.lastIndex
}

data class AudioTrack(
    val file: String,
    val title: String,
    val durationMs: Long,
    val cues: List<AudioCue> = emptyList(),
    val sourceUri: String? = null
)

data class AudioCue(
    val startMs: Long,
    val endMs: Long,
    val href: String,
    val fragment: String? = null,
    val text: String = "",
    val before: String = "",
    val after: String = "",
    val cssSelector: String? = null,
    val progression: Double? = null
)
data class AudioBookmark(val id: String, val track: Int, val positionMs: Long, val label: String)

/** Private audio storage. Ordinary library backups intentionally know nothing about this directory. */
class AudiobookStore(private val context: Context) {
    private val metadataScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var metadataJob: Job?=null
    internal val metadataProgress=MutableStateFlow<String?>(null)
    internal fun cancelMetadata() { metadataJob?.cancel() }
    /** Index first; read/cache durations afterwards, yielding to active narration. */
    internal fun prepareLinkedMetadata() {
        if(metadataJob?.isActive==true)return
        metadataJob=metadataScope.launch(start=CoroutineStart.LAZY) {
            try {
                val pending=books.value.filter{it.sourceRoot!=null && !it.durationKnown}
                var completed=0;val total=pending.sumOf{book->book.tracks.count{it.durationMs<=0}}
                for(book in pending) {
                    val found=mutableMapOf<String,AudioMetadata>()
                    for(track in book.tracks.filter{it.durationMs<=0 && it.sourceUri!=null}) {
                        currentCoroutineContext().ensureActive()
                        while(AudiobookController.state.value.playing || SpeechController.state.value.playing || SpeechController.state.value.preparing)delay(300)
                        metadataProgress.value="Reading durations ${++completed}/$total · ${book.title}"
                        runCatching{readMetadata(Uri.parse(track.sourceUri))}.getOrNull()?.let{found[track.sourceUri!!]=it}
                    }
                    currentCoroutineContext().ensureActive()
                    if(found.isNotEmpty())mutex.withLock {
                        val latest=mutableBooks.value.firstOrNull{it.id==book.id} ?: return@withLock
                        replaceLocked(latest.copy(tracks=latest.tracks.map { track ->
                            val metadata=found[track.sourceUri]
                            if(metadata==null || metadata.durationMs<=0)track else track.copy(durationMs=metadata.durationMs)
                        }))
                    }
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception) { /* Playback can still discover individual durations. */ }
            finally { if(metadataJob===currentCoroutineContext()[Job])metadataProgress.value=null }
        }
        metadataJob?.start()
    }
    private val root = File(context.filesDir, "audiobooks").apply { mkdirs() }
    private val catalog = AtomicFile(File(root, "audiobooks.json"))
    private val mutex = Mutex()
    private var loaded = false
    private val hashes = mutableMapOf<String, List<String>>()
    private val mutableBooks = MutableStateFlow<List<Audiobook>>(emptyList())
    val books = mutableBooks.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            // Remove imports interrupted by process death on the first catalog load.
            root.listFiles()?.filter { it.isDirectory && (it.name.startsWith(".stage-") || it.name.startsWith(".removed-")) }
                ?.forEach { it.deleteRecursively() }
            if (catalog.baseFile.exists() || File(catalog.baseFile.path + ".bak").exists()) {
                val bytes = catalog.openRead().use { readLimited(it, MAX_CATALOG_BYTES) }
                val json = BoundedJson.objectFrom(bytes, MAX_CATALOG_BYTES, 48, MAX_CATALOG_TOKENS)
                require(json.optInt("version") == CATALOG_VERSION) { "The audiobook catalog version is not supported." }
                val array = json.optJSONArray("books") ?: JSONArray()
                require(array.length() <= MAX_BOOKS) { "The audiobook catalog contains too many books." }
                val parsed = mutableListOf<Audiobook>()
                for (index in 0 until array.length()) {
                    runCatching { parseBook(array.getJSONObject(index)) }
                        .onSuccess { book ->
                            if ((book.sourceRoot!=null || book.tracks.all { file(book.id, it.file).isFile }) && parsed.none { it.id == book.id }) {
                                parsed += readCursor(book)
                                hashes[book.id] = parseHashes(array.getJSONObject(index).optJSONArray("checksums"), book.tracks.size)
                            }
                        }
                }
                mutableBooks.value = parsed.sortedByDescending { it.lastPlayed }
            }
            loaded = true
        }
    }

    /** Root libraries index SAF references without copying/hashing media or opening every decoder. */
    internal suspend fun linkAudio(tree: Uri,sourceKey: String,fingerprint: String,title: String,entries: List<Pair<String,Uri>>,previousId: String?=null,knownId: String?=null): Audiobook = withContext(Dispatchers.IO) {
        load();require(entries.isNotEmpty() && entries.size<=MAX_TRACKS)
        require(fingerprint.isNotBlank())
        val id=hex(MessageDigest.getInstance("SHA-256").digest(("folder-reference-v1\n$tree\n$sourceKey").toByteArray()))
        val tracks=entries.mapIndexed { index,(name,uri) ->
            require(validLinkedAudio(tree,uri)) { "A track is outside the selected library root." }
            AudioTrack("track-${(index+1).toString().padStart(4,'0')}.m4a",name.take(500),0,sourceUri=uri.toString())
        }
        mutex.withLock {
            mutableBooks.value.firstOrNull { it.id==id || (it.id==knownId && it.sourceRoot==tree.toString()) }?.let { existing ->
                val newIndices=tracks.mapIndexed{index,track->track.sourceUri to index}.toMap()
                require(existing.tracks.all{it.sourceUri in newIndices}) { "Existing source tracks were removed or renamed. Relink or remove the old entry deliberately before replacing its playlist." }
                val updated=existing.copy(tracks=tracks,positionTrack=checkNotNull(newIndices[existing.tracks[existing.positionTrack].sourceUri]),
                    bookmarks=existing.bookmarks.map{it.copy(track=checkNotNull(newIndices[existing.tracks[it.track].sourceUri]))})
                writeCursor(updated);replaceLocked(updated)
                return@withLock updated
            }
            // Relinking a moved root can retain the old edition/cursor only when
            // its already discovered relative track sequence is identical.
            val previous=mutableBooks.value.firstOrNull{it.id==previousId && it.sourceRoot!=null}
            val restore=previous?.takeIf{it.tracks.map{track->track.title}==tracks.map{track->track.title}}
            val book=if(restore==null)Audiobook(id,title.take(500).ifBlank{"Audiobook"},"",tracks=tracks,sourceRoot=tree.toString())
                else restore.copy(tracks=tracks,sourceRoot=tree.toString())
            File(root,book.id).mkdirs();hashes[book.id]=emptyList()
            try {
                writeCursor(book);val next=if(restore==null)mutableBooks.value+book else mutableBooks.value.map{if(it.id==book.id)book else it}
                persistLocked(next);mutableBooks.value=next.sortedByDescending{it.lastPlayed}
            } catch(error:Throwable) { if(restore==null)hashes.remove(book.id);throw error }
            book
        }
    }

    internal suspend fun decodedDuration(id: String,index: Int,duration: Long) = withContext(Dispatchers.IO) {
        if(duration<=0 || duration>MAX_TRACK_DURATION_MS)return@withContext
        mutex.withLock {
            val book=mutableBooks.value.firstOrNull{it.id==id} ?: return@withLock
            if(book.sourceRoot==null || index !in book.tracks.indices || book.tracks[index].durationMs==duration)return@withLock
            replaceLocked(book.copy(tracks=book.tracks.mapIndexed{i,track->if(i==index)track.copy(durationMs=duration) else track}))
        }
    }

    /** Imports ordered local audio files as one audiobook, streaming each file to private storage. */
    suspend fun importAudio(uris: List<Uri>, preserveOrder: Boolean = false, titleOverride: String? = null): Audiobook = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.size <= MAX_TRACKS) { "Choose between 1 and $MAX_TRACKS audio files." }
        load()
        val orderedUris = uris.mapIndexed { index, uri -> Triple(index, uri, displayName(uri).orEmpty()) }
            .let { files -> if(preserveOrder) files else files.sortedWith { left, right ->
                val order = compareNatural(left.third, right.third)
                if (order != 0) order else left.first.compareTo(right.first)
            } }
        val stage = stageDirectory()
        try {
            val tracks = mutableListOf<AudioTrack>()
            val digests = mutableListOf<String>()
            var firstAuthor = ""
            var total = 0L
            for ((index, item) in orderedUris.withIndex()) {
                currentCoroutineContext().ensureActive()
                val uri = item.second
                val sourceName = item.third.takeIf { it.isNotBlank() }
                val extension = audioExtension(sourceName, context.contentResolver.getType(uri))
                val name = "track-${(index + 1).toString().padStart(4, '0')}.$extension"
                val target = File(stage, name)
                val digest = MessageDigest.getInstance("SHA-256")
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> copyBounded(input, output, MAX_TRACK_BYTES, digest) }
                } ?: throw IllegalArgumentException("${sourceName ?: "The selected audio"} could not be opened.")
                require(copied > 0) { "${sourceName ?: "An audio file"} is empty." }
                total += copied
                require(total <= MAX_AUDIO_BYTES) { "These audiobook files exceed the 4 GB storage limit." }
                val metadata = readMetadata(target)
                if (firstAuthor.isBlank()) firstAuthor = metadata.artist?.takeIf { it.isNotBlank() }.orEmpty()
                tracks += AudioTrack(name, metadata.title?.takeIf { it.isNotBlank() } ?: sourceName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Track ${index + 1}", metadata.durationMs)
                digests += hex(digest.digest())
            }
            val title = titleOverride?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: tracks.first().title.ifBlank { "Audiobook" }
            publish(stage, title, firstAuthor, null, tracks, digests,replaceTitle=titleOverride!=null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Imports a bounded Folio audiobook ZIP bundle and optionally links its exact original EPUB. */
    suspend fun importBundle(uri: Uri): Audiobook = withContext(Dispatchers.IO) {
        load()
        val stage = stageDirectory()
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("The audiobook bundle could not be opened.")
            val limited = CompressedLimitInputStream(source, MAX_BUNDLE_BYTES)
            val tracks: MutableList<AudioTrack> = mutableListOf()
            val digests = mutableListOf<String>()
            var title = "Audiobook"
            var author = ""
            var preview = false
            var epubPath: File? = null
            var epubDigest: String? = null
            var linkedBookId: String? = null
            limited.use { raw ->
                ZipInputStream(BufferedInputStream(raw, 64 * 1024)).use { zip ->
                    val manifestEntry = zip.nextEntry ?: throw IllegalArgumentException("The audiobook bundle is empty.")
                    require(manifestEntry.name == "manifest.json" && !manifestEntry.isDirectory) {
                        "The audiobook bundle must begin with manifest.json."
                    }
                    val manifestBytes = readEntryBounded(zip, MAX_MANIFEST_BYTES.toLong(), null)
                    zip.closeEntry()
                    val manifest = BoundedJson.objectFrom(manifestBytes, MAX_MANIFEST_BYTES, 40, MAX_CATALOG_TOKENS)
                    require(manifest.optString("format") == "folio-audiobook" && manifest.optInt("version") == 1) {
                        "This Folio audiobook bundle format is not supported."
                    }
                    require(!manifest.has("preview") || manifest.opt("preview") is Boolean) {
                        "The audiobook bundle has an invalid preview marker."
                    }
                    preview = manifest.opt("preview") as? Boolean ?: false
                    title = cleanText(manifest.optString("title", "Audiobook"), 500).ifBlank { "Audiobook" }
                    author = cleanText(manifest.optString("author", ""), 500)
                    val sourceSha = manifest.optString("sourceSha256", "").takeIf { it.isNotBlank() }
                    require(sourceSha == null || sourceSha.matches(SHA256_PATTERN)) { "The bundle source checksum is invalid." }
                    val epubName = manifest.optString("epub", "").takeIf { it.isNotBlank() }
                    require(epubName == null || epubName == "book.epub") { "The bundle has an unsupported EPUB path." }
                    val chapterArray = manifest.optJSONArray("chapters") ?: JSONArray()
                    require(chapterArray.length() in 1..MAX_TRACKS) { "The bundle must contain between 1 and $MAX_TRACKS chapters." }
                    val chapters = (0 until chapterArray.length()).map { parseChapter(chapterArray.getJSONObject(it), it) }
                    require(chapters.map { it.archivePath }.distinct().size == chapters.size) { "The bundle lists a chapter more than once." }
                    require(chapters.sumOf { it.cues.size } <= MAX_CUES) { "The bundle contains too many text cues." }
                    val expected = chapters.mapIndexed { index, chapter -> chapter.archivePath to index }.toMap()
                    val allExpected = expected.keys + listOfNotNull(epubName)
                    val directoryEntries = allowedDirectoryEntries(allExpected)
                    val seen = mutableSetOf<String>()
                    var expanded = manifestBytes.size.toLong()
                    var entries = 1
                    var chapterDigests = arrayOfNulls<String>(chapters.size)
                    var chapterTracks = arrayOfNulls<AudioTrack>(chapters.size)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_ZIP_ENTRIES && safeArchivePath(entry.name)) { "The bundle contains an unsafe or excessive ZIP entry." }
                        require(seen.add(entry.name)) { "The bundle contains a duplicate ZIP entry." }
                        if (entry.isDirectory) {
                            require(entry.name in directoryEntries) { "The bundle contains an unexpected directory." }
                            require(entry.size == 0L && zip.read() < 0) { "A ZIP directory entry contains unexpected data." }
                            zip.closeEntry()
                            continue
                        }
                        val chapterIndex = expected[entry.name]
                        require(chapterIndex != null || entry.name == epubName) { "The bundle contains an unexpected file." }
                        val isEpub = entry.name == epubName
                        val cap = if (isEpub) MAX_EPUB_BYTES else MAX_TRACK_BYTES
                        require(entry.size < 0L || entry.size <= cap) { "A bundle file exceeds its size limit." }
                        val target = if (isEpub) File(stage, "book.epub") else {
                            val extension = audioExtension(entry.name, null)
                            File(stage, "track-${(chapterIndex!! + 1).toString().padStart(4, '0')}.$extension")
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        val amount = target.outputStream().use { copyBounded(zip, it, cap, digest) }
                        expanded += amount
                        require(expanded <= MAX_BUNDLE_BYTES) { "The bundle expands beyond the 4 GB limit." }
                        require(amount > 0) { "The bundle contains an empty media file." }
                        val checksum = hex(digest.digest())
                        if (isEpub) { epubPath = target; epubDigest = checksum }
                        else {
                            val chapter = chapters[chapterIndex!!]
                            val metadata = readMetadata(target)
                            require(metadata.durationMs <= MAX_TRACK_DURATION_MS) { "An audio chapter exceeds the supported duration." }
                            val actualDuration = metadata.durationMs.takeIf { it > 0L } ?: chapter.durationMs
                            validateCueDuration(chapter.cues, actualDuration)
                            chapterDigests[chapterIndex] = checksum
                            chapterTracks[chapterIndex] = AudioTrack(
                                target.name,
                                chapter.title.ifBlank { metadata.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapterIndex + 1}" },
                                actualDuration,
                                chapter.cues
                            )
                        }
                        zip.closeEntry()
                    }
                    require(chapters.indices.all { chapterTracks[it] != null && chapterDigests[it] != null }) {
                        "The bundle is missing one or more audio chapters."
                    }
                    require(epubName == null || epubPath != null) { "The bundle is missing its original EPUB." }
                    tracks += chapterTracks.map { it!! }
                    digests += chapterDigests.map { it!! }
                    if (sourceSha != null && epubDigest != null) require(sourceSha == epubDigest) { "The bundled EPUB failed its source checksum." }
                    epubPath?.let { epub ->
                        val book = (context.applicationContext as FolioApp).library.importStream { epub.inputStream() }
                        linkedBookId = book.id
                        if (title == "Audiobook") title = book.title
                        if (author.isBlank()) author = book.author
                        check(epub.delete()) { "The original EPUB could not be removed from the audiobook staging folder." }
                    }
                }
            }
            publish(stage, title, author, linkedBookId, tracks, digests, preview)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            stage.deleteRecursively()
        }
    }

    fun file(id: String, relative: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid audiobook identifier." }
        require(relative.isNotBlank() && !relative.contains('\\') && !File(relative).isAbsolute &&
            relative.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid audiobook file path." }
        val directory = File(root, id).canonicalFile
        val target = File(directory, relative).canonicalFile
        require(target.path.startsWith(directory.path + File.separator)) { "Invalid audiobook file path." }
        return target
    }

    suspend fun savePosition(id: String, track: Int, positionMs: Long, speed: Float) = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val old = mutableBooks.value.firstOrNull { it.id == id } ?: return@withLock
            require(track in old.tracks.indices)
            val duration = old.tracks[track].durationMs
            val position = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
            val updated = old.copy(positionTrack = track, positionMs = position, speed = speed.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f,
                lastPlayed = System.currentTimeMillis(), started=old.started || track>0 || position>0)
            writeCursor(updated)
            mutableBooks.value = mutableBooks.value.map { if (it.id == updated.id) updated else it }.sortedByDescending { it.lastPlayed }
        }
    }

    suspend fun addBookmark(id: String, track: Int, positionMs: Long, label: String = ""): AudioBookmark = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val old = mutableBooks.value.firstOrNull { it.id == id } ?: throw IllegalArgumentException("This audiobook is no longer available.")
            require(track in old.tracks.indices)
            require(old.bookmarks.size < MAX_BOOKMARKS) { "This audiobook has reached its bookmark limit." }
            val duration = old.tracks[track].durationMs
            val position = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
            val bookmark = AudioBookmark(UUID.randomUUID().toString(), track, position, cleanText(label, 500))
            replaceLocked(old.copy(bookmarks = old.bookmarks + bookmark))
            bookmark
        }
    }

    suspend fun removeBookmark(id: String, bookmarkId: String) = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val old = mutableBooks.value.firstOrNull { it.id == id } ?: return@withLock
            if (old.bookmarks.any { it.id == bookmarkId }) replaceLocked(old.copy(bookmarks = old.bookmarks.filterNot { it.id == bookmarkId }))
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        require(id.matches(ID_PATTERN))
        load()
        if (AudiobookController.state.value.id == id) {
            AudiobookController.stop(context)
            kotlinx.coroutines.withTimeoutOrNull(5_000L) { AudiobookController.state.first { it.id != id } }
                ?: throw IllegalStateException("Playback did not stop, so this audiobook was kept.")
        }
        mutex.withLock {
            val old = mutableBooks.value.firstOrNull { it.id == id } ?: return@withLock
            val directory = File(root, id)
            val removed = File(root, ".removed-${UUID.randomUUID()}")
            if (directory.exists()) check(directory.renameTo(removed)) { "The audiobook files could not be removed." }
            try {
                val next = mutableBooks.value.filterNot { it.id == id }
                persistLocked(next)
                hashes.remove(id)
                mutableBooks.value = next
            } catch (error: Throwable) {
                if (removed.exists()) removed.renameTo(directory)
                throw error
            }
            if (removed.exists() && !removed.deleteRecursively()) throw IllegalStateException("The audiobook was removed from the library, but its files could not be fully cleaned up.")
        }
    }

    suspend fun markRead(id: String, read: Boolean) = withContext(Dispatchers.IO) {
        load();mutex.withLock {
            mutableBooks.value.firstOrNull { it.id==id }?.let { replaceLocked(it.copy(readState=if(read) "Read" else "Unread")) }
        }
    }

    private suspend fun publish(
        stage: File,
        title: String,
        author: String,
        bookId: String?,
        tracks: List<AudioTrack>,
        checksums: List<String>,
        preview: Boolean = false,
        replaceTitle: Boolean = false
    ): Audiobook = mutex.withLock {
        require(tracks.isNotEmpty() && tracks.size <= MAX_TRACKS && tracks.size == checksums.size)
        currentCoroutineContext().ensureActive()
        val id = audiobookId(checksums, bookId)
        val existing = mutableBooks.value.firstOrNull { it.id == id }
        if (existing != null) {
            val updated = existing.copy(
                title = if(replaceTitle)title else existing.title,
                bookId = bookId ?: existing.bookId,
                preview = existing.preview && preview
            )
            if (updated != existing) replaceLocked(updated)
            return@withLock updated
        }
        val audiobook = Audiobook(id, title.take(500).ifBlank { "Audiobook" }, author.take(500), bookId,
            tracks.toList(), lastPlayed = 0L, preview = preview)
        val destination = File(root, id)
        if (destination.exists()) check(destination.deleteRecursively()) { "An incomplete audiobook import could not be cleared." }
        check(stage.renameTo(destination)) { "The audiobook files could not be committed." }
        try {
            hashes[id] = checksums
            writeCursor(audiobook)
            val next = mutableBooks.value + audiobook
            persistLocked(next)
            mutableBooks.value = next.sortedByDescending { it.lastPlayed }
        } catch (error: Throwable) {
            hashes.remove(id)
            destination.deleteRecursively()
            throw error
        }
        audiobook
    }

    private suspend fun replaceLocked(updated: Audiobook) {
        val next = mutableBooks.value.map { if (it.id == updated.id) updated else it }
        persistLocked(next)
        mutableBooks.value = next.sortedByDescending { it.lastPlayed }
    }

    private fun persistLocked(items: List<Audiobook>) {
        require(items.size <= MAX_BOOKS) { "This library has reached its audiobook limit." }
        val json = JSONObject().put("version", CATALOG_VERSION).put("books", JSONArray(items.map { book ->
            bookJson(book).put("checksums", JSONArray(hashes[book.id].orEmpty()))
        }))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CATALOG_BYTES) { "The audiobook catalog exceeds its safe size limit." }
        BoundedJson.check(bytes.toString(Charsets.UTF_8), 48, MAX_CATALOG_TOKENS)
        val output = catalog.startWrite()
        try {
            output.write(bytes)
            catalog.finishWrite(output)
            check(catalog.baseFile.isFile && catalog.baseFile.length() == bytes.size.toLong()) { "The audiobook catalog could not be saved." }
        } catch (error: Throwable) {
            catalog.failWrite(output)
            throw error
        }
    }

    private fun cursorFile(id: String) = AtomicFile(file(id, "cursor.json"))

    private fun readCursor(book: Audiobook): Audiobook = runCatching {
        val bytes = cursorFile(book.id).openRead().use { readLimited(it, MAX_CURSOR_BYTES) }
        val json = BoundedJson.objectFrom(bytes, MAX_CURSOR_BYTES, 8, 64)
        require(json.optInt("version") == 1)
        val key=json.optString("sourceTrackKey","")
        val track=if(book.sourceRoot!=null && key.matches(SHA256_PATTERN))book.tracks.indexOfFirst{sourceTrackKey(it)==key}.takeIf{it>=0} ?: json.getInt("track") else json.getInt("track")
        require(track in book.tracks.indices)
        val rawPosition = json.getLong("positionMs").coerceAtLeast(0L)
        val duration = book.tracks[track].durationMs
        book.copy(positionTrack = track, positionMs = if (duration > 0L) rawPosition.coerceAtMost(duration) else rawPosition,
            speed = json.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f,
            lastPlayed = json.optLong("lastPlayed", book.lastPlayed).coerceAtLeast(0L),
            started = json.optBoolean("started", book.started || track>0 || rawPosition>0))
    }.getOrDefault(book)

    private fun writeCursor(book: Audiobook) {
        val bytes = JSONObject().put("version", 1).put("track", book.positionTrack).put("positionMs", book.positionMs)
            .put("speed", book.speed.toDouble()).put("lastPlayed", book.lastPlayed).put("started",book.started)
            .put("sourceTrackKey",book.tracks.getOrNull(book.positionTrack)?.let{sourceTrackKey(it)}).toString().toByteArray(Charsets.UTF_8)
        DurableJson.write(cursorFile(book.id), bytes)
    }
    private fun sourceTrackKey(track: AudioTrack): String? = track.sourceUri?.let{hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))}

    private fun stageDirectory(): File = File(root, ".stage-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Audiobook storage is not available." }
    }

    private suspend fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long, digest: MessageDigest): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "An audiobook file exceeds its size limit." }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return total
    }

    private suspend fun readEntryBounded(input: InputStream, limit: Long, digest: MessageDigest?): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "The audiobook manifest exceeds its size limit." }
            digest?.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        require(output.size() <= limit) { "The audiobook catalog exceeds its size limit." }
        return output.toByteArray()
    }

    private fun readMetadata(uri: Uri): AudioMetadata {
        val retriever=MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context,uri)
            AudioMetadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceIn(0L,MAX_TRACK_DURATION_MS) ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        } finally { runCatching{retriever.release()} }
    }

    private fun readMetadata(file: File): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            AudioMetadata(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            )
        } catch (error: Exception) {
            throw IllegalArgumentException("The audio data could not be read. Check that the file is complete and uses a supported format.", error)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** Compares digit runs numerically, so chapter-2 sorts before chapter-10. */
    private fun compareNatural(left: String, right: String): Int {
        val a = left.lowercase()
        val b = right.lowercase()
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ac = a[i]
            val bc = b[j]
            if (ac in '0'..'9' && bc in '0'..'9') {
                var aEnd = i
                while (aEnd < a.length && a[aEnd] in '0'..'9') aEnd++
                var bEnd = j
                while (bEnd < b.length && b[bEnd] in '0'..'9') bEnd++
                var aSignificant = i
                while (aSignificant < aEnd - 1 && a[aSignificant] == '0') aSignificant++
                var bSignificant = j
                while (bSignificant < bEnd - 1 && b[bSignificant] == '0') bSignificant++
                val aLength = aEnd - aSignificant
                val bLength = bEnd - bSignificant
                if (aLength != bLength) return aLength.compareTo(bLength)
                val digits = a.substring(aSignificant, aEnd).compareTo(b.substring(bSignificant, bEnd))
                if (digits != 0) return digits
                val zeroPadding = (aEnd - i).compareTo(bEnd - j)
                if (zeroPadding != 0) return zeroPadding
                i = aEnd
                j = bEnd
            } else {
                if (ac != bc) return ac.compareTo(bc)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun validateCueDuration(cues: List<AudioCue>, durationMs: Long) {
        if (durationMs <= 0L) return
        require(cues.all { it.endMs <= durationMs + CUE_DURATION_SLOP_MS }) {
            "The bundle contains a text cue beyond the decoded audio duration."
        }
    }

    private fun audioExtension(name: String?, mime: String?): String {
        val fromName = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in AUDIO_EXTENSIONS }
        if (fromName != null) return fromName
        return when (mime?.lowercase()?.substringBefore(';')) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/x-m4b", "audio/m4b" -> "m4b"
            "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            else -> throw IllegalArgumentException("Choose an MP3, M4B, M4A, OGG, or WAV audio file.")
        }
    }

    private fun parseBook(json: JSONObject): Audiobook {
        val id = json.getString("id")
        require(id.matches(ID_PATTERN))
        val sourceRoot=json.optString("sourceRoot","").takeIf{it.isNotBlank()}
        val tree=sourceRoot?.let{Uri.parse(it)}
        require(tree==null || (tree.scheme=="content" && android.provider.DocumentsContract.isTreeUri(tree)))
        val tracksJson = json.getJSONArray("tracks")
        require(tracksJson.length() in 1..MAX_TRACKS)
        var cueCount = 0
        val tracks = (0 until tracksJson.length()).map { index ->
            val item = tracksJson.getJSONObject(index)
            val fileName = item.getString("file")
            require(fileName.matches(TRACK_FILE_PATTERN))
            val sourceUri=item.optString("sourceUri","").takeIf{it.isNotBlank()}
            require(if(tree==null)sourceUri==null else sourceUri!=null && validLinkedAudio(tree,Uri.parse(sourceUri)))
            val duration = item.optLong("durationMs", 0L).coerceAtLeast(0L)
            val cuesJson = item.optJSONArray("cues") ?: JSONArray()
            cueCount += cuesJson.length()
            require(cueCount <= MAX_CUES)
            val cues = (0 until cuesJson.length()).map { cueIndex -> parseCue(cuesJson.getJSONObject(cueIndex), duration) }.sortedBy { it.startMs }
            AudioTrack(fileName, cleanText(item.optString("title", ""), 500), duration, cues,sourceUri)
        }
        val bookmarkJson = json.optJSONArray("bookmarks") ?: JSONArray()
        require(bookmarkJson.length() <= MAX_BOOKMARKS)
        val bookmarks = (0 until bookmarkJson.length()).map { index ->
            val item = bookmarkJson.getJSONObject(index)
            AudioBookmark(item.getString("id").take(100), item.getInt("track"), item.getLong("positionMs").coerceAtLeast(0L), cleanText(item.optString("label", ""), 500))
                .also { require(it.track in tracks.indices) }
        }
        val bookId = json.optString("bookId", "").takeIf { it.isNotBlank() }
        require(bookId == null || bookId.matches(ID_PATTERN))
        require(!json.has("preview") || json.opt("preview") is Boolean)
        return Audiobook(id, cleanText(json.optString("title", "Audiobook"), 500).ifBlank { "Audiobook" }, cleanText(json.optString("author", ""), 500),
            bookId, tracks, json.optInt("positionTrack", 0).coerceIn(0, tracks.lastIndex), json.optLong("positionMs", 0L).coerceAtLeast(0L),
            json.optDouble("speed", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f,
            bookmarks, json.optLong("lastPlayed", 0L).coerceAtLeast(0L), json.opt("preview") as? Boolean ?: false,
            json.optBoolean("started",json.optInt("positionTrack",0)>0 || json.optLong("positionMs",0)>0),
            json.optString("readState","Auto").takeIf { it in listOf("Auto","Read","Unread") } ?: "Auto",sourceRoot)
    }

    private fun parseChapter(json: JSONObject, index: Int): BundleChapter {
        val archivePath = json.getString("file")
        require(safeArchivePath(archivePath) && archivePath.startsWith("audio/") && audioExtension(archivePath, null) in AUDIO_EXTENSIONS) {
            "A chapter has an unsupported audio path."
        }
        val duration = json.optLong("durationMs", 0L)
        require(duration >= 0L && duration <= MAX_TRACK_DURATION_MS)
        val cueArray = json.optJSONArray("cues") ?: JSONArray()
        require(cueArray.length() <= MAX_CUES)
        // The declared duration is untrusted; validate cues again against decoded media duration.
        val cues = (0 until cueArray.length()).map { parseCue(cueArray.getJSONObject(it), 0L) }.sortedBy { it.startMs }
        return BundleChapter(archivePath, cleanText(json.optString("title", "Chapter ${index + 1}"), 500), duration, cues)
    }

    private fun parseCue(json: JSONObject, durationMs: Long): AudioCue {
        val start = json.getLong("startMs")
        val end = json.getLong("endMs")
        val href = cleanText(json.getString("href"), 2048)
        val fragment = json.optString("fragment", "").takeIf { it.isNotBlank() }?.let { cleanText(it, 512) }
        val text = cleanText(json.optString("text", ""), 4000)
        val before = cleanText(json.optString("before", ""), 1000)
        val after = cleanText(json.optString("after", ""), 1000)
        val cssSelector = json.optString("cssSelector", "").takeIf { it.isNotBlank() }?.let { cleanText(it, 2048) }
        val progression = if (json.has("progression") && !json.isNull("progression")) json.optDouble("progression", Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 } else null
        require(!json.has("progression") || json.isNull("progression") || progression != null) { "The audiobook bundle contains an invalid text cue progression." }
        require(start >= 0L && end > start && end <= MAX_TRACK_DURATION_MS && (durationMs <= 0L || end <= durationMs + CUE_DURATION_SLOP_MS)) {
            "The audiobook bundle contains an invalid text cue."
        }
        require(href.isNotBlank() && !href.startsWith('/') && !href.startsWith("\\") && !href.contains('\\') && !href.contains(':')) {
            "The audiobook bundle contains an unsafe text cue link."
        }
        return AudioCue(start, end, href, fragment, text, before, after, cssSelector, progression)
    }

    private fun bookJson(book: Audiobook) = JSONObject()
        .put("id", book.id).put("title", book.title).put("author", book.author).put("bookId", book.bookId)
        .put("preview", book.preview).put("started",book.started).put("readState",book.readState).put("sourceRoot",book.sourceRoot)
        .put("tracks", JSONArray(book.tracks.map { track -> JSONObject().put("file", track.file).put("title", track.title)
            .put("durationMs", track.durationMs).put("sourceUri",track.sourceUri).put("cues", JSONArray(track.cues.map { cue -> JSONObject().put("startMs", cue.startMs)
                .put("endMs", cue.endMs).put("href", cue.href).put("fragment", cue.fragment).put("text", cue.text)
                .put("before", cue.before).put("after", cue.after).put("cssSelector", cue.cssSelector).put("progression", cue.progression) })) }))
        .put("bookmarks", JSONArray(book.bookmarks.map { mark -> JSONObject().put("id", mark.id).put("track", mark.track)
            .put("positionMs", mark.positionMs).put("label", mark.label) }))

    private fun parseHashes(array: JSONArray?, trackCount: Int): List<String> = if (array == null || array.length() != trackCount) emptyList() else
        (0 until array.length()).map { array.getString(it).takeIf { hash -> hash.matches(SHA256_PATTERN) } ?: "" }

    private fun audiobookId(checksums: List<String>, bookId: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        checksums.forEach { checksum -> digest.update(checksum.toByteArray(Charsets.US_ASCII)); digest.update(0.toByte()) }
        digest.update((bookId ?: "").toByteArray(Charsets.US_ASCII))
        return hex(digest.digest())
    }

    private fun allowedDirectoryEntries(files: Set<String>): Set<String> = files.flatMap { path ->
        val parts = path.split('/')
        (1 until parts.size).map { parts.take(it).joinToString("/") + "/" }
    }.toSet()

    private fun safeArchivePath(path: String): Boolean = path.isNotBlank() && path.length <= 2048 && !path.startsWith('/') && !path.startsWith('\\') &&
        !path.contains('\\') && !path.contains(':') && path.split('/').filter { it.isNotBlank() }.all { it != "." && it != ".." } &&
        !path.split('/').any { it.isBlank() && path.last() != '/' }

    private fun cleanText(value: String, limit: Int): String = value.filter { it == '\n' || it == '\t' || it >= ' ' && it != '\u007f' }.take(limit)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private data class AudioMetadata(val title: String?, val durationMs: Long, val artist: String?)
    private data class BundleChapter(val archivePath: String, val title: String, val durationMs: Long, val cues: List<AudioCue>)

    private class CompressedLimitInputStream(source: InputStream, private val limit: Long) : java.io.FilterInputStream(source) {
        private var total = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) account(count)
            return count
        }
        private fun account(count: Int) {
            total += count
            require(total <= limit) { "The audiobook bundle exceeds the 4 GB file limit." }
        }
    }

    private companion object {
        const val CATALOG_VERSION = 1
        const val MAX_CATALOG_BYTES = 16 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        const val MAX_CURSOR_BYTES = 4096
        const val MAX_CATALOG_TOKENS = 2_000_000
        const val MAX_TRACKS = 256
        const val MAX_BOOKS = 10_000
        const val MAX_BOOKMARKS = 10_000
        const val MAX_CUES = 300_000
        const val MAX_ZIP_ENTRIES = 512
        const val MAX_TRACK_BYTES = 256L * 1024 * 1024
        const val MAX_EPUB_BYTES = 200L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_BUNDLE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_TRACK_DURATION_MS = 7L * 24 * 60 * 60 * 1000
        const val CUE_DURATION_SLOP_MS = 100L
        val ID_PATTERN = Regex("[a-f0-9]{64}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        val TRACK_FILE_PATTERN = Regex("track-[0-9]{4}\\.(mp3|m4b|m4a|ogg|wav)")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "ogg", "wav")
    }
}
