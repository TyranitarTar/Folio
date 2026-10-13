package app.folio.reader

import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class KokoroAudioValidationTest {
    private fun rejects(samples: FloatArray) {
        try { KokoroEngine.validateAudio(samples); fail("Unsafe audio must not reach the speaker or cache") }
        catch (_: IllegalStateException) { }
    }
    @Test fun rejectsNonfiniteSilenceAndSustainedClippedOutput() {
        rejects(floatArrayOf(Float.NaN))
        rejects(floatArrayOf(Float.POSITIVE_INFINITY))
        rejects(FloatArray(1000))
        rejects(FloatArray(1000) { if (it % 2 == 0) 1f else -1f })
        rejects(FloatArray(1000) { .5f }) // A DC rail is not synthesized speech.
        rejects(FloatArray(1000) { if (it == 0) 3f else .1f })
    }
    @Test fun normalSpeechRangeRemainsIntactAndRareOvershootIsClipped() {
        val samples = FloatArray(2400) { (.25 * sin(it * .1)).toFloat() }
        val expected = samples.copyOf()
        KokoroEngine.validateAudio(samples)
        assertArrayEquals(expected, samples, 0f)
        samples[0] = 1.1f
        samples[1] = -1.1f
        KokoroEngine.validateAudio(samples)
        assertEquals(1f, samples[0], 0f)
        assertEquals(-1f, samples[1], 0f)
        assertTrue(samples.all { it in -1f..1f })
    }
}
