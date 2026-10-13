package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

/** Deterministic synthesis callbacks, including a late completion after stopping an utterance. */
class SpeechCallbackGateTest {
    @Test fun pausedUtteranceCannotAdvancePositionWhenItsCallbackArrivesLate() {
        val gate = SpeechCallbackGate()
        var position = 4
        val speaking = gate.begin()
        assertTrue(gate.accepts(speaking))
        gate.invalidate()
        if (gate.complete(speaking)) position++
        assertEquals(4, position)
        val resumed = gate.begin()
        assertFalse(gate.accepts(speaking))
        assertTrue(gate.accepts(resumed))
        if (gate.complete(resumed)) position++
        assertEquals(5, position)
        assertFalse(gate.complete(resumed))
    }
    @Test fun bookSwitchOrSpeedChangeRejectsEveryOldRangeAndCompletion() {
        val gate = SpeechCallbackGate()
        val old = gate.begin()
        gate.invalidate()
        val changed = gate.begin()
        assertFalse(gate.accepts(old))
        assertFalse(gate.complete(old))
        assertTrue(gate.accepts(changed))
        val nextSentence = gate.begin()
        assertFalse(gate.accepts(changed))
        assertTrue(gate.complete(nextSentence))
        assertFalse(gate.accepts(nextSentence))
    }
}
