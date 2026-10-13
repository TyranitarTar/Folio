package app.folio.reader

import android.content.Intent
import android.media.AudioManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI synthesis and AudioTrack checkpoints. No simulated engine or word timing. */
@RunWith(AndroidJUnit4::class)
class AiSpeechInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private fun waitFor(limit: Long = 120000, condition: () -> Boolean) = runBlocking {
        withTimeout(limit) { while (!condition()) delay(100) }
    }
    @After fun cleanup() {
        SpeechController.stop(app)
        waitFor { SpeechController.state.value.bookId == null }
        SpeechSettings().save(app); SpeechController.setFollow(app, true)
    }
    @Test fun localVoicesSynthesizeAndResumeActualAudioAfterServiceRestart() {
        val engine = KokoroEngine(app)
        val summary = JSONObject()
        try {
            val phrase = "The rain had stopped. Emma opened the book and began to read, quietly and clearly."
            for (voice in KokoroEngine.voices) {
                val clip = runBlocking { engine.generate(phrase, voice.id, 1f) }
                assertEquals(24000, clip.sampleRate)
                assertTrue(clip.samples.size > 24000)
                assertTrue(clip.samples.all { it.isFinite() })
                writeWav(File(app.filesDir, "ai-${voice.name}-sample.wav"), clip)
                summary.put(voice.name, JSONObject().put("samples", clip.samples.size).put("sampleRate", clip.sampleRate)
                    .put("synthesisMillis", clip.synthesisMillis).put("durationSeconds", clip.samples.size / 24000.0))
            }
        } finally { engine.close() }
        SpeechSettings(backend = "kokoro").save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 30 }
        val requested = text.cursor(section, 12)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            Thread.sleep(1500)
            SpeechController.start(app, book.id, requested.locator)
            waitFor { SpeechController.state.value.bookId == book.id &&
                (SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 12000) }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            waitFor { AiListeningStore(app).read(book.id)?.playedSamples == SpeechController.state.value.audioSample }
            val paused = AiListeningStore(app).read(book.id)!!
            assertTrue(paused.playedSamples > 0)
            val pausedState = SpeechController.state.value
            // An appearance change and closing the Activity cannot change the listening position.
            it.recreate(); Thread.sleep(500)
            assertEquals(pausedState.audioSample, SpeechController.state.value.audioSample)
            SpeechController.stop(app)
            waitFor { SpeechController.state.value.bookId == null }
            SpeechController.start(app, book.id)
            waitFor { SpeechController.state.value.error != null || SpeechController.state.value.audioSample > paused.playedSamples + 2400 }
            assertNull(SpeechController.state.value.error)
            assertEquals(paused.word, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            val restored = SpeechController.state.value.audioSample
            assertTrue(restored > paused.playedSamples)
            SpeechController.settings(app, SpeechSettings(rate = 1.25f, backend = "kokoro", aiVoice = 3))
            Thread.sleep(300)
            assertFalse("Changing AI voice while paused must not start audio", SpeechController.state.value.playing)
            assertEquals(paused.word, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.selectedVoice == "kokoro:3" && SpeechController.state.value.audioSample > 2400) }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            assertTrue(SpeechController.state.value.locator!!.text.highlight!!.split(' ').size > 1)
            val backgroundPoint = AiListeningStore(app)
            waitFor { backgroundPoint.read(book.id)?.playedSamples == SpeechController.state.value.audioSample }
            val beforeBackground = backgroundPoint.read(book.id)!!
            SpeechController.resume(app)
            UiDevice.getInstance(instrumentation).pressHome()
            waitFor {
                val now = backgroundPoint.read(book.id)
                now != null && (now.section > beforeBackground.section || now.word > beforeBackground.word ||
                    (now.key == beforeBackground.key && now.playedSamples > beforeBackground.playedSamples + 2400))
            }
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            waitFor { backgroundPoint.read(book.id)?.playedSamples == SpeechController.state.value.audioSample }
            val beforeSwitch = ReaderCursorStore(app).read(book.id, "speech")!!
            SpeechController.settings(app, SpeechSettings(backend = "android"))
            Thread.sleep(300)
            assertFalse(SpeechController.state.value.playing)
            assertEquals(beforeSwitch.wordIndex, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            summary.put("pauseSample", paused.playedSamples).put("restartSample", restored).put("cursorWord", paused.word)
        }
        File(app.filesDir, "ai-validation.json").writeText(summary.toString(2))
    }
    @Test fun progressWriteFailurePausesAndCannotResumeUnsavedAudio() {
        SpeechSettings(backend = "kokoro").save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 30 }
        val point = text.cursor(section, 12)!!
        val directory = File(app.filesDir, "reader-state").apply { mkdirs() }
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            try {
                SpeechController.start(app, book.id, point.locator)
                waitFor { SpeechController.state.value.bookId == book.id &&
                    (SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 2400) }
                assertNull("Narration must start with a healthy writable checkpoint directory", SpeechController.state.value.error)
                assertTrue(SpeechController.state.value.playing)
                // Inject failure during real playback, after startup reset/model/cache preparation succeeded.
                assertTrue(directory.setWritable(false, true))
                waitFor { SpeechController.state.value.bookId == book.id && SpeechController.state.value.error != null }
                assertTrue(SpeechController.state.value.error!!.contains("progress", ignoreCase = true))
                assertFalse(SpeechController.state.value.playing)
                SpeechController.resume(app)
                Thread.sleep(500)
                assertFalse("Storage errors must not resume uncheckpointed audio", SpeechController.state.value.playing)
            } finally {
                assertTrue(directory.setWritable(true, true))
                SpeechController.stop(app)
                waitFor { SpeechController.state.value.bookId == null }
            }
        }
    }
    @Test fun pausingWhileReplacementAudioBuffersNeverInheritsThePreviousOffset() {
        SpeechSettings(backend = "kokoro").save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val pub = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(pub) } } finally { pub.close() }
        val section = text.sections.indexOfFirst { it.words.size > 200 }
        val requested = text.cursor(section, 12)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            SpeechController.start(app, book.id, requested.locator)
            waitFor { SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 12000 }
            assertNull(SpeechController.state.value.error)
            val passage = SpeechController.state.value.passage
            SpeechController.settings(app, SpeechSettings(backend = "kokoro", aiVoice = 3))
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.preparing && SpeechController.state.value.preparation.contains("Buffering")) }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
            assertEquals("Prepared replacement audio has not been presented", 0, SpeechController.state.value.audioSample)
            waitFor { AiListeningStore(app).read(book.id)?.playedSamples == 0 }
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.selectedVoice == "kokoro:3" && SpeechController.state.value.audioSample > 2400) }
            assertNull(SpeechController.state.value.error)
            assertEquals(passage, SpeechController.state.value.passage)
            SpeechController.pause(app)
        }
    }
    @Test fun pauseDuringQueuedHandoffPinIoStopsOutputWithoutAdvancingTheNewPassage() {
        SpeechSettings(backend = "kokoro").save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 200 }
        val point = text.cursor(section, 12)!!
        val cacheLock = AiListeningStore::class.java.getDeclaredField("cacheLock").apply { isAccessible = true }.get(null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val audio = app.getSystemService(AudioManager::class.java)
        var holder: Thread? = null
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            try {
                SpeechController.start(app, book.id, point.locator)
                waitFor { SpeechController.state.value.bookId == book.id &&
                    (SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 2400) }
                assertNull(SpeechController.state.value.error)
                waitFor(5000) { audio.isMusicActive }
                val initialPassage = SpeechController.state.value.passage
                holder = Thread({ synchronized(cacheLock) {
                    entered.countDown(); release.await(60, TimeUnit.SECONDS)
                } }, "Folio test suspended cache IO").apply { isDaemon = true; start() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                waitFor(45_000) { SpeechController.state.value.error != null ||
                    (SpeechController.state.value.passage != initialPassage && SpeechController.state.value.preparing &&
                        SpeechController.state.value.preparationStage=="buffering") }
                assertNull(SpeechController.state.value.error)
                val nextPassage = SpeechController.state.value.passage
                SpeechController.pause(app)
                waitFor(5000) { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
                assertEquals("Unactivated next metadata must retain its zero offset", 0, SpeechController.state.value.audioSample)
                waitFor(5000) { !audio.isMusicActive }
                waitFor(5000) { AiListeningStore(app).read(book.id)?.playedSamples == 0 }
                release.countDown(); holder?.join(2000)
                Thread.sleep(500)
                assertFalse(SpeechController.state.value.playing)
                assertEquals(nextPassage, SpeechController.state.value.passage)
                assertEquals(0, SpeechController.state.value.audioSample)
                assertEquals(0, AiListeningStore(app).read(book.id)!!.playedSamples)
                // Cancelled pin completion cannot restart or skip the queued sentence.
                SpeechController.resume(app)
                waitFor { SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 2400 }
                assertNull(SpeechController.state.value.error)
                assertEquals(nextPassage, SpeechController.state.value.passage)
                SpeechController.pause(app)
            } finally { release.countDown(); holder?.join(2000) }
        }
    }
    private fun writeWav(file: File, clip: AiAudioClip) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun short(value: Int) { out.writeByte(value and 255); out.writeByte((value ushr 8) and 255) }
            fun int(value: Int) { short(value and 65535); short(value ushr 16) }
            out.writeBytes("RIFF"); int(36 + clip.samples.size * 2); out.writeBytes("WAVEfmt "); int(16)
            short(1); short(1); int(clip.sampleRate); int(clip.sampleRate * 2); short(2); short(16)
            out.writeBytes("data"); int(clip.samples.size * 2)
            clip.samples.forEach { short((it.coerceIn(-1f, 1f) * 32767).toInt()) }
        }
    }
}
