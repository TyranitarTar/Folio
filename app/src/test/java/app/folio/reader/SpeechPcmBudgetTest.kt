package app.folio.reader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechPcmBudgetTest {
    @Test fun waitsForBytesAndCancelDoesNotLeakReservations() = runBlocking {
        val budget = SpeechPcmBudget(16, 10.0, 8, 5.0)
        val first = Any(); val second = Any(); val third = Any()
        budget.acquire(first); budget.commit(first, 8, 5.0)
        budget.acquire(second)
        val blocked = async(start = CoroutineStart.UNDISPATCHED) { budget.acquire(third) }
        assertFalse(blocked.isCompleted); assertEquals(16L, budget.retainedBytes())
        blocked.cancel(); blocked.join()
        budget.release(second); budget.release(first)
        assertEquals(0L, budget.retainedBytes())
        budget.acquire(third); assertEquals(8L, budget.retainedBytes())
    }
    @Test fun synthesisReservationTransfersToCurrentClipWithoutDoubleCounting() = runBlocking {
        val budget = SpeechPcmBudget(16, 10.0, 8, 5.0)
        val owner = Any(); budget.acquire(owner)
        val clip = AiAudioClip(floatArrayOf(.1f, -.1f), 8000)
        budget.commit(owner, 8, .00025)
        budget.activate(clip, 0, owner)
        assertEquals(8L, budget.retainedBytes())
        budget.updateActive(clip, 1)
        assertEquals("Paused PCM still retains its full sample array", 8L, budget.retainedBytes())
        budget.clearActive(); assertEquals(0L, budget.retainedBytes())
    }
}
