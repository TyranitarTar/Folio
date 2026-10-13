@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

@RunWith(AndroidJUnit4::class)
class ContinuousSpeechInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(limit: Long = 180000, condition: () -> Boolean) = runBlocking {
        withTimeout(limit) { while (!condition()) delay(100) }
    }
    @After fun cleanup() {
        SpeechController.stop(app); waitFor { SpeechController.state.value.bookId == null }
        SpeechSettings().save(app); Appearance().save(app); SpeechController.setFollow(app, true)
    }
    @Test fun kokoroPlaysSixColdPassagesWithoutMultiSecondPreparationGaps() {
        SpeechSettings(backend = "kokoro").save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 200 }
        val from = text.cursor(section, 1)!!
        // Force new synthesis, not a benchmark of disk cache speed.
        File(app.cacheDir, "ai-audio").listFiles().orEmpty().forEach { it.delete() }
        val gaps = Collections.synchronizedList(mutableListOf<Double>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observer = scope.launch {
            var passage = ""
            var predictedEnd = 0.0
            SpeechController.state.collect { state ->
                if (state.bookId != book.id || !state.playing || state.preparing || state.audioSample <= 0) return@collect
                val now = SystemClock.elapsedRealtime().toDouble()
                if (state.passage != passage) {
                    if (predictedEnd > 0) gaps.add(now - state.audioSample * 1000.0 / state.sampleRate - predictedEnd)
                    passage = state.passage
                }
                predictedEnd = now + (state.audioTotal - state.audioSample) * 1000.0 / state.sampleRate
            }
        }
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
                SpeechController.start(app, book.id, from.locator)
                waitFor { val state = SpeechController.state.value; state.bookId == book.id &&
                    (state.error != null || (state.playing && !state.preparing && state.audioSample > 2400)) }
                assertNull(SpeechController.state.value.error)
                val firstTrack = activeAudioTrackId()
                waitFor { SpeechController.state.value.error != null || gaps.size >= 5 }
                val finalTrack = activeAudioTrackId()
                assertEquals("Natural passages must retain one Android output FIFO", firstTrack, finalTrack)
                assertNull(SpeechController.state.value.error)
                SpeechController.pause(app); waitFor { !SpeechController.state.value.playing }
                val measured = synchronized(gaps) { gaps.toList() }
                assertTrue("Audio handoffs must not contain synthesis pauses: $measured", measured.all { it < 750 })
                File(app.filesDir, "continuous-speech-validation.json").writeText(JSONObject()
                    .put("backend", "kokoro").put("coldSynthesis", true).put("handoffGapsMillis", JSONArray(measured))
                    .put("maximumGapMillis", measured.maxOrNull()).put("firstAudioTrackId", firstTrack).put("finalAudioTrackId", finalTrack).put("persistentAudioTrack", true).toString(2))
            }
        } finally { observer.cancel(); scope.cancel() }
    }
    private fun activeAudioTrackId(): Int {
        val output = instrumentation.uiAutomation.executeShellCommand("dumpsys audio")
        val dump = java.io.FileInputStream(output.fileDescriptor).bufferedReader().use { it.readText() }
        output.close()
        val config = dump.lineSequence().firstOrNull { it.contains("AudioPlaybackConfiguration") &&
            it.contains("type:android.media.AudioTrack") && it.contains("u/pid:${app.applicationInfo.uid}/") && it.contains("state:started") }
        assertNotNull("A single Folio AudioTrack must be active", config)
        return Regex("piid:(\\d+)").find(config!!)!!.groupValues[1].toInt()
    }
    @Test fun readFromHereStartsAtTheVisibleWordInsideAWrappedParagraph() {
        Appearance(publisher = false, fontSize = 3f).save(app)
        SpeechSettings(backend = "kokoro").save(app)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { it.words.size > 200 }
        val from = text.cursor(section, 65)!!
        runBlocking { app.library.progress(book.id, from.locator) }
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            Thread.sleep(2500)
            val captured = CompletableDeferred<Pair<Locator, Locator>>()
            scenario.onActivity { a -> a.lifecycleScope.launch {
                val nav = a.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                captured.complete(nav.firstVisibleElementLocator()!! to nav.visibleReadingAnchor()!!)
            } }
            val anchors = runBlocking { withTimeout(15000) { captured.await() } }
            val old = text.locate(anchors.first)!!
            val expected = text.locate(anchors.second)!!
            assertTrue("The page should begin within a wrapped paragraph: old=$old expected=$expected", expected.wordIndex > old.wordIndex)
            device.wait(Until.findObject(By.text("Read aloud")), 15000)!!.click()
            device.wait(Until.findObject(By.text("Read from here")), 15000)!!.click()
            waitFor { SpeechController.state.value.bookId == book.id &&
                (SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 2400) }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app); waitFor { !SpeechController.state.value.playing }
            waitFor { ReaderCursorStore(app).read(book.id, "speech")?.wordIndex == expected.wordIndex }
            assertEquals(expected.locator.text.highlight, SpeechController.state.value.passage.split(' ').first())
            assertTrue(SpeechController.follow.value)
            File(app.filesDir, "read-from-here-validation.json").writeText(JSONObject()
                .put("oldParagraphWord", old.wordIndex).put("visibleWord", expected.wordIndex)
                .put("spokenFirstWord", SpeechController.state.value.passage.split(' ').first()).put("passed", true).toString(2))
        }
    }
}
