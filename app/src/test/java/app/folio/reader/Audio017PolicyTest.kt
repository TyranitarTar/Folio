package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class Audio017PolicyTest {
    private val one=AudioCue(8000,10000,"one.xhtml",text="One")
    private val two=AudioCue(0,2000,"two.xhtml",text="Two")
    private val book=Audiobook("id","Title","",tracks=listOf(AudioTrack("one","",10000,listOf(one)),AudioTrack("two","",10000,listOf(two))))

    @Test fun offsets_shift_display_across_tracks_without_changing_actual_position() {
        val later=book.copy(passageOffsetMs=1000)
        assertEquals(one,displayPassage(later,1,500).cue)
        assertEquals(0,displayPassage(later,1,500).track)
        assertEquals(two,displayPassage(book.copy(passageOffsetMs=-1000),0,9500).cue)
        assertNull(displayPassage(later,0,500).cue)
        assertEquals(two,displayPassage(book,1,500).cue)
        assertEquals(0,book.positionTrack);assertEquals(0L,book.positionMs)
        assertFalse(AudioSleepBoundary(1,1000,"passage").reached(1,500))
    }
    @Test fun gaps_and_unknown_previous_duration_do_not_invent_cues() {
        assertNull(displayPassage(book,0,5000).cue)
        val unknown=book.copy(tracks=listOf(book.tracks[0].copy(durationMs=0),book.tracks[1]),passageOffsetMs=1000)
        assertNull(displayPassage(unknown,1,500).cue)
    }
    @Test fun partial_audio_cannot_automatically_finish_but_partial_alignment_can() {
        val base=book.copy(editionId="folio-"+"a".repeat(64),editionIncomplete=true,completed=true)
        assertFalse(base.copy(generatedChapterCount=1,totalChapterCount=2).isRead)
        assertTrue(base.copy(generatedChapterCount=1,totalChapterCount=1).isRead)
        assertFalse(base.copy(generatedChapterCount=0,totalChapterCount=0).isRead)
        assertTrue(base.copy(generatedChapterCount=1,totalChapterCount=2,readState="Read").isRead)
    }
}
