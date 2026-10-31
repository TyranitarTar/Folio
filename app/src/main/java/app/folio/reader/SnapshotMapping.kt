package app.folio.reader

/** A durable replacement target for a saved quote; EPUB annotations are not rewritten. */
data class AudioQuoteTarget(val track:Int,val positionMs:Long)

/** Resolve used audio positions only through an unambiguous source passage in both editions. */
internal class SnapshotTrackMapping(private val old:AudioTrack, private val replacement:AudioTrack) {
    private data class Key(val href:String,val fragment:String?,val selector:String?,val text:String,val before:String,val after:String)
    private fun clean(s:String)=s.replace(Regex("\\s+")," ").trim()
    private fun key(c:AudioCue)=Key(c.href,c.fragment,c.cssSelector,clean(c.text),clean(c.before),clean(c.after))
    private val oldGroups=old.cues.groupBy(::key)
    private val newGroups=replacement.cues.groupBy(::key)
    private val ordered=old.cues.sortedBy { it.startMs }
    private val maxEnd=LongArray(ordered.size).also { ends ->
        ordered.forEachIndexed { i,cue -> ends[i]=maxOf(if(i==0) 0L else ends[i-1],cue.endMs) }
    }
    fun remap(position:Long):Long? {
        var lo=0;var hi=ordered.lastIndex;var index=-1
        while(lo<=hi) { val mid=(lo+hi).ushr(1);if(ordered[mid].startMs<=position){index=mid;lo=mid+1}else hi=mid-1 }
        var cue:AudioCue?=null
        while(index>=0 && maxEnd[index]>position) {
            val candidate=ordered[index--]
            if(position<candidate.endMs) { if(cue!=null)return null;cue=candidate }
        }
        val found=cue ?: return null
        val k=key(found)
        if(k.text.isBlank() || oldGroups[k]?.size!=1)return null
        val next=newGroups[k]?.singleOrNull() ?: return null
        return next.startMs+(position-found.startMs).coerceIn(0L,(next.endMs-next.startMs-1L).coerceAtLeast(0L))
    }
}
