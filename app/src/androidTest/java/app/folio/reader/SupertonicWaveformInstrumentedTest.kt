package app.folio.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reference exports isolate native synthesis from AudioTrack. End-word quality needs listening. */
@RunWith(AndroidJUnit4::class)
class SupertonicWaveformInstrumentedTest {
    @Test fun exportNormalAndNativeFastFinalWordsForEnglishAndDanishComparison() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val engine=SupertonicEngine(app)
        val rows=JSONArray()
        try {
            val phrases=listOf("en" to "She put down the book. Remember these final words: quiet, last, stop, and good night.",
                "da" to "Hun lægger bogen fra sig. Husk de sidste ord: stille, sidst, stop og godnat.")
            for((language,text) in phrases) for(rate in listOf(1f,1.5f)) {
                val clip=runBlocking { engine.generate(text,0,rate,language) }
                assertTrue(clip.samples.size>clip.sampleRate); assertEquals(SpeechPcm.digest(clip.samples,clip.sampleRate),clip.digest)
                val name="supertonic-reference-$language-native-$rate.wav"
                wav(File(app.filesDir,name),clip)
                val tail=clip.samples.takeLast(minOf(clip.samples.size,clip.sampleRate/5))
                val energy=tail.sumOf { it.toDouble()*it } / tail.size
                rows.put(JSONObject().put("file",name).put("language",language).put("nativeRate",rate.toDouble())
                    .put("text",text).put("samples",clip.samples.size).put("sampleRate",clip.sampleRate)
                    .put("durationSeconds",clip.samples.size.toDouble()/clip.sampleRate).put("tailMeanSquare",energy)
                    .put("synthesisMillis",clip.synthesisMillis).put("digest",clip.digest))
            }
        } finally { engine.close() }
        File(app.filesDir,"supertonic-waveform-comparison.json").writeText(JSONObject().put("referenceOnly",true)
            .put("acousticEndingsVerified",false).put("clips",rows).toString(2))
    }
    private fun wav(file:File,clip:AiAudioClip) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun short(value:Int) { out.writeByte(value and 255); out.writeByte((value ushr 8) and 255) }
            fun int(value:Int) { short(value and 65535); short(value ushr 16) }
            out.writeBytes("RIFF"); int(36+clip.samples.size*2); out.writeBytes("WAVEfmt "); int(16)
            short(1); short(1); int(clip.sampleRate); int(clip.sampleRate*2); short(2); short(16)
            out.writeBytes("data"); int(clip.samples.size*2)
            for(sample in clip.samples) short((sample.coerceIn(-1f,1f)*32767).toInt())
        }
    }
}
