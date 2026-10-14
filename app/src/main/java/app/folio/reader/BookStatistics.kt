@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

internal data class StatisticsChapter(val title: String, val depth: Int, val start: Int, val end: Int, val locator: Locator) {
    val words get() = end - start
}
internal data class BookStatistics(val totalWords: Int, val sections: List<Pair<String,Int>>, val chapters: List<StatisticsChapter>) {
    fun wordOffset(text: BookText, locator: Locator): Int? {
        if(text.sections.none { it.href.removeFragment()==locator.href.removeFragment() }) {
            return chapters.firstOrNull { it.locator.href.removeFragment()==locator.href.removeFragment() }?.start
        }
        val point=text.locate(locator) ?: return null
        return (text.sections.take(point.sectionIndex).sumOf { it.words.size } + point.wordIndex).coerceIn(0,totalWords)
    }
    fun currentChapter(offset: Int, href: String): StatisticsChapter? = chapters.firstOrNull {
        it.depth==0 && it.words==0 && it.locator.href.removeFragment().toString()==href
    } ?: chapters.lastOrNull { it.depth==0 && offset>=it.start && offset<it.end }
    fun remainingMinutes(before: Int, wpm: Double): Int = ceil((totalWords-before.coerceIn(0,totalWords))/wpm.coerceAtLeast(1.0)).toInt()
    fun json()=JSONObject().put("schema",SCHEMA).put("total",totalWords)
        .put("sections",JSONArray(sections.map { JSONObject().put("href",it.first).put("words",it.second) }))
        .put("chapters",JSONArray(chapters.map { JSONObject().put("title",it.title).put("depth",it.depth).put("start",it.start).put("end",it.end).put("locator",it.locator.toJSON()) }))
    companion object {
        const val SCHEMA=1
        fun from(j: JSONObject): BookStatistics {
            require(j.getInt("schema")==SCHEMA)
            val total=j.getInt("total");require(total in 0..750000)
            val ss=j.getJSONArray("sections");require(ss.length()<=10000)
            val sections=(0 until ss.length()).map { val s=ss.getJSONObject(it);val href=s.getString("href");val words=s.getInt("words");require(href.length<=8192&&words in 0..total);href to words }
            require(sections.sumOf { it.second.toLong() }==total.toLong())
            val cs=j.getJSONArray("chapters");require(cs.length()<=10000)
            val chapters=(0 until cs.length()).map { val c=cs.getJSONObject(it);val start=c.getInt("start");val end=c.getInt("end");val depth=c.getInt("depth");val title=c.getString("title")
                require(start in 0..total&&end in start..total&&depth in 0..128&&title.length<=4096)
                StatisticsChapter(title,depth,start,end,checkNotNull(Locator.fromJSON(c.getJSONObject("locator")))) }
            val top=chapters.filter { it.depth==0&&it.words>0 }.sortedBy { it.start }
            require(top.sumOf { it.words.toLong() }==total.toLong())
            require(top.zipWithNext().all { (a,b)->a.end==b.start })
            if(total>0) require(top.first().start==0&&top.last().end==total)
            return BookStatistics(total,sections,chapters)
        }
        fun build(text: BookText, publication: Publication): BookStatistics {
            val sections=text.sections.map { it.href.toString() to it.words.size }
            val prefix=IntArray(sections.size+1);sections.indices.forEach { prefix[it+1]=prefix[it]+sections[it].second }
            val total=prefix.last();val spine=publication.readingOrder.map { it.url().removeFragment().toString() }
            fun offset(entry: ReadingChapter): Int = entry.cursor?.let { prefix[it.sectionIndex]+it.wordIndex } ?: run {
                val position=spine.indexOf(entry.locator.href.removeFragment().toString())
                sections.filter { spine.indexOf(it.first)<position }.sumOf { it.second }
            }
            val entries=text.chapterEntries.filter { spine.contains(it.locator.href.removeFragment().toString()) }
            val rootDepth=entries.minOfOrNull { it.depth } ?: 0
            val sorted=entries.sortedWith(compareBy({ spine.indexOf(it.locator.href.removeFragment().toString()) },{ offset(it) },{ it.depth }))
            val top=sorted.filter { it.depth==rootDepth }.distinctBy { it.locator.href.removeFragment() to offset(it) }
            val rows=mutableListOf<StatisticsChapter>()
            fun other(a: Int,b: Int,label: String) {
                if(b<=a)return
                val index=sections.indices.lastOrNull { prefix[it]<=a } ?: 0
                val locator=text.cursor(index,(a-prefix[index]).coerceAtMost(text.sections[index].words.lastIndex))?.locator ?: return
                rows+=StatisticsChapter(label,0,a,b,locator)
            }
            if(top.isEmpty()) other(0,total,"Other readable text") else {
                other(0,offset(top.first()),"Front matter / other text")
                top.forEachIndexed { index,entry ->
                    val a=offset(entry);val b=top.getOrNull(index+1)?.let(::offset) ?: total
                    rows+=StatisticsChapter(entry.title.take(4096),0,a,if(entry.cursor==null)a else b,entry.locator)
                    if(entry.cursor==null)other(a,b,"Other readable text")
                }
                sorted.filter { it.depth>rootDepth }.forEach { entry ->
                    val a=offset(entry);val next=sorted.dropWhile { it!==entry }.drop(1).firstOrNull { it.depth<=entry.depth }
                    val b=next?.let(::offset) ?: total
                    rows+=StatisticsChapter(entry.title.take(4096),(entry.depth-rootDepth).coerceAtMost(128),a,if(entry.cursor==null)a else b,entry.locator)
                }
            }
            publication.readingOrder.filter { link -> sections.none { it.first==link.url().removeFragment().toString() } && rows.none { it.locator.href.removeFragment()==link.url().removeFragment() } }.forEach { link ->
                publication.locatorFromLink(link)?.let { locator ->
                    val a=sections.filter { spine.indexOf(it.first)<spine.indexOf(link.url().removeFragment().toString()) }.sumOf { it.second }
                    rows+=StatisticsChapter(link.title ?: "Illustrations / other content",0,a,a,locator)
                }
            }
            return BookStatistics(total,sections,rows.sortedWith(compareBy({it.start},{spine.indexOf(it.locator.href.removeFragment().toString())},{it.depth})))
        }
    }
}

/** Compact regenerable cache: totals and TOC boundaries, never another full book text copy. */
internal object BookStatisticsStore {
    private val mutex=Mutex()
    private fun file(context: Context,id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(context.cacheDir,"book-statistics/$id-v${BookStatistics.SCHEMA}.json"))
    }
    suspend fun read(context: Context,id: String): BookStatistics?=withContext(Dispatchers.IO) { mutex.withLock {
        runCatching { file(context,id).openRead().use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=8_000_000);out.write(buffer,0,n)}
            BookStatistics.from(JSONObject(out.toString("UTF-8")))
        } }.getOrNull()
    } }
    suspend fun prepare(context: Context,id: String,publication: Publication,text: BookText): BookStatistics {
        val result=withContext(Dispatchers.Default) { BookStatistics.build(text,publication) }
        withContext(Dispatchers.IO) { mutex.withLock {
            val bytes=result.json().toString().toByteArray(Charsets.UTF_8)
            if(bytes.size<=8_000_000) runCatching {
                val atomic=file(context,id);atomic.baseFile.parentFile?.mkdirs();val out=atomic.startWrite()
                try{out.write(bytes);atomic.finishWrite(out)}catch(e:Throwable){atomic.failWrite(out);throw e}
            }
        } }
        return result
    }
}
