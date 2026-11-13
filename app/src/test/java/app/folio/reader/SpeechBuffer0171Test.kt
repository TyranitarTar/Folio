package app.folio.reader

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SpeechBuffer0171Test {
    @Test fun benchmark_chunks_use_production_boundaries_and_retain_all_authored_words() {
        val baseline=SpeechBufferConfiguration.prompts(SpeechSettings(backend="pico"))
        assertTrue(baseline.distinct().size>3)
        for(backend in listOf("pico","plapre3"))for(target in listOf(10,20,30,35)) {
            val chunks=SpeechBufferConfiguration.prompts(SpeechSettings(backend=backend,passageTargetWords=target))
            assertEquals(baseline.joinToString(" "),chunks.joinToString(" "))
            assertTrue(chunks.all{it.length<=350})
            assertTrue(chunks.size<=baseline.size)
        }
        assertEquals("32",SpeechBufferConfiguration.lookahead.last())
        assertTrue("0" in SpeechBufferConfiguration.startup)
        assertTrue("2.5" in SpeechBufferConfiguration.speeds)
    }
}
