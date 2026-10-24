package app.folio.reader

import org.readium.r2.shared.publication.Locator
import java.security.MessageDigest

internal fun beforeBoundaryKey(locator:Locator):String = MessageDigest.getInstance("SHA-256")
    .digest((pageBoundaryKey(locator)+":before").toByteArray()).joinToString("") { "%02x".format(it) }

/** Uses the same layout counts and measured DOM boundaries; never estimates from words. */
internal object ChapterPagination {
    fun label(info:PageInformation,starts:List<Locator>,href:String,localPage:Int):String {
        data class Point(val locator:Locator,val index:Int,val page:Int?)
        val index=info.hrefs.indexOf(href)
        if(index !in info.counts.indices)return "Chapter: calculating…"
        fun point(locator:Locator):Point {
            val i=info.hrefs.indexOf(locator.href.removeFragment().toString())
            val local=info.boundaries[pageBoundaryKey(locator)] ?: if(locator.locations.fragments.isEmpty() && locator.locations["cssSelector"]==null && locator.text.highlight.isNullOrBlank())0 else null
            return Point(locator,i,local)
        }
        val points=starts.map(::point).filter { it.index>=0 }
        if(points.any { it.index==index && it.page==null })return "Chapter: locating boundaries…"
        val current=points.indexOfLast { it.index<index || it.index==index && it.page!=null &&
            (it.page<localPage || it.page==localPage && info.boundaries[beforeBoundaryKey(it.locator)]!=localPage) }
        if(current<0)return "Front matter · ${localPage+1} of ${info.counts[index].takeIf { it>0 } ?: "…"}"
        val start=points[current];val first=start.page ?: return "Chapter: calculating…"
        val next=points.getOrNull(current+1)
        var endIndex=next?.index ?: info.counts.lastIndex
        var end=if(next==null) info.counts.getOrNull(endIndex)?.minus(1) else if(next.page==0) -1 else info.boundaries[beforeBoundaryKey(next.locator)]
        if(end==-1){endIndex--;end=info.counts.getOrNull(endIndex)?.minus(1)}
        if(end==null || start.index>endIndex || info.counts.subList(start.index,endIndex+1).any { it<=0 })return "Chapter: calculating…"
        val total=info.counts.subList(start.index,endIndex).sumOf { it.toLong() }+end-first+1
        val page=info.counts.subList(start.index,index).sumOf { it.toLong() }+localPage-first+1
        if(total<=0 || page !in 1..total)return "Chapter: locating boundaries…"
        return "Chapter: ${if(info.scrolling) "screen" else "page"} $page of $total"
    }
}
