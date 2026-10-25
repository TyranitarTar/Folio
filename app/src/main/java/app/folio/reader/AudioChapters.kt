package app.folio.reader

data class AudioChapter(val title: String, val startMs: Long)
internal data class ListeningChapter(val title: String,val track: Int,val startMs: Long,val endMs: Long) {
    val durationMs get()=(endMs-startMs).coerceAtLeast(0)
}
internal fun Audiobook.listeningChapters(): List<ListeningChapter> = tracks.flatMapIndexed { index,track ->
    val starts=track.chapters.filter{it.startMs>=0 && (track.durationMs<=0 || it.startMs<track.durationMs)}.distinctBy{it.startMs}.sortedBy{it.startMs}
    val chapters=if(starts.isEmpty())listOf(AudioChapter(track.title,0)) else
        if(starts.first().startMs>0)listOf(AudioChapter("Opening",0))+starts else starts
    chapters.mapIndexed { i,chapter -> ListeningChapter(chapter.title.ifBlank{"Chapter ${i+1}"},index,chapter.startMs,
        chapters.getOrNull(i+1)?.startMs ?: track.durationMs) }
}
internal fun List<ListeningChapter>.currentChapter(track: Int,position: Long): Int {
    var lo=0;var hi=lastIndex;var answer=0
    while(lo<=hi){val middle=(lo+hi).ushr(1);val chapter=this[middle]
        if(chapter.track<track || (chapter.track==track && chapter.startMs<=position)){answer=middle;lo=middle+1}else hi=middle-1
    }
    return answer
}
internal data class AudioPosition(val track: Int,val positionMs: Long)
internal fun Audiobook.positionAt(elapsed: Long): AudioPosition {
    var remaining=elapsed.coerceIn(0,totalMs)
    tracks.forEachIndexed { i,track -> if(remaining<track.durationMs || i==tracks.lastIndex)return AudioPosition(i,remaining.coerceAtMost(track.durationMs));remaining-=track.durationMs }
    return AudioPosition(0,0)
}

/** Bounded random-access metadata reader. Never walks/loads the audio payload.
 * Supports Nero chapter lists and referenced QuickTime chapter text tracks. */
