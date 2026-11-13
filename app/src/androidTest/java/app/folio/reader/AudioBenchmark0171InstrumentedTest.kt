package app.folio.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioBenchmark0171InstrumentedTest {
    @Test fun live_trial_uses_startup_seconds_instead_of_filling_the_entire_lookahead()=runBlocking<Unit> {
        val result=withTimeout(25000) {
            LiveSpeechBenchmark.run(32,6,1.5f,startupSeconds=1) {
                delay(150)
                AiAudioClip(FloatArray(14400){n->(kotlin.math.sin(n*2*Math.PI*220/24000)*.1).toFloat()})
            }
        }
        assertEquals(32,result.getInt("lookaheadLimit"))
        assertEquals(3,result.getInt("preloadedPassages"))
        assertEquals(1,result.getInt("startupBufferSeconds"))
        assertEquals(6,result.getJSONArray("passages").length())
        assertTrue(result.getInt("generationOverlappedPlayback")>0)
        assertEquals(0,result.getInt("queueWaits"))
    }
    @Test fun cancellation_during_prebuffer_releases_audio_and_the_producer()=runBlocking<Unit> {
        var stopped=false
        val task=launch {
            try {LiveSpeechBenchmark.run(32,12,1f,startupSeconds=30) {
                delay(500);AiAudioClip(FloatArray(24000){.01f})
            }} finally {stopped=true}
        }
        delay(700);withTimeout(5000){task.cancelAndJoin()};assertTrue(stopped)
    }
}
