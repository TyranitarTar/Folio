package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class SnapshotMappingTest {
    private val cue=AudioCue(1000,2000,"one.xhtml","p1","A sentence.","before","after","#p1")
    private fun track(cues:List<AudioCue>)=AudioTrack("track.wav","",10000,cues)
    @Test fun shifts_used_position_within_unique_passage_and_bounds_it() {
        val map=SnapshotTrackMapping(track(listOf(cue)),track(listOf(cue.copy(startMs=5000,endMs=5300))))
        assertEquals(5100L,map.remap(1100));assertEquals(5299L,map.remap(1800))
        assertNull(map.remap(2500))
    }
    @Test fun refuses_missing_or_ambiguous_source_passages() {
        val duplicate=cue.copy(startMs=3000,endMs=4000)
        assertNull(SnapshotTrackMapping(track(listOf(cue,duplicate)),track(listOf(cue))).remap(1200))
        assertNull(SnapshotTrackMapping(track(listOf(cue)),track(listOf(cue,duplicate))).remap(1200))
        assertNull(SnapshotTrackMapping(track(listOf(cue)),track(emptyList())).remap(1200))
    }
}
