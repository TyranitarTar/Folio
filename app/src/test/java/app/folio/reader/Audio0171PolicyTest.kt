package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class Audio0171PolicyTest {
    @Test fun delayed_observations_cannot_replace_a_newer_cursor_but_deliberate_backward_seeks_can() {
        val gate=AudioCursorGate()
        val old=AudioCursorOrder.next();val newer=AudioCursorOrder.next()
        assertTrue(gate.accepts("book",newer));gate.committed("book",newer)
        assertFalse(gate.accepts("book",old))
        val backwardSeek=AudioCursorOrder.next()
        assertTrue(gate.accepts("book",backwardSeek));gate.committed("book",backwardSeek)
        assertTrue(gate.accepts("other",old))
    }
    @Test fun sessions_count_playing_time_and_speed_changes_but_exclude_pause_seek_and_buffering() {
        val clock=AudioSessionClock()
        assertNull(clock.session)
        clock.playing(0,1000,1f,100000,10)
        assertEquals(500L,clock.playing(0,1500,1f,100500,510).listenedMs)
        clock.suspendClock()
        assertEquals(500L,clock.playing(1,30000,2f,400000,90000).listenedMs)
        val saved=clock.playing(1,32000,2f,401000,91000)
        assertEquals(1500L,saved.listenedMs);assertEquals(listOf(1f,2f),saved.speeds.map{it.speed})
        assertEquals(0,saved.startTrack);assertEquals(1,saved.endTrack)
        val closed=clock.close()!!;assertTrue(closed.closed);assertNull(clock.session)
        val next=clock.playing(1,32000,2f,500000,100000)
        assertNotEquals(closed.id,next.id);assertEquals(0L,next.listenedMs)
    }
    @Test fun a_stalled_or_suspended_ticker_cannot_count_minutes_as_confirmed_listening() {
        val clock=AudioSessionClock()
        clock.playing(0,0,1f,10000,1000)
        assertEquals(2500L,clock.playing(0,300000,1f,310000,301000).listenedMs)
    }
}
