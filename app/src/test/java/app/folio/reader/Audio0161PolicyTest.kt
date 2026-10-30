package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class Audio0161PolicyTest {
    @Test fun passage_survives_gaps_completion_and_track_changes_but_never_displays_a_future_cue() {
        val retained=SpokenPassage()
        val first=AudioCue(100,200,"one.xhtml",text="Spoken")
        val future=AudioCue(500,600,"two.xhtml",text="Upcoming")
        assertNull(retained.update("a",0,first,0))
        assertEquals(first,retained.update("a",0,first,100))
        assertEquals(first,retained.update("a",0,null,400))
        assertEquals(first,retained.update("a",1,future,0))
        assertEquals(0,retained.track)
        assertEquals(future,retained.update("a",1,future,500))
        assertEquals(1,retained.track)
        assertEquals(future,retained.update("a",1,null,600))
        assertNull(retained.update("b",0,null,0))
    }
    @Test fun awake_respects_device_timeout_background_and_interaction_deadline() {
        val timed=ReaderAwakePolicy("timed",10)
        assertTrue(audioPlayerAwake(timed,true,true,true,900_000,0))
        assertFalse(audioPlayerAwake(timed,true,true,false,600_000,0))
        assertTrue(audioPlayerAwake(timed,true,true,false,600_000,1))
        assertFalse(audioPlayerAwake(timed,false,true,true,10,0))
        assertFalse(audioPlayerAwake(timed,true,false,true,10,0))
        assertFalse(audioPlayerAwake(ReaderAwakePolicy("device"),true,true,true,10,0))
        assertTrue(audioPlayerAwake(ReaderAwakePolicy("always"),true,true,false,900_000,0))
    }
    @Test fun boundary_uses_absolute_track_time_for_embedded_and_file_chapters() {
        val boundary=AudioSleepBoundary(2,30_000,"Chapter")
        assertFalse(boundary.reached(1,500_000))
        assertFalse(boundary.reached(2,29_999))
        assertTrue(boundary.reached(2,30_000))
        assertTrue(boundary.reached(3,0))
    }
}
