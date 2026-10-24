package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReliabilityRegressionTest {
    private lateinit var context: Context
    private lateinit var store: LibraryStore
    private fun isolated(): Context {
        val app = ApplicationProvider.getApplicationContext<FolioApp>()
        val files = java.nio.file.Files.createTempDirectory("folio-v6-").toFile()
        val cache = File(files, "cache").apply { mkdirs() }
        return object : ContextWrapper(app) { override fun getFilesDir() = files; override fun getCacheDir() = cache }
    }
    @Before fun setup() { context = isolated(); store = LibraryStore(context, BookEngine(context)) }
    private suspend fun legacySample(): Book {
        val bytes = context.assets.open("sample.epub").use { it.readBytes() }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(context.filesDir, "library").mkdirs(); store.file(id).writeBytes(bytes)
        val publication = store.engine.open(store.file(id))
        val locator = try { publication.positions().last() } finally { publication.close() }
        return Book(id, "Old sample", "Folio", 12, 1234, locator.toJSON().toString(), .8)
    }
    @Test fun migrationIsolatesBadRecordAndRetainsOriginalAndFutureImports() = runBlocking {
        val good = legacySample()
        val raw = JSONObject().put("version", 1).put("books", JSONArray(listOf(good.json(), good.copy(id = "bad").json()))).toString()
        File(context.filesDir, "library/library.json").writeText(raw)
        store.load()
        assertEquals(listOf(good), store.books.value)
        assertNotNull(store.recovery.value)
        assertEquals(raw, File(context.filesDir, "library/library-v1-preserved.json").readText())
        assertEquals(good.id, store.importSample().id)
        store.progress(good.id, good.position()!!)
        val reloaded = LibraryStore(context, BookEngine(context)); reloaded.load()
        assertEquals(1, reloaded.books.value.size)
        assertEquals(raw, File(context.filesDir, "library/library.json").readText())
    }
    @Test fun truncatedLegacyTailStillRecoversCompleteBooks() = runBlocking {
        val good = legacySample()
        File(context.filesDir, "library/library.json").writeText("{\"version\":1,\"books\":[${good.json()}, {\"id\":")
        store.load()
        assertEquals(good.id, store.books.value.single().id)
        assertNotNull(store.recovery.value)
    }
    @Test fun deeplyNestedDamagedRecordCannotBlockHealthyLegacyBook() = runBlocking {
        val good = legacySample()
        val bad = "{\"id\":\"bad\",\"padding\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        val raw = "{\"version\":1,\"books\":[$bad,${good.json()}]}"
        File(context.filesDir, "library/library.json").writeText(raw)
        store.load()
        assertEquals(good.id, store.books.value.single().id)
        assertEquals(raw, File(context.filesDir, "library/library-v1-preserved.json").readText())
    }
    @Test fun damagedSqliteIsPreservedAndStoredEpubCanBeRecovered() = runBlocking {
        val original = legacySample()
        val bytes = "not a sqlite database".repeat(100).toByteArray()
        File(context.filesDir, "library/library.sqlite").writeBytes(bytes)
        store.load()
        assertNotNull(store.recovery.value)
        val saved = File(context.filesDir, "library").listFiles()!!.single { it.name.startsWith("library.sqlite.corrupt-") }
        assertArrayEquals(bytes, saved.readBytes())
        assertEquals(1, store.recoverStoredBooks())
        assertEquals(original.id, store.books.value.single().id)
    }
    @Test fun deletionReimportDoesNotReviveSpeechGuidedOrAiState() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        val cursor = text.cursor(0, 3)!!
        ReaderCursorStore(context).write(book.id, "speech", cursor); ReaderCursorStore(context).write(book.id, "guided", cursor)
        AiListeningStore(context).write(book.id, AiListeningPoint(0, 3, "a".repeat(64), 100))
        store.removeBooks(setOf(book.id)); store.importSample()
        assertNull(ReaderCursorStore(context).read(book.id, "speech"))
        assertNull(ReaderCursorStore(context).read(book.id, "guided"))
        assertNull(AiListeningStore(context).read(book.id))
    }
    @Test fun repeatedContextsKeepCorrectElementSelector() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val base = try { BookText.load(publication).first()!!.locator } finally { publication.close() }
        fun locator(selector: String): Locator = requireNotNull(Locator.fromJSON(base.toJSON().apply {
            getJSONObject("locations").put("cssSelector", selector)
            put("text", JSONObject().put("before", "Same previous. ").put("highlight", "Repeat").put("after", " same next."))
        }))
        val first = locator("#first"); val second = locator("#second")
        val text = BookText(listOf(TextSection(base.href, "Repeats", listOf(ReadingWord("Repeat", first, 6), ReadingWord("Repeat", second, 6)), emptyList())))
        assertEquals(1, text.locate(second)!!.wordIndex)
    }
    @Test fun repeatedWordsWithinOneElementUseCharacterOffset() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val base = try { BookText.load(publication).first()!!.locator } finally { publication.close() }
        fun locator(offset: Int) = base.copy(locations = base.locations.copy(otherLocations = mapOf("cssSelector" to "#repeat", "folioOffset" to offset)),
            text = Locator.Text("repeat repeat ", "repeat", " repeat repeat"))
        val first = locator(100); val second = locator(1000)
        val text = BookText(listOf(TextSection(base.href, "Repeat", listOf(ReadingWord("repeat", first, 7), ReadingWord("repeat", second, 7)), emptyList())))
        assertEquals(1, text.locate(second)!!.wordIndex)
    }
    @Test fun oldReadingSessionCannotOverwriteNewerPositionOrReimport() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        val early = text.cursor(0, 3)!!.locator; val later = text.cursor(0, 10)!!.locator
        val old = store.beginReading(book.id)
        store.queueProgress(book.id, early, session = old)
        val current = store.beginReading(book.id)
        store.queueProgress(book.id, later, immediate = true, session = current)
        store.queueProgress(book.id, early, immediate = true, session = old)
        store.snapshot()
        assertEquals(later, store.books.value.single().position())
        store.removeBooks(setOf(book.id)); store.importSample()
        store.queueProgress(book.id, later, immediate = true, session = current)
        store.snapshot()
        assertNull(store.books.value.single().position())
    }
    @Test fun fullBackupRoundTripRetainsMarksAllModesShelvesAndTypedSettings() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        val cursor = text.cursor(0, 5)!!
        store.progress(book.id, cursor.locator); store.addMark(book.id, cursor.locator, true, "Remember this.")
        store.addShelf("Empty shelf"); store.assignShelves(setOf(book.id), listOf("English", "Study"))
        ReaderCursorStore(context).write(book.id, "speech", cursor); ReaderCursorStore(context).write(book.id, "guided", cursor)
        val ai = AiListeningPoint(0, 5, "b".repeat(64), 123)
        AiListeningStore(context).write(book.id, ai)
        SpeechSettings(backend = "supertonic", aiVoice = 3, supertonicVoice = 7, supertonicLanguage = "da", picoThreads = 8,plapre3Voice=4,plapre3Threads=6,passageTargetWords=30,lookaheadPassages=8,startBufferSeconds=24).save(context)
        HomePreferences(status="Unread").save(context)
        Appearance().withAwake(ReaderAwakePolicy("timed", 15)).save(context)
        val origin = SourceOrigin("WIKISOURCE", "Prøve", "da", SourceUrls.wikiPage("da", "Prøve"), "a".repeat(64))
        store.update(book.id) { it.copy(origin = origin,readState="Read") }
        val output = ByteArrayOutputStream(); LibraryBackup(context, store).exportTo(output, true)
        val other = isolated(); val destination = LibraryStore(other, BookEngine(other))
        val result = LibraryBackup(other, destination).restoreFrom(ByteArrayInputStream(output.toByteArray()))
        assertTrue(result.contains("1 books"))
        val restored = destination.books.value.single()
        assertTrue(restored.isRead)
        assertEquals("Unread",HomePreferences.load(other).status)
        assertEquals(30,SpeechSettings.load(other).passageTargetWords)
        assertEquals(8,SpeechSettings.load(other).lookaheadPassages)
        assertEquals(24,SpeechSettings.load(other).startBufferSeconds)
        assertEquals("Remember this.", restored.highlights.single().note)
        assertEquals(listOf("English", "Study"), restored.shelves)
        assertTrue("Empty shelf" in destination.shelves.value)
        assertEquals(cursor, ReaderCursorStore(other).read(book.id, "speech"))
        assertEquals(cursor, ReaderCursorStore(other).read(book.id, "guided"))
        assertEquals(ai, AiListeningStore(other).read(book.id))
        assertEquals(7, SpeechSettings.load(other).supertonicVoice)
        assertEquals(8, SpeechSettings.load(other).picoThreads)
        assertEquals(4,SpeechSettings.load(other).plapre3Voice)
        assertEquals(6,SpeechSettings.load(other).plapre3Threads)
        assertEquals("da", SpeechSettings.load(other).supertonicLanguage)
        assertEquals(ReaderAwakePolicy("timed", 15), Appearance.load(other).awakePolicy)
        assertEquals(origin, restored.origin)
    }
    @Test fun badBackupPathCannotWriteOutsideStagingOrChangeLibrary() = runBlocking {
        val book = store.importSample()
        val data = ByteArrayOutputStream()
        ZipOutputStream(data).use { zip -> zip.putNextEntry(ZipEntry("../library.json")); zip.write("bad".toByteArray()); zip.closeEntry() }
        assertTrue(runCatching { LibraryBackup(context, store).restoreFrom(ByteArrayInputStream(data.toByteArray())) }.isFailure)
        assertEquals(book.id, store.books.value.single().id)
        assertTrue(store.file(book.id).isFile)
    }
    @Test fun badParcelableShareIsHandledWithoutCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val method = MainActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java).apply { isAccessible = true }
            val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            method.invoke(controller.get(), intent)
            assertEquals(Intent.ACTION_MAIN, intent.action)
        } finally { controller.destroy() }
    }
    @Test fun shelfRenameIsTransactionalAndPreservesProgressAndAnnotations() = runBlocking {
        val book = store.importSample(); store.update(book.id) { it.copy(progress = .8, lastRead = 123) }
        store.assignShelves(setOf(book.id), listOf("Old", "Other")); store.renameShelf("Old", "New")
        val reloaded = LibraryStore(context, BookEngine(context)); reloaded.load()
        val saved = reloaded.books.value.single()
        assertEquals(listOf("New", "Other"), saved.shelves)
        assertEquals(.8, saved.progress, 0.0)
        assertEquals(123L, saved.lastRead)
        assertFalse("Old" in reloaded.shelves.value)
    }
    private fun changeManifest(backup: ByteArray, change: (JSONObject) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { destination -> ZipInputStream(ByteArrayInputStream(backup)).use { source ->
            while (true) {
                val entry = source.nextEntry ?: break
                val bytes = source.readBytes()
                destination.putNextEntry(ZipEntry(entry.name))
                destination.write(if (entry.name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8)).apply(change).toString().toByteArray() else bytes)
                destination.closeEntry()
            }
        } }
        return output.toByteArray()
    }
    @Test fun partialModeBackupDoesNotEraseIndependentListeningPosition() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        val cursor = text.cursor(0, 5)!!
        ReaderCursorStore(context).write(book.id, "speech", cursor)
        ReaderCursorStore(context).write(book.id, "guided", cursor)
        val ai = AiListeningPoint(0, 5, "c".repeat(64), 100)
        AiListeningStore(context).write(book.id, ai)
        val output = ByteArrayOutputStream(); LibraryBackup(context, store).exportTo(output, false)
        val partial = changeManifest(output.toByteArray()) { manifest ->
            for (kind in listOf("speech", "ai")) {
                manifest.getJSONObject("states").remove("${book.id}-$kind.json")
                manifest.getJSONObject("stateModified").remove("${book.id}-$kind.json")
            }
        }
        LibraryBackup(context, store).restoreFrom(ByteArrayInputStream(partial))
        assertEquals(cursor, ReaderCursorStore(context).read(book.id, "speech"))
        assertEquals(ai, AiListeningStore(context).read(book.id))
    }
    @Test fun newerListeningModeSurvivesOlderBackupEvenWithoutVisualProgressChange() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        val old = text.cursor(0, 5)!!; val latest = text.cursor(0, 10)!!
        val file = File(context.filesDir, "reader-state/${book.id}-speech.json")
        ReaderCursorStore(context).write(book.id, "speech", old); assertTrue(file.setLastModified(1000))
        val output = ByteArrayOutputStream(); LibraryBackup(context, store).exportTo(output, false)
        ReaderCursorStore(context).write(book.id, "speech", latest); assertTrue(file.setLastModified(2000))
        LibraryBackup(context, store).restoreFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(latest, ReaderCursorStore(context).read(book.id, "speech"))
    }
    @Test fun oversizedCheckpointIsRejectedBeforeMergingBookRecords() = runBlocking {
        val book = store.importSample(); val publication = store.engine.open(store.file(book.id))
        val text = try { BookText.load(publication) } finally { publication.close() }
        ReaderCursorStore(context).write(book.id, "guided", text.first()!!)
        val saved = ReaderCursorStore(context).read(book.id, "guided")
        val output = ByteArrayOutputStream(); LibraryBackup(context, store).exportTo(output, false)
        val malformed = changeManifest(output.toByteArray()) { manifest ->
            manifest.getJSONArray("books").getJSONObject(0).put("highlights", JSONArray(listOf(
                Mark("new", text.first()!!.locator.toJSON().toString(), "new").json())))
            manifest.getJSONObject("states").getJSONObject("${book.id}-guided.json").put("padding", "x".repeat(65536))
        }
        assertTrue(runCatching { LibraryBackup(context, store).restoreFrom(ByteArrayInputStream(malformed)) }.isFailure)
        assertEquals(book, store.books.value.single())
        assertEquals(saved, ReaderCursorStore(context).read(book.id, "guided"))
    }
    @Test fun deeplyNestedBackupIsRejectedBeforeRecursiveJsonParsing() = runBlocking {
        val book = store.importSample()
        val raw = "{\"padding\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("manifest.json")); zip.write(raw.toByteArray()); zip.closeEntry() }
        val error = runCatching { LibraryBackup(context, store).restoreFrom(ByteArrayInputStream(output.toByteArray())) }.exceptionOrNull()
        assertTrue("Expected bounded rejection, got $error", error is IllegalArgumentException)
        assertEquals(book, store.books.value.single())
    }
}
