package app.folio.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AudioChapters015Test {
    private fun fixture(name:String):ByteArray {
        val relative="src/androidTest/assets/$name"
        return (File(relative).takeIf{it.isFile} ?: File("app/$relative")).readBytes()
    }
    private fun parse(data:ByteArray)=Mp4ChapterReader(data.size.toLong()){at,count->data.copyOfRange(at.toInt(),at.toInt()+count)}.read()
    @Test fun real_m4b_and_quicktime_only_chapters_have_exact_file_offsets() {
        listOf("chapters-015.m4b","chapters-015-quicktime.m4b").forEach { name ->
            val chapters=parse(fixture(name))
            assertEquals(name,listOf("Opening","Kapitel to","Ending"),chapters.map{it.title})
            assertEquals(name,listOf(0L,6000L,12000L),chapters.map{it.startMs})
        }
    }
    private fun binary(write:DataOutputStream.()->Unit)=ByteArrayOutputStream().also{DataOutputStream(it).use(write)}.toByteArray()
    private fun box(type:String,payload:ByteArray)=binary{writeInt(payload.size+8);writeBytes(type);write(payload)}
    @Test fun nero_metadata_uses_100ns_ticks_and_does_not_read_audio_payload() {
        val chapter=box("chpl",binary{writeInt(0x01000000);writeInt(0);writeByte(2);writeLong(0);writeByte(3);writeBytes("One");writeLong(25_000_000);writeByte(3);writeBytes("Two")})
        val file=box("mdat",ByteArray(1_000_000))+box("moov",box("udta",chapter))
        var read=0
        val result=Mp4ChapterReader(file.size.toLong()){at,count->read+=count;file.copyOfRange(at.toInt(),at.toInt()+count)}.read()
        assertEquals(listOf(0L,2500L),result.map{it.startMs});assertTrue(read<1024)
    }
    @Test fun malformed_sizes_counts_and_offsets_are_bounded_and_do_not_throw() {
        val base=fixture("chapters-015-quicktime.m4b")
        val random=java.util.Random(15)
        repeat(100){val input=base.copyOf();repeat(5){input[random.nextInt(input.size)]=random.nextInt().toByte()};parse(input)}
        assertTrue(parse(byteArrayOf(127,127,127,127,109,111,111,118)).isEmpty())
        assertTrue(parse(box("moov",box("udta",box("chpl",byteArrayOf(1,0,0,0))))).isEmpty())
    }
    @Test fun internal_chapters_do_not_split_tracks_and_whole_book_seek_crosses_files() {
        val book=Audiobook("a","Book","",tracks=listOf(AudioTrack("one.m4b","One",18000,chapters=listOf(AudioChapter("First",0),AudioChapter("Second",6000))),AudioTrack("two.mp3","Two",10000)))
        val chapters=book.listeningChapters()
        assertEquals(2,book.tracks.size);assertEquals(3,chapters.size)
        assertEquals(1,chapters.currentChapter(0,7000));assertEquals(2,chapters.currentChapter(1,0))
        assertEquals(AudioPosition(1,1000),book.positionAt(19000));assertEquals(AudioPosition(1,10000),book.positionAt(99999))
        assertEquals(12000L,chapters[1].durationMs)
    }
}
