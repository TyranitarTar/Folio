package app.folio.reader

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AudiobookInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private val created = mutableListOf<String>()
    private lateinit var activity: ActivityScenario<MainActivity>
    private val temp get() = File(app.cacheDir, "v013-audio-test").apply { mkdirs() }

    @Before fun setup() = runBlocking {
        AudiobookController.stop(app); SpeechController.stop(app)
        activity = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        app.audiobooks.load(); Unit
    }
    @After fun cleanup() = runBlocking {
        AudiobookController.stop(app)
        waitFor { AudiobookController.state.value.id == null }
        created.forEach { app.audiobooks.remove(it) }
        activity.close(); temp.deleteRecursively(); Unit
    }
    private fun waitFor(timeout: Long = 12_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end && !predicate()) Thread.sleep(40)
        assertTrue("Timed out: ${AudiobookController.state.value}", predicate())
    }
    private fun wave(seconds: Int, frequency: Int): ByteArray {
        val rate = 16000; val count = seconds * rate
        val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count * 2)
        repeat(count) { i -> buffer.putShort((kotlin.math.sin(2 * Math.PI * frequency * i / rate) * 100).toInt().toShort()) }
        return buffer.array()
    }
    private fun bundle(hostile: Boolean = false): File {
        val epub = app.assets.open("sample.epub").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(epub).joinToString("") { "%02x".format(it) }
        val chapters = JSONArray()
        repeat(2) { index -> chapters.put(JSONObject().put("file", "audio/chapter-${index + 1}.wav")
            .put("title", "Test chapter ${index + 1}").put("durationMs", 6000)
            .put("cues", JSONArray().put(JSONObject().put("startMs", 0).put("endMs", 3000)
                .put("href", "EPUB/chapter1.xhtml").put("text", "A cup of tea and a quiet page.").put("cssSelector", "p:nth-of-type(1)"))
                .put(JSONObject().put("startMs", 3000).put("endMs", 6000).put("href", "EPUB/chapter1.xhtml")
                    .put("text", "That is all you need to begin.").put("before", "A cup of tea and a quiet page. ").put("cssSelector", "p:nth-of-type(1)")))) }
        val manifest = JSONObject().put("format", "folio-audiobook").put("version", 1).put("title", "Folio audio acceptance")
            .put("author", "Folio test fixture").put("language", "en").put("sourceSha256", sha).put("epub", "book.epub").put("chapters", chapters)
        val file = File(temp, if (hostile) "unsafe.foliobook" else "test.foliobook")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("manifest.json", manifest.toString().toByteArray())
            if (hostile) entry("../outside.wav", wave(1, 100))
            entry("book.epub", epub)
            repeat(2) { entry("audio/chapter-${it + 1}.wav", wave(6, 200 + it * 20)) }
        }
        return file
    }
    private fun imported(): Audiobook = runBlocking {
        app.audiobooks.importBundle(Uri.fromFile(bundle())).also { created += it.id }
    }

    @Test fun hybridImportKeepsExactEpubIdentityAndRejectsTraversalBeforePublication() = runBlocking {
        val original = app.library.importSample()
        val before = app.library.books.value.first { it.id == original.id }.json().toString()
        val audio = imported()
        assertEquals(original.id, audio.bookId)
        assertEquals(before, app.library.books.value.first { it.id == original.id }.json().toString())
        assertEquals(2, audio.tracks.size)
        assertEquals(2, audio.tracks[0].cues.size)
        val identity = audio.id
        val repeated = app.audiobooks.importBundle(Uri.fromFile(bundle()))
        assertEquals(identity, repeated.id)
        val beforeIds = app.audiobooks.books.value.map { it.id }
        assertTrue(runCatching { app.audiobooks.importBundle(Uri.fromFile(bundle(true))) }.isFailure)
        assertEquals(beforeIds, app.audiobooks.books.value.map { it.id })
        assertFalse(File(app.filesDir, "outside.wav").exists())
    }

    @Test fun preparedPausedPlayerDoesNotAdvanceAndSeekWaitsBeforePlaying() {
        val book = imported()
        AudiobookController.start(app, book.id, autoplay = false)
        waitFor { AudiobookController.state.value.id == book.id && !AudiobookController.state.value.loading }
        Thread.sleep(800)
        assertFalse(AudiobookController.state.value.playing)
        assertTrue(AudiobookController.state.value.positionMs < 150)
        AudiobookController.seek(app, 3500)
        waitFor { !AudiobookController.state.value.loading && AudiobookController.state.value.positionMs >= 3400 }
        assertFalse(AudiobookController.state.value.playing)
        assertEquals("That is all you need to begin.", AudiobookController.state.value.cue?.text)
        val target = AudiobookController.state.value.positionMs
        Thread.sleep(700)
        assertEquals(target, AudiobookController.state.value.positionMs)
        AudiobookController.play(app)
        waitFor { AudiobookController.state.value.playing && AudiobookController.state.value.positionMs > target + 200 }
        AudiobookController.pause(app)
        waitFor { !AudiobookController.state.value.playing }
        val paused = AudiobookController.state.value.positionMs
        Thread.sleep(600)
        assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs - paused) < 100)
    }

    @Test fun actualCompletionAdvancesTracksAtOnePointFiveAndThreeTimesWithoutEarlyCutoff() {
        val book = imported()
        for (speed in listOf(1.5f, 3f)) {
            AudiobookController.start(app, book.id, 0, 0, autoplay = false)
            waitFor { !AudiobookController.state.value.loading && AudiobookController.state.value.id == book.id }
            AudiobookController.speed(app, speed)
            waitFor { AudiobookController.state.value.speed == speed }
            AudiobookController.play(app)
            waitFor { AudiobookController.state.value.playing }
            val started = SystemClock.elapsedRealtime()
            waitFor(15_000) { AudiobookController.state.value.trackIndex == 1 }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Track ended early at $speed: $elapsed ms", elapsed >= (6000 / speed - 600).toLong())
            assertTrue("Unexpected stall at $speed: $elapsed ms", elapsed < (6000 / speed + 3500).toLong())
            waitFor { AudiobookController.state.value.trackIndex == 1 && AudiobookController.state.value.playing }
            AudiobookController.pause(app)
            waitFor { !AudiobookController.state.value.playing }
        }
    }

    @Test fun positionBookmarksSpeedAndSleepControlsSurviveStoreReload() = runBlocking {
        val book = imported()
        AudiobookController.start(app, book.id, 1, 2500, autoplay = false)
        waitFor { !AudiobookController.state.value.loading && AudiobookController.state.value.trackIndex == 1 }
        AudiobookController.speed(app, 2.5f)
        waitFor { AudiobookController.state.value.speed == 2.5f }
        val bookmark = app.audiobooks.addBookmark(book.id, 1, 2500, "Remember this passage")
        AudiobookController.sleep(app, 5)
        waitFor { (AudiobookController.state.value.sleepRemainingSeconds ?: 0) > 250 }
        AudiobookController.clearSleep(app)
        waitFor { AudiobookController.state.value.sleepRemainingSeconds == null }
        AudiobookController.stop(app)
        waitFor { AudiobookController.state.value.id == null }
        val restored = AudiobookStore(app).also { it.load() }.books.value.first { it.id == book.id }
        assertEquals(1, restored.positionTrack)
        assertTrue(kotlin.math.abs(restored.positionMs - 2500) < 150)
        assertEquals(2.5f, restored.speed, .001f)
        assertEquals(bookmark, restored.bookmarks.single())
        app.audiobooks.removeBookmark(book.id, bookmark.id)
        assertTrue(app.audiobooks.books.value.first { it.id == book.id }.bookmarks.isEmpty())
    }

    @Test fun library_exit_keeps_audio_playing_and_exposes_background_controls() {
        val book=imported()
        activity.onActivity { openAudiobook(it,book.id,true) }
        waitFor { AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing }
        val library=device.wait(Until.findObject(By.desc("Library")),10000)
        assertNotNull(library);library.click()
        assertTrue(device.wait(Until.hasObject(By.desc("Add audiobooks")),10000))
        assertTrue(device.hasObject(By.desc("Pause audiobook")))
        assertEquals(book.id,AudiobookController.state.value.id)
        assertTrue(AudiobookController.state.value.playing)
        device.takeScreenshot(File(app.filesDir,"v0141-audio-library-playing.png"))
        device.findObject(By.desc("Pause audiobook")).click()
        waitFor { !AudiobookController.state.value.playing }
    }

    @Test fun booksAndAudiobooksTabsExposePlayerAndOriginalBook() {
        val book = imported()
        activity.recreate()
        assertTrue(device.wait(Until.hasObject(By.text("Reading")), 15000))
        device.wait(Until.findObject(By.text("Audiobooks")), 10000).click()
        assertTrue(device.wait(Until.hasObject(By.text(book.title)), 10000))
        device.takeScreenshot(File(app.filesDir, "v013-audiobook-library.png"))
        activity.onActivity { openAudiobook(it, book.id) }
        assertTrue(device.wait(Until.hasObject(By.text("Read book")), 10000))
        assertTrue(device.hasObject(By.desc("Play audiobook")))
        device.takeScreenshot(File(app.filesDir, "v013-audiobook-player.png"))
        device.wait(Until.findObject(By.text("Read book")), 10000).click()
        assertTrue(device.wait(Until.hasObject(By.desc("Listen from here")), 20000))
    }

    /** Optional private fixture is copied locally by the test runner, never bundled in source. */
    @Test fun actualGeneratedNarrationLinksToOriginalBookAndStartsTheRealDecoder() = runBlocking {
        val input = File(app.filesDir, "v013-recorded-input.foliobook")
        org.junit.Assume.assumeTrue("No private recorded-book fixture supplied", input.isFile)
        app.library.load()
        val priorIds = app.library.books.value.map { it.id }.toSet()
        val audio = app.audiobooks.importBundle(Uri.fromFile(input)).also { created += it.id }
        try {
            assertNotNull(audio.bookId)
            assertTrue("Measured sample must be labelled a preview", audio.preview)
            assertTrue(audio.tracks.first().durationMs > 1000)
            val cue = audio.tracks.first().cues.first()
            assertEquals(RecordedNarration.Target(0, cue.startMs), RecordedNarration.find(audio, RecordedNarration.locator(cue)))
            val publication = app.engine.open(app.library.file(audio.bookId!!))
            try { assertTrue(publication.readingOrder.any { it.href.toString().substringBefore('#') == cue.href }) }
            finally { publication.close() }
            activity.onActivity { openAudiobook(it, audio.id, true) }
            waitFor(20_000) { AudiobookController.state.value.id == audio.id && AudiobookController.state.value.playing }
            waitFor { AudiobookController.state.value.positionMs > 300 }
            assertTrue(device.wait(Until.hasObject(By.text("Preview recording")), 10000))
            device.takeScreenshot(File(app.filesDir, "v013-private-narration-player.png"))
            device.wait(Until.findObject(By.text("Read book")), 10000).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Stop recorded narration")), 20000))
            File(app.filesDir,"v015-recorded-fixture.json").writeText(JSONObject().put("passed",true).put("realGeneratedAudioPlayed",true)
                .put("hybridTextLinked",true).put("privateFixtureSupplied",true).toString(2))
        } finally {
            AudiobookController.stop(app); waitFor { AudiobookController.state.value.id == null }
            if (audio.bookId !in priorIds) app.library.removeBook(audio.bookId!!)
            input.delete()
        }
    }
}
