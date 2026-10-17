package app.folio.reader

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PicoPolicyTest {
    private fun resource(name: String) = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).bufferedReader().use { it.readText() }
    @Test fun tokenizerMatchesPinnedHuggingFaceReferenceIncludingUnicodeAndWhitespace() {
        val tokenizer = PicoTokenizer(resource("pico-tokenizer.json"))
        val cases = JSONArray(resource("pico-tokenizer-cases.json"))
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val expected = item.getJSONArray("tokens")
            assertArrayEquals(item.getString("text"), IntArray(expected.length()) { expected.getInt(it) }, tokenizer.encode(item.getString("text")))
        }
        assertEquals(0, tokenizer.eos)
        assertEquals(8002, tokenizer.audioStart)
    }
    @Test fun playbackSpeedKeepsSourceWaveformAndPicoVoiceIsIndependent() {
        val pico = SpeechSettings(backend="pico", picoVoice=1)
        val faster = pico.copy(rate=3f)
        assertTrue(pico.isLocal)
        assertTrue(pico.sameSource(faster))
        assertEquals(pico.audioKey("Hej."), faster.audioKey("Hej."))
        assertEquals(1f, faster.synthesisRate)
        assertEquals(3f, faster.playbackRate)
        assertFalse(pico.sameSource(pico.copy(picoVoice=2)))
        assertNotEquals(pico.audioKey("Hej."), pico.copy(picoVoice=2).audioKey("Hej."))
        assertFalse(pico.sameSource(pico.copy(backend="supertonic")))
    }
    @Test fun trimmedPicoCannotReuseAnOldPaddedWaveformOrSampleOffset() {
        val text = "Hun læser en bog."
        val oldIdentity = "${PicoEngine.VERSION}/normalization-v1/native-ort1.28.2/sampling-t07-p095-k50-seed42/da"
        val oldKey = SpeechPcm.hex(java.security.MessageDigest.getInstance("SHA-256").digest("$oldIdentity|0|1.0|$text".toByteArray()))
        val selected = SpeechSettings(backend="pico")
        assertNotEquals(oldKey, selected.audioKey(text))
        assertEquals(selected.audioKey(text), selected.copy(rate=1.5f).audioKey(text))
    }
    @Test fun picoVoiceSurvivesSettingsRoundTripWithoutOverwritingOtherVoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = SpeechSettings(backend="pico", picoVoice=4, aiVoice=3, supertonicVoice=7, rate=2.5f)
        expected.save(context)
        assertEquals(expected, SpeechSettings.load(context))
        expected.copy(picoVoice=-1).save(context)
        assertEquals(0, SpeechSettings.load(context).picoVoice)
    }
    @Test fun danishNumbersAreSpokenWithoutChangingTheBookText() {
        assertEquals("Prisen er tolv komma fem nul kroner.", PicoEngine.normalize("Prisen er 12,50 kroner"))
        assertEquals("Der er femogtyve bøger.", PicoEngine.normalize("Der er 25 bøger."))
        assertEquals("Æbler og pærer.", PicoEngine.normalize("  Æbler\n og pærer  "))
    }
    @Test fun picoCatalogRejectsAnUnknownRuntimeAndKeepsExistingLimits() {
        val raw = ApplicationProvider.getApplicationContext<Context>().assets.open("voice-packs.json").bufferedReader().use { it.readText() }
        val packs = VoicePackCatalog.parse(raw)
        assertEquals("pico", packs.first { it.id=="pico" }.backend)
        assertThrows(IllegalArgumentException::class.java) { VoicePackCatalog.parse(raw.replace("pico-onnx-v1", "unknown-runtime")) }
    }
}
