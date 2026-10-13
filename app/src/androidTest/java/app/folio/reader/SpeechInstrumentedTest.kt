package app.folio.reader

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real installed Android engine, or verifies the usable missing-voice path. */
@RunWith(AndroidJUnit4::class)
class SpeechInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    @After fun cleanup() {
        SpeechController.stop(app)
        waitFor { SpeechController.state.value.bookId == null }
        SpeechController.setFollow(app, true)
        SpeechSettings().save(app)
    }
    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(30000) { while (!condition()) delay(100) }
    }

    private fun offlineVoices(): List<String> = runBlocking {
        val result = CompletableDeferred<List<String>>()
        var engine: TextToSpeech? = null
        instrumentation.runOnMainSync {
            engine = TextToSpeech(app) { status ->
                // The engine constructor must return before reading its voices.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    result.complete(if (status == TextToSpeech.SUCCESS) engine?.voices.orEmpty().filter {
                        it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    }.map { it.name } else emptyList())
                }
            }
        }
        try { withTimeout(20000) { result.await() } }
        finally { instrumentation.runOnMainSync { engine?.shutdown() } }
    }

    @Test fun speechStartsOnlyOnRequestAndPreservesItsOwnPositionWhenPaused() {
        SpeechController.stop(app)
        waitFor { SpeechController.state.value.bookId == null }
        val voices = offlineVoices()
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 30 }
        assertTrue(section >= 0)
        val requested = text.cursor(section, 12)!!
        val reading = text.cursor(section, 25)!!
        runBlocking { app.library.progress(book.id, reading.locator) }
        val cursors = ReaderCursorStore(app)
        cursors.write(book.id, "speech", text.cursor(section, 0)!!)
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            val open = device.wait(Until.findObject(By.text("Read aloud")), 20000)
            assertNotNull("Speech controls are missing", open)
            open.click()
            assertTrue(device.wait(Until.hasObject(By.text("Resume listening")), 10000))
            assertFalse("Opening speech controls must never start audio", SpeechController.state.value.playing)
            assertNull(SpeechController.state.value.bookId)
            // Allow the navigator's normal initial position publication to settle before comparing it.
            var initialReading = app.library.books.value.first { it.id == book.id }.position()
            runBlocking { withTimeout(10000) {
                var stable = 0
                while (stable < 4) {
                    delay(200)
                    val updated = app.library.books.value.first { it.id == book.id }.position()
                    if (updated == initialReading) stable++ else { stable = 0; initialReading = updated }
                }
            } }
            SpeechController.setFollow(app, false)
            assertTrue("Legacy follow opt-out must migrate on", SpeechController.follow.value)
            SpeechController.start(app, book.id, requested.locator)
            waitFor { SpeechController.state.value.bookId == book.id && !SpeechController.state.value.preparing &&
                (SpeechController.state.value.playing || SpeechController.state.value.error != null) }
            assertEquals("android", SpeechController.state.value.backend)
            if (voices.isEmpty() && SpeechController.state.value.error != null) {
                assertFalse(SpeechController.state.value.playing)
                assertNotNull("Missing offline voice must explain how to enable one", SpeechController.state.value.error)
                assertTrue(SpeechController.state.value.error!!.contains("voice", ignoreCase = true) ||
                    SpeechController.state.value.error!!.contains("engine", ignoreCase = true))
                if (!device.hasObject(By.text("Android Speech settings"))) {
                    repeat(2) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .8f) }
                }
                assertTrue(device.wait(Until.hasObject(By.text("Android Speech settings")), 10000))
            } else {
                assertNull(SpeechController.state.value.error)
                assertTrue("Successful Android speech must expose installed offline voices", SpeechController.state.value.voices.isNotEmpty())
                assertTrue(SpeechController.state.value.playing)
                // The cursor file is written by actual engine start/range callbacks, not by generated speech.
                waitFor { cursors.read(book.id, "speech")?.let { it.sectionIndex == section && it.wordIndex >= requested.wordIndex } == true }
                SpeechController.pause(app)
                waitFor { !SpeechController.state.value.playing }
                val paused = SpeechController.state.value.locator!!
                waitFor { cursors.read(book.id, "speech")?.locator == paused }
                val voice = SpeechController.state.value.selectedVoice
                SpeechController.settings(app, SpeechSettings(voice, 1.25f))
                Thread.sleep(300)
                assertFalse("Changing speed while paused must not start narration", SpeechController.state.value.playing)
                assertEquals(paused, SpeechController.state.value.locator)
                assertEquals(paused, cursors.read(book.id, "speech")!!.locator)
                assertNotNull("Reading follows speech without losing its separate listening bookmark",
                    app.library.books.value.first { it.id == book.id }.position())
                SpeechController.resume(app)
                waitFor { SpeechController.state.value.playing }
                SpeechController.pause(app)
                waitFor { !SpeechController.state.value.playing }
            }
            SpeechController.stop(app)
            waitFor { SpeechController.state.value.bookId == null }
        }
        SpeechController.setFollow(app, true)
        SpeechSettings().save(app)
    }
}
