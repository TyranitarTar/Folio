package app.folio.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioFolder015Test {
    private fun dir(id:String,name:String)=AudioSourceEntry(id,name,"vnd.android.document/directory")
    private fun audio(id:String,name:String)=AudioSourceEntry(id,name,"audio/mp4",100,10)
    @Test fun author_series_book_and_cd_trees_produce_separate_books()=runBlocking {
        val tree=mapOf("root" to listOf(dir("author","Author")),"author" to listOf(dir("series","Series")),
            "series" to listOf(dir("a","Book A"),dir("b","Book B")),"a" to listOf(dir("cd1","CD 1"),dir("cd2","CD 2")),
            "cd1" to listOf(audio("a1","01.mp3")),"cd2" to listOf(audio("a2","02.mp3")),"b" to listOf(audio("b1","Book.m4b")))
        val books=discoverAudioBooks("root","Root"){tree[it].orEmpty()}
        assertEquals(listOf("Book A","Book B"),books.map{it.title})
        assertEquals(listOf("CD 1/01.mp3","CD 2/02.mp3"),books.first().tracks.map{it.name})
    }
    @Test fun loose_m4bs_and_bundles_are_not_merged_and_cover_is_not_a_track()=runBlocking {
        val art=AudioSourceEntry("cover","cover.jpg","image/jpeg",50,10)
        val entries=listOf(audio("a","First.m4b"),audio("b","Second.m4b"),AudioSourceEntry("c","Third.foliobook","application/zip"),art)
        val books=discoverAudioBooks("root","Root"){entries}
        assertEquals(3,books.size);assertTrue(books.all{it.tracks.size==1 && it.cover==art})
    }
    @Test fun cyclic_tree_is_rejected_instead_of_repeated_forever()=runBlocking {
        try {discoverAudioBooks("root","Root"){listOf(dir("root","Cycle"))};fail("cycle accepted")}catch(expected:IllegalArgumentException){}
    }
    @Test fun import_and_scan_agree_on_formats() {
        listOf("mp3","m4b","m4a","ogg","opus","wav","flac","aac").forEach{assertEquals(it,audioExtensionOrNull("file.$it","application/octet-stream"))}
        assertNull(audioExtensionOrNull("file.aiff","audio/aiff"));assertEquals("flac",audioExtensionOrNull(null,"audio/flac"))
    }
}