internal class Mp4ChapterReader(private val length: Long,private val readAt: (Long,Int)->ByteArray) {
    private data class Box(val type: String,val start: Long,val end: Long)
    private var bytesRead=0L
    private var boxCount=0
    private fun bytes(at: Long,count: Int): ByteArray {
        require(at>=0 && count>=0 && at<=length-count && count<=1_000_000)
        bytesRead+=count;require(bytesRead<=8_000_000)
        return readAt(at,count).also{require(it.size==count)}
    }
    private fun u32(at:Long)=bytes(at,4).fold(0L){n,b->(n shl 8) or (b.toLong() and 255)}
    private fun u64(at:Long)=bytes(at,8).fold(0L){n,b->(n shl 8) or (b.toLong() and 255)}.also{require(it>=0)}
    private fun boxes(start:Long,end:Long): List<Box> {
        val result=mutableListOf<Box>();var at=start
        while(at<=end-8) {
            require(++boxCount<=30_000)
            val header=bytes(at,8);val small=header.take(4).fold(0L){n,b->(n shl 8) or (b.toLong() and 255)}
            val width=if(small==1L)16L else 8L
            val size=when(small){0L->end-at;1L->u64(at+8);else->small}
            require(size>=width && size<=end-at)
            result+=Box(String(header,4,4,Charsets.ISO_8859_1),at+width,at+size)
            at+=size
        }
        return result
    }
    private fun Box.children()=boxes(start,end)
    private fun Box.child(type:String)=children().firstOrNull{it.type==type}
    private fun Box.count(offset:Long=4): Int=u32(start+offset).also{require(it<=10_000)}.toInt()
    fun read(): List<AudioChapter> = runCatching {
        val movie=boxes(0,length).firstOrNull{it.type=="moov"} ?: return emptyList()
        val children=movie.children()
        val nero=children.firstOrNull{it.type=="udta"}?.child("chpl")?.let{runCatching{nero(it)}.getOrDefault(emptyList())}.orEmpty()
        val tracks=children.filter{it.type=="trak"}
        val references=tracks.flatMap { track -> track.child("tref")?.child("chap")?.let { chap ->
            require((chap.end-chap.start)%4==0L && chap.end-chap.start<=4096)
            (0 until ((chap.end-chap.start)/4).toInt()).map{u32(chap.start+it*4L)}
        }.orEmpty() }.toSet()
        val chapterTrack=tracks.firstOrNull { track -> track.child("tkhd")?.let{tkhd ->
            val v=bytes(tkhd.start,1)[0].toInt();u32(tkhd.start+if(v==1)20 else 12) in references
        }==true }
        val quicktime=chapterTrack?.let{runCatching{quicktime(it)}.getOrDefault(emptyList())}.orEmpty()
        (quicktime.ifEmpty{nero}).filter{it.startMs in 0..604_800_000L}.distinctBy{it.startMs}.sortedBy{it.startMs}
    }.getOrDefault(emptyList())
    private fun nero(box:Box): List<AudioChapter> {
        var at=box.start
        val version=bytes(at,1)[0].toInt();at+=4
        if(version!=0)at+=4
        val count=bytes(at++,1)[0].toInt() and 255
        return List(count){index ->
            require(at<=box.end-9)
            val time=u64(at)/10_000;at+=8
            val size=bytes(at++,1)[0].toInt() and 255;require(at<=box.end-size)
            val title=clean(bytes(at,size));at+=size
            AudioChapter(title.ifBlank{"Chapter ${index+1}"},time)
        }
    }
    private fun quicktime(track:Box): List<AudioChapter> {
        val media=requireNotNull(track.child("mdia"));val header=requireNotNull(media.child("mdhd"))
        val version=bytes(header.start,1)[0].toInt();val scale=u32(header.start+if(version==1)20 else 12);require(scale>0)
        val table=requireNotNull(media.child("minf")?.child("stbl"))
        val sizesBox=requireNotNull(table.child("stsz"));val fixed=u32(sizesBox.start+4)
        val count=sizesBox.count(8);require(count>0)
        require(fixed>0 || sizesBox.end-sizesBox.start>=12+count*4L)
        val sizes=LongArray(count){if(fixed>0)fixed else u32(sizesBox.start+12+it*4L)}
        val timeBox=requireNotNull(table.child("stts"));val timeCount=timeBox.count()
        require(timeBox.end-timeBox.start>=8+timeCount*8L)
        val times=LongArray(count);var sample=0;var time=0L
        repeat(timeCount){i ->
            val runs=u32(timeBox.start+8+i*8L);val delta=u32(timeBox.start+12+i*8L)
            require(runs<=count-sample && delta<=scale*604_800)
            repeat(runs.toInt()){times[sample++]=time;time+=delta;require(time<=scale*604_800)}
        };require(sample==count)
        val chunkMap=requireNotNull(table.child("stsc"));val mapCount=chunkMap.count();require(mapCount>0)
        require(chunkMap.end-chunkMap.start>=8+mapCount*12L)
        val map=List(mapCount){i->u32(chunkMap.start+8+i*12L) to u32(chunkMap.start+12+i*12L)}
        require(map.first().first==1L && map.all{it.second in 1..10_000} && map.zipWithNext().all{it.first.first<it.second.first})
        val chunkBox=table.child("stco") ?: requireNotNull(table.child("co64"));val chunks=chunkBox.count();val width=if(chunkBox.type=="co64")8 else 4
        require(chunkBox.end-chunkBox.start>=8+chunks*width.toLong())
        val result=mutableListOf<AudioChapter>();sample=0;var mapIndex=0
        repeat(chunks){chunk ->
            while(mapIndex+1<map.size && map[mapIndex+1].first<=chunk+1)mapIndex++
            var offset=if(width==8)u64(chunkBox.start+8+chunk*8L) else u32(chunkBox.start+8+chunk*4L)
            repeat(map[mapIndex].second.toInt()) {
                require(sample<count && sizes[sample] in 2..65537 && offset<=length-sizes[sample])
                val prefix=bytes(offset,2);val titleSize=((prefix[0].toInt() and 255) shl 8) or (prefix[1].toInt() and 255)
                require(titleSize<=sizes[sample]-2)
                result+=AudioChapter(clean(bytes(offset+2,titleSize)).ifBlank{"Chapter ${sample+1}"},times[sample]*1000/scale)
                offset+=sizes[sample++]
            }
        };require(sample==count)
        return result
    }
    private fun clean(data:ByteArray): String {
        val encoding=if(data.size>=2 && ((data[0]==0xfe.toByte() && data[1]==0xff.toByte()) || (data[0]==0xff.toByte() && data[1]==0xfe.toByte())))Charsets.UTF_16 else Charsets.UTF_8
        return String(data,encoding).filter{it>=' ' && it!='\u007f'}.trim().take(500)
    }
}
