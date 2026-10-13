package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class GuidedTimingTest {
    @Test fun overlappingWindowConsumesOneWordButGroupConsumesFour() {
        assertEquals(1, GuidedTiming.consumed("Window", 4, 20))
        assertEquals(4, GuidedTiming.consumed("Groups", 4, 20))
        assertEquals(2, GuidedTiming.consumed("Groups", 4, 2))
        val words = List(4) { "word" to 5 }
        assertEquals(1200.0, GuidedTiming.milliseconds("WPM", 200.0, words, false, false, false), .01)
        assertEquals(300.0, GuidedTiming.milliseconds("WPM", 200.0, words.take(1), false, false, false), .01)
    }
    @Test fun characterPacingAndOptionalPausesUseNewlyConsumedText() {
        val words = listOf("short" to 6, "encyclopaedia." to 15)
        assertEquals(2100.0, GuidedTiming.milliseconds("CPS", 10.0, words, false, false, false), .01)
        assertEquals(2300.0, GuidedTiming.milliseconds("CPS", 10.0, words, true, false, false), .01)
        assertEquals(2400.0, GuidedTiming.milliseconds("CPS", 10.0, words, true, false, true), .01)
        assertEquals(900.0, GuidedTiming.milliseconds("WPM", 200.0, words, false, true, false), .01)
    }
    @Test fun measuredPaceIncludesExtraDwellAndDoesNotInventComprehension() {
        assertEquals(150, GuidedSession(100, 40000).effectiveWpm)
        assertEquals(0, GuidedSession().effectiveWpm)
        assertEquals("Window", GuidedOptions(mode = "bad", words = 99).normalized().mode)
        assertEquals(8, GuidedOptions(words = 99).normalized().words)
    }
}
