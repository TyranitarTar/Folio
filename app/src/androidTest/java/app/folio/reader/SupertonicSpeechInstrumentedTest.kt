package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Exercises the shipped ONNX graphs, Danish characters and real AudioTrack checkpoints. */
@RunWith(AndroidJUnit4::class)
class SupertonicSpeechInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private fun waitFor(limit: Long = 180000, condition: () -> Boolean) = runBlocking {
        withTimeout(limit) { while (!condition()) delay(100) }
    }
    @After fun cleanup() {
        SpeechController.stop(app)
        waitFor { SpeechController.state.value.bookId == null }
        SpeechSettings().save(app); SpeechController.setFollow(app, true)
    }
    @Test fun tenVoicesDanishAndEnglishResumeAndSwitchWithoutLosingThePassage() {
        val engine = SupertonicEngine(app)
        val summary = JSONObject()
        val english = "The rain had stopped. She opened the book and began to read, quietly and clearly."
        val danish = "Regnen var holdt op. Hun åbnede bogen og læste højt. På bordet lå tre æbler og en skål med blåbær."
        try {
            val reference = runBlocking { engine.generate(english, 0, 1f, "en") }
            for (voice in SupertonicEngine.voices) {
                val clip = if (voice.id == 0) reference else runBlocking { engine.generate(english, voice.id, 1f, "en") }
                assertEquals(44100, clip.sampleRate)
                assertTrue(clip.samples.size > 44100)
                assertTrue(clip.samples.all { it.isFinite() })
                summary.put("voice${voice.id + 1}", metrics(clip))
                if (voice.id == 0) writeWav(File(app.filesDir, "supertonic-en-sample.wav"), clip)
            }
            val regenerated = runBlocking { engine.generate(english, 0, 1f, "en") }
            assertArrayEquals("Fixed-seed regeneration must preserve cached sample timing", reference.samples, regenerated.samples, 0f)
            for (id in listOf(0, 5)) {
                val clip = runBlocking { engine.generate(danish, id, 1f, "da") }
                assertEquals(44100, clip.sampleRate)
                assertTrue(clip.samples.size > 44100)
                summary.put("danishVoice${id + 1}", metrics(clip))
                if (id == 0) writeWav(File(app.filesDir, "supertonic-da-sample.wav"), clip)
            }
            summary.put("deterministicRegeneration", true)
        } finally { engine.close() }
        val settings = SpeechSettings(backend = "supertonic", supertonicLanguage = "da")
        settings.save(app)
        SpeechController.setFollow(app, false)
        val book = runBlocking { app.library.importSample() }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        val text = try { runBlocking { BookText.load(publication) } } finally { publication.close() }
        val section = text.sections.indexOfFirst { s -> s.words.any { it.text.contains("Regnen") } }
        assertTrue("The original sample contains Danish text", section >= 0)
        val word = text.sections[section].words.indexOfFirst { it.text.contains("Regnen") }
        val requested = text.cursor(section, word)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
            Thread.sleep(1500)
            SpeechController.start(app, book.id, requested.locator)
            waitFor { SpeechController.state.value.bookId == book.id &&
                (SpeechController.state.value.error != null || SpeechController.state.value.audioSample > 15000) }
            assertNull(SpeechController.state.value.error)
            assertEquals(44100, SpeechController.state.value.sampleRate)
            assertEquals("supertonic:da:0", SpeechController.state.value.selectedVoice)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            waitFor { AiListeningStore(app).read(book.id)?.playedSamples == SpeechController.state.value.audioSample }
            val paused = AiListeningStore(app).read(book.id)!!
            assertTrue(paused.playedSamples > 0)
            val passage = SpeechController.state.value.passage
            it.recreate(); Thread.sleep(500)
            assertEquals(paused.playedSamples, SpeechController.state.value.audioSample)
            SpeechController.stop(app)
            waitFor { SpeechController.state.value.bookId == null }
            SpeechController.start(app, book.id)
            waitFor { SpeechController.state.value.error != null || SpeechController.state.value.audioSample > paused.playedSamples + 5000 }
            assertNull(SpeechController.state.value.error)
            assertEquals(passage, SpeechController.state.value.passage)
            assertEquals(paused.word, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            val restored = SpeechController.state.value.audioSample
            SpeechController.settings(app, settings.copy(supertonicLanguage = "en", supertonicVoice = 9, rate = 1.25f))
            Thread.sleep(400)
            assertFalse("A paused language/voice change must stay paused", SpeechController.state.value.playing)
            assertEquals(paused.word, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.selectedVoice == "supertonic:en:9" && SpeechController.state.value.audioSample > 5000) }
            assertNull(SpeechController.state.value.error)
            assertEquals(passage, SpeechController.state.value.passage)
            // Switch during active playback while another inference may be prefetched.
            SpeechController.settings(app, settings.copy(backend = "kokoro", aiVoice = 3))
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.selectedVoice == "kokoro:3" && SpeechController.state.value.audioSample > 2400) }
            assertNull(SpeechController.state.value.error)
            assertEquals(passage, SpeechController.state.value.passage)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            SpeechController.settings(app, settings.copy(backend = "supertonic", supertonicVoice = 5))
            Thread.sleep(300)
            assertFalse(SpeechController.state.value.playing)
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error != null ||
                (SpeechController.state.value.selectedVoice == "supertonic:da:5" && SpeechController.state.value.audioSample > 5000) }
            assertNull(SpeechController.state.value.error)
            assertEquals(passage, SpeechController.state.value.passage)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing }
            SpeechController.settings(app, settings.copy(backend = "android"))
            Thread.sleep(300)
            assertFalse(SpeechController.state.value.playing)
            assertEquals(paused.word, ReaderCursorStore(app).read(book.id, "speech")!!.wordIndex)
            summary.put("pauseSample", paused.playedSamples).put("restartSample", restored)
                .put("cursorWord", paused.word).put("activeBackendSwitch", true).put("pausedLanguageSwitch", true)
        }
        File(app.filesDir, "supertonic-validation.json").writeText(summary.toString(2))
    }
    private fun metrics(clip: AiAudioClip) = JSONObject().put("samples", clip.samples.size)
        .put("sampleRate", clip.sampleRate).put("synthesisMillis", clip.synthesisMillis)
        .put("durationSeconds", clip.samples.size.toDouble() / clip.sampleRate)
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
