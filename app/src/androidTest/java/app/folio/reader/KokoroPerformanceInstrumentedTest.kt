package app.folio.reader

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uncached real FP32 inference. Only one native engine is alive at a time. */
@RunWith(AndroidJUnit4::class)
class KokoroPerformanceInstrumentedTest {
    @Test fun compareTwoAndFourCpuThreadsAfterWarmInference() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val phrase = "The rain had stopped. Emma opened the book and began to read, quietly and clearly."
        val results = JSONArray()
        for (threads in listOf(2, 4)) {
            val engine = KokoroEngine(app, cpuThreads = threads)
            try {
                val preparationStart = SystemClock.elapsedRealtime()
                engine.prepare()
                val preparationMillis = SystemClock.elapsedRealtime() - preparationStart
                val warm = engine.generate(phrase, voiceId = 21, speed = 1f)
                val measurements = JSONArray()
                repeat(3) {
                    val clip = engine.generate(phrase, voiceId = 21, speed = 1f)
                    assertEquals(24000, clip.sampleRate)
                    assertTrue(clip.samples.size > clip.sampleRate)
                    assertTrue(clip.samples.all { sample -> sample.isFinite() && sample in -1f..1f })
                    val seconds = clip.samples.size.toDouble() / clip.sampleRate
                    measurements.put(JSONObject().put("synthesisMillis", clip.synthesisMillis)
                        .put("audioSeconds", seconds).put("samples", clip.samples.size)
                        .put("realTimeFactor", clip.synthesisMillis / (seconds * 1000)))
                }
                val meanRtf = (0 until measurements.length()).map { measurements.getJSONObject(it).getDouble("realTimeFactor") }.average()
                val medianMillis = (0 until measurements.length()).map { measurements.getJSONObject(it).getLong("synthesisMillis") }.sorted()[1]
                val result = JSONObject().put("cpuThreads", threads).put("preparationMillis", preparationMillis)
                    .put("warmSynthesisMillis", warm.synthesisMillis).put("measurements", measurements)
                    .put("meanRealTimeFactor", meanRtf).put("medianSynthesisMillis", medianMillis)
                results.put(result)
                Log.i("FolioKokoroBenchmark", result.toString())
            } finally {
                // The following engine's prepare queues behind this release on LocalVoiceWorker.
                engine.close()
            }
        }
        val summary = JSONObject().put("modelVersion", KokoroEngine.VERSION).put("voiceId", 21).put("speed", 1)
            .put("phrase", phrase).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("threads", results)
        val file = AtomicFile(File(app.filesDir, "kokoro-performance.json"))
        val output = file.startWrite()
        try { output.write(summary.toString(2).toByteArray()); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
