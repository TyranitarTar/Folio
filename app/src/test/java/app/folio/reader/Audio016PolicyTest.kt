package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class Audio016PolicyTest {
    @Test fun remaining_time_accounts_for_speed_and_unknown_duration() {
        assertEquals(600_000L,remainingListeningMs(1_200_000,600_000,1f))
        assertEquals(300_000L,remainingListeningMs(1_200_000,600_000,2f))
        assertEquals(200_000L,remainingListeningMs(1_200_000,600_000,3f))
        assertEquals(0L,remainingListeningMs(1200,2000,2f));assertNull(remainingListeningMs(0,0,1f))
        assertEquals(1000L,remainingListeningMs(1000,0,Float.NaN))
    }
    @Test fun temporary_interruption_resumes_only_the_same_active_edition_once() {
        val gate=AudioInterruptionGate()
        gate.interrupt(4,true,true);assertTrue(gate.regain(4));assertFalse(gate.regain(4))
        gate.interrupt(4,true,true);assertFalse(gate.regain(5))
        gate.interrupt(4,false,true);assertFalse(gate.regain(4))
        gate.interrupt(4,true,false);assertFalse(gate.regain(4))
    }
    @Test fun manual_pause_stop_and_disconnect_cancel_pending_automatic_resume() {
        val gate=AudioInterruptionGate()
        repeat(3){gate.interrupt(1,true,true);gate.cancel();assertFalse(gate.regain(1))}
    }
}
