@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.navigator.preferences.Theme

/** Exact resource counts for one typography/viewport key. Cache display precedes renderer work. */
class PageCounter(private val context: Context, private val id: String, private val publication: Publication,
    private val visible: EpubNavigatorFragment, private val measuring: suspend () -> EpubNavigatorFragment, private val scope: CoroutineScope) {
    val label = MutableStateFlow("Calculating pages…")
    val counting = MutableStateFlow(true)
    internal val information = MutableStateFlow(PageInformation())
    private var boundaryRequests: List<Locator> = emptyList()
    private val boundaryPages = linkedMapOf<String,Int>()
    private var boundaryCache: AtomicFile? = null
    internal fun requestBoundaries(locators: List<Locator>) {
        boundaryRequests=(chapterStarts+locators).distinctBy(::pageBoundaryKey).take(5000)
        // Reuse the already visible renderer; never restart a whole-book count/create a renderer.
        if(currentKey.isNotEmpty() && !fixed) verifyVisible(force=true)
    }
    internal val diagnostics = MutableStateFlow(PaginationDiagnostics())
    internal val measurements = MutableStateFlow<Map<String,PaginationMeasurement>>(emptyMap())
    private val retainEvidence=context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val hrefs = publication.readingOrder.map { it.url().removeFragment().toString() }
    private val chapterStarts=publication.tableOfContents.let { links ->
        if(links.size==1 && links[0].children.isNotEmpty()) links[0].children else links
    }.ifEmpty { publication.readingOrder }.mapNotNull { publication.locatorFromLink(it) }.distinctBy(::pageBoundaryKey)
    private val fixed = publication.metadata.presentation.layout == org.readium.r2.shared.publication.epub.EpubLayout.FIXED
    private var counts = IntArray(hrefs.size)
    private val layouts = object : LinkedHashMap<String, IntArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 8
    }
    private var job: Job? = null
    private var visibleJob: Job? = null
    private var persistJob: Job? = null
    private var localPage = 0
    private var localHref = visible.currentLocator.value.href.removeFragment().toString()
    private var scroll = false
    private var currentKey = ""
    private var generation = 0L
    private var pageGeneration = 0L
    private var pageRevision = 0L
    private var activeVisible: String? = null
    private var verifiedVisible: String? = null
    private var foreground = true
    private var closed = false
    private var cache: AtomicFile? = null
    private var dirty = false
    private var changedAt = 0L
    private val viewport = PaginationViewport()
    init { boundaryRequests=chapterStarts }

    internal fun countedPages(): IntArray = counts.copyOf()
    private fun workAllowed(): Boolean = foreground && !SpeechController.state.value.playing && !SpeechController.state.value.preparing
    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) { visibleJob?.cancel(); flush() }
        else if (!closed) pageLoaded()
    }
    /** Readium's PaginationListener.onPageLoaded(), called after content-ready and pending locator. */
    fun pageLoaded() { if (!closed && currentKey.isNotEmpty() && !fixed) verifyVisible(force=true) }
    fun pageChanged(index: Int, locator: Locator, total: Int = 0) {
        if (closed) return
        localPage = index.coerceAtLeast(0); localHref = locator.href.removeFragment().toString()
        pageRevision++
        // A callback may belong to the old preference/viewport render. Verify geometry before
        // saving its total under a newly selected key; existing cached totals remain immediate.
        if (fixed && counts.isNotEmpty()) counts.fill(1)
        update()
        if (currentKey.isNotEmpty() && !fixed) verifyVisible()
    }
    private fun update() {
        information.value=PageInformation(label.value,hrefs,counts.toList(),boundaryPages.toMap(),scroll)
        val index = hrefs.indexOf(localHref)
        if (index < 0) return
        val bookLabel=PaginationCounts.label(counts, index, localPage, scroll)
        val chapter=ChapterPagination.label(information.value,chapterStarts,localHref,localPage)
        label.value = "Book: $bookLabel\n$chapter"
        information.value=information.value.copy(label=label.value)
    }
    fun recalculate(key: String, scrolling: Boolean) {
        if (closed || key == currentKey) return
        flush()
        if (currentKey.isNotEmpty()) layouts[currentKey] = counts.copyOf()
        currentKey = key; scroll = scrolling
        val ticket = ++generation; pageGeneration++
        job?.cancel(); visibleJob?.cancel()
        changedAt = SystemClock.elapsedRealtime()
        val view=visible.publicationView
        if(view.isLaidOut && !view.isLayoutRequested) viewport.reset(view.width,view.height) else viewport.reset()
        counts = layouts[key]?.copyOf() ?: IntArray(hrefs.size)
        if (fixed) counts.fill(1)
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val atomic = AtomicFile(File(File(context.cacheDir, "pagination"), "$id-v3-$hash.json"))
        cache = atomic; dirty = false
        boundaryPages.clear()
        boundaryCache=AtomicFile(File(File(context.cacheDir,"pagination"),"$id-v3-$hash-boundaries.json"))
        diagnostics.value = PaginationDiagnostics(memoryHit = counts.all { it > 0 })
        measurements.value=emptyMap()
        counting.value = counts.any { it == 0 }
        update()
        job = scope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                // No reflow debounce on cache IO/display. Do not overwrite newer live results.
                val saved = withContext(Dispatchers.IO) { PaginationCache.read(atomic, hrefs) }
                val savedBoundaries=withContext(Dispatchers.IO) { PaginationBoundaryCache.read(checkNotNull(boundaryCache)) }
                ensureActive(); if (ticket != generation) return@launch
                counts = PaginationCounts.merge(saved, counts)
                savedBoundaries.forEach { (k,v) -> if(k !in boundaryPages)boundaryPages[k]=v }
                if (fixed) counts.fill(1)
                layouts[key] = counts.copyOf()
                diagnostics.value = diagnostics.value.copy(cacheReadMillis = SystemClock.elapsedRealtime() - start)
                counting.value = counts.any { it == 0 }; update()
                if (fixed) { dirty = true; flush(); return@launch }
                verifyVisible()
                // Coalesce viewport/chrome animation keys before creating or moving a renderer.
                delay((120 - (SystemClock.elapsedRealtime() - changedAt)).coerceAtLeast(0))
                val current = hrefs.indexOf(localHref).takeIf { it >= 0 }
                if (current != null && counts[current] == 0) visibleJob?.join()
                var hidden: EpubNavigatorFragment? = null
                val order = (listOfNotNull(current) + counts.indices).distinct()
                for (index in order) {
                    ensureActive(); if (ticket != generation) return@launch
                    if (counts[index] > 0) continue
                    while (!workAllowed()) { delay(120); ensureActive() }
                    // Navigation can move while measuring. The generation/viewport/settings and
                    // resource checks in sample() prevent stale geometry entering this layout.
                    val renderer = hidden ?: measuring().also { hidden = it }
                    val link = publication.readingOrder[index]
                    try {
                        withTimeout(2500) { while (!renderer.go(link, false)) delay(40) }
                        val began = SystemClock.elapsedRealtime()
                        val measured = sample(renderer, hrefs[index], ticket)
                        ensureActive(); if (ticket != generation) return@launch
                        retainMeasurement(hrefs[index],renderer,"hidden",measured)
                        record(index, measured.pages)
                        captureBoundaries(renderer,hrefs[index],ticket,measured.pages)
                        diagnostics.value = diagnostics.value.copy(hiddenResources = diagnostics.value.hiddenResources + 1,
                            hiddenMeasureMillis = diagnostics.value.hiddenMeasureMillis + SystemClock.elapsedRealtime() - began)
                    } catch (e: TimeoutCancellationException) {
                        // A slow image/font/resource stays unknown; never fabricate a whole-book total.
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Other exact chapters remain useful. */ }
                }
                flush(); update()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (ticket == generation) { update(); if (counts.none { it > 0 }) label.value = "Page count unavailable" } }
            finally { if (ticket == generation) counting.value = false }
        }
    }
    private fun verifyVisible(force: Boolean=false) {
        if (!foreground || currentKey.isEmpty()) return
        val stamp="$generation:$localHref:${visible.publicationView.width}x${visible.publicationView.height}:${visible.settings.value}"
        if((visibleJob?.isActive==true && activeVisible==stamp) || (!force && verifiedVisible==stamp)) {
            diagnostics.value=diagnostics.value.copy(visibleReuses=diagnostics.value.visibleReuses+1)
            return
        }
        val ticket = generation; val request = ++pageGeneration
        val revision=pageRevision
        val expected = localHref
        visibleJob?.cancel()
        activeVisible=stamp
        diagnostics.value=diagnostics.value.copy(visibleVerifications=diagnostics.value.visibleVerifications+1)
        visibleJob = scope.launch {
            try {
                while (!workAllowed()) { delay(120); ensureActive() }
                val began = SystemClock.elapsedRealtime()
                val measured = sample(visible, expected, ticket)
                if (ticket != generation || request != pageGeneration || localHref != expected) return@launch
                val index = hrefs.indexOf(expected)
                if (index < 0) return@launch
                if(revision==pageRevision)localPage = measured.page
                verifiedVisible=stamp
                retainMeasurement(expected,visible,"visible",measured)
                record(index, measured.pages)
                captureBoundaries(visible,expected,ticket,measured.pages)
                diagnostics.value = diagnostics.value.copy(visibleMeasureMillis = SystemClock.elapsedRealtime() - began)
            } catch (e: TimeoutCancellationException) { /* Next callback can retry. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Cache/other chapter results are independent. */ }
        }
    }
    private suspend fun sample(renderer: EpubNavigatorFragment, expected: String, ticket: Long): PaginationSample {
        fun typography(renderer: EpubNavigatorFragment) = renderer.settings.value.copy(theme=Theme.LIGHT, backgroundColor=null, textColor=null, imageFilter=null)
        val settings = typography(visible)
        val settled = PaginationSettler()
        var activeMillis = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (ticket != generation || closed) throw CancellationException("Pagination layout replaced")
            if (!workAllowed()) { settled.reset(); delay(120); continue }
            val view=visible.publicationView
            when(viewport.inspect(view.width,view.height,view.isLaidOut,view.isLayoutRequested)) {
                PaginationViewport.Status.WAIT -> {
                    settled.reset(); activeMillis+=25
                    if(activeMillis>=8000) throw IllegalStateException("Reader viewport did not become ready")
                    delay(25); continue
                }
                PaginationViewport.Status.STALE -> throw CancellationException("Pagination viewport changed")
                PaginationViewport.Status.MATCH -> Unit
            }
            if (typography(visible) != settings) throw CancellationException("Pagination typography changed")
            val probeStarted = SystemClock.elapsedRealtime()
            val data = withTimeout((8000-activeMillis).coerceAtLeast(1)) {
                renderer.evaluateJavascript(PaginationSample.script(expected, scroll, retainEvidence))
            }
            activeMillis += SystemClock.elapsedRealtime()-probeStarted
            if (ticket != generation || closed) throw CancellationException("Pagination layout replaced")
            if (!workAllowed()) { settled.reset(); continue }
            val value = PaginationSample.decode(data)
            val viewportMatches = renderer.publicationView.width == viewport.width && renderer.publicationView.height == viewport.height
            if (value != null && viewportMatches && typography(renderer) == settings && settled.accept(value, SystemClock.elapsedRealtime())) return value
            if (value == null || !viewportMatches || typography(renderer) != settings) settled.reset()
            val polling = if (value?.ready == true) 25L else 80L
            activeMillis += polling
            if (activeMillis >= 8000) throw IllegalStateException("Page geometry did not become ready")
            delay(polling)
        }
    }
    private suspend fun captureBoundaries(renderer: EpubNavigatorFragment,href: String,ticket: Long,pages: Int) {
        val requests=boundaryRequests.filter { it.href.removeFragment().toString()==href &&
            (it.locations.fragments.isNotEmpty() || it.locations["cssSelector"]!=null || !it.text.highlight.isNullOrBlank()) &&
            (pageBoundaryKey(it) !in boundaryPages || beforeBoundaryKey(it) !in boundaryPages) }.take(128)
        if(requests.isEmpty())return
        val group="folio-statistics-boundaries"
        val script="""(function(){const result={},d=document.scrollingElement,vertical=getComputedStyle(document.documentElement).writingMode.startsWith('vertical'),span=${if(scroll) "innerHeight" else "Android.getViewportWidth()/devicePixelRatio"};if(!(span>0)||($scroll&&vertical))return result;
            const items=window.readium.getDecorations('$group').items;
            function page(rect){const offset=${if(scroll) "rect.top+d.scrollTop" else "Math.abs(rect.left+d.scrollLeft)"};return Math.max(0,Math.min($pages-1,Math.floor(offset/span+.001)));}
            for(const item of items){try{const rect=item.range.getBoundingClientRect();if(!(rect.width>0&&rect.height>0))continue;result[item.decoration.id]=page(rect);
                const anchor=item.range.cloneRange();anchor.collapse(true);const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let node=anchor.startContainer,last=null,end=anchor.startOffset;
                if(node.nodeType!==Node.TEXT_NODE){let previous=node.childNodes[end-1];if(previous){while(previous.lastChild)previous=previous.lastChild;walker.currentNode=previous;node=previous.nodeType===Node.TEXT_NODE?previous:walker.previousNode();}else{walker.currentNode=node;node=walker.previousNode();}end=node?node.textContent.length:0;}else walker.currentNode=node;
                while(node){const text=node.textContent.slice(0,end),match=/\S(?=\s*$)/.exec(text);if(match){const r=document.createRange();r.setStart(node,match.index);r.setEnd(node,match.index+1);const rr=r.getBoundingClientRect();if(rr.width>0&&rr.height>0){last=rr;break;}}node=walker.previousNode();end=node?node.textContent.length:0;}
                result[item.decoration.id+':before']=last?page(last):-1;
            }catch(e){}}
            return result;})()"""
        var data: String?=null
        try {
            withTimeoutOrNull(250) {
                renderer.commitDecorations(requests.map { org.readium.r2.navigator.Decoration(pageBoundaryKey(it),it,GuidedBandStyle(0,true)) },group)
                // Readium's supported decoration API exposes its verified DOM quote range.
                // A failed anchor stays unknown; retain only what resolves within this small budget.
                while(true){data=renderer.evaluateJavascript(script);if(runCatching { val json=JSONObject(data ?: "{}");requests.all { json.has(pageBoundaryKey(it)) } }.getOrDefault(false))break;delay(20)}
            }
        } finally { withContext(NonCancellable) { renderer.commitDecorations(emptyList(),group) } }
        if(ticket!=generation||closed)return
        val json=runCatching { JSONObject(data) }.getOrNull() ?: return
        var changed=false
        requests.forEach { locator -> val key=pageBoundaryKey(locator);val value=(json.opt(key) as? Number)?.toInt()
            if(value!=null && value in 0 until pages){boundaryPages[key]=value;changed=true} }
        requests.forEach { locator -> val value=(json.opt(pageBoundaryKey(locator)+":before") as? Number)?.toInt()
            if(value!=null && value in -1 until pages){boundaryPages[beforeBoundaryKey(locator)]=value;changed=true} }
        if(changed){dirty=true;schedulePersist();update()}
    }
    private fun retainMeasurement(href: String,renderer: EpubNavigatorFragment,source: String,sample: PaginationSample) {
        if(!retainEvidence) return
        measurements.value=(measurements.value-href+Pair(href,PaginationMeasurement.capture(renderer,source,sample) )).entries.toList().takeLast(32).associate { it.toPair() }
    }
    private fun record(index: Int, total: Int) {
        if (total !in 1..100000 || index !in counts.indices) return
        if (counts[index] != total) {
            counts[index] = total; layouts[currentKey] = counts.copyOf(); dirty = true; schedulePersist()
        }
        update()
        if (counts.all { it > 0 }) counting.value = false
    }
    private fun snapshot(): PaginationCacheSnapshot? = if (dirty) cache?.let { PaginationCacheSnapshot(it, hrefs, counts.copyOf(),boundaryCache,boundaryPages.toMap()) } else null
    private fun schedulePersist() {
        if (persistJob?.isActive == true) return
        val ticket=generation
        persistJob = scope.launch {
            try {
                delay(350)
                val saved = snapshot() ?: return@launch
                withContext(Dispatchers.IO) { PaginationCache.write(saved) }
                // Keep the pending snapshot dirty through cancellable IO, so close/key changes
                // can still flush it if the activity write is cancelled while waiting for the lock.
                if(ticket==generation && counts.contentEquals(saved.counts) && boundaryPages==saved.boundaries) dirty=false
            } finally {
                if(ticket==generation) {
                    persistJob=null
                    if(dirty && !closed) schedulePersist()
                }
            }
        }
    }
    private fun flush() {
        persistJob?.cancel(); persistJob = null
        val saved = snapshot() ?: return
        dirty = false
        // Immutable cache-only snapshot survives Activity scope cancellation; no renderer is retained.
        PaginationCache.flush(saved)
    }
    fun close() {
        if (closed) return
        flush(); closed = true; generation++; pageGeneration++
        job?.cancel(); visibleJob?.cancel()
    }
}

internal data class PaginationDiagnostics(val cacheReadMillis: Long = -1, val memoryHit: Boolean = false,
    val visibleVerifications: Int=0,val visibleReuses: Int=0,
    val visibleMeasureMillis: Long = -1, val hiddenMeasureMillis: Long = 0, val hiddenResources: Int = 0)

internal object PaginationCounts {
    fun merge(saved: IntArray, live: IntArray) = IntArray(live.size) { i -> live[i].takeIf { it in 1..100000 } ?: saved.getOrElse(i) { 0 }.takeIf { it in 1..100000 } ?: 0 }
    fun label(counts: IntArray, index: Int, page: Int, scroll: Boolean): String {
        if (index !in counts.indices) return "Calculating pages…"
        val name = if (scroll) "Screen" else "Page"
        if (counts[index] <= 0) return "Calculating pages…"
        val local = page.coerceIn(0,counts[index]-1)+1
        return if (counts.all { it > 0 }) "$name ${counts.take(index).sumOf { it.toLong() }+local} of ${counts.sumOf { it.toLong() }}"
        else "$name $local of ${counts[index]} · chapter · ${counts.count { it > 0 }}/${counts.size} counted"
    }
}

internal data class PaginationSample(val pages: Int, val page: Int, val ready: Boolean, val geometry: String, val detail: String = "") {
    companion object {
        fun decode(data: String?): PaginationSample? = runCatching {
            val json = JSONObject(data ?: "null")
            val pages = json.getInt("pages"); require(pages in 1..100000)
            val geometry=json.getString("geometry"); require(geometry.length in 1..20000)
            PaginationSample(pages, json.getInt("page").coerceIn(0,pages-1), json.getBoolean("ready"), geometry, json.optJSONObject("detail")?.toString().orEmpty())
        }.getOrNull()
        fun script(expected: String, scroll: Boolean, diagnostics: Boolean = false) = """(function(){
            let expected=new URL(${JSONObject.quote(expected)},location.origin+'/').pathname,actual=location.pathname;
            try{expected=decodeURIComponent(expected);actual=decodeURIComponent(actual);}catch(e){}
            if(actual!==expected&&!actual.endsWith('/'+expected.replace(/^\/+/,'')))return null;
            let d=document.scrollingElement,h=document.documentElement,b=document.body;if(!d||!b)return null;
            let hs=getComputedStyle(h),bs=getComputedStyle(b),vertical=hs.writingMode.startsWith('vertical');
            let width=Android.getViewportWidth()/devicePixelRatio,scrolling=$scroll;
            let actualScroll=(h.style.getPropertyValue('--USER__view').trim()==='readium-scroll-on'||h.style.getPropertyValue('--USER__scroll').trim()==='readium-scroll-on');
            let size=scrolling&&!vertical?d.scrollHeight:d.scrollWidth,span=scrolling&&!vertical?innerHeight:width;
            if(!(span>0)||!Number.isFinite(size/span))return null;
            let pages=Math.max(1,Math.ceil(size/span-.02)),offset=scrolling&&!vertical?d.scrollTop:Math.abs(d.scrollLeft);
            let ready=document.readyState==='complete'&&(!document.fonts||document.fonts.status==='loaded')&&Array.from(document.images).every(i=>i.complete)&&actualScroll===scrolling;
            let geometry=[size,span,d.scrollWidth,d.scrollHeight,innerWidth,innerHeight,hs.writingMode,hs.columnCount,hs.columnGap,bs.fontFamily,bs.fontSize,bs.lineHeight,bs.padding,h.style.cssText.slice(0,8192)].join('|');
            let detail=null;if($diagnostics){let p=document.querySelector('p'),ps=p?getComputedStyle(p):null;
            detail={href:location.href,scrollWidth:d.scrollWidth,scrollHeight:d.scrollHeight,innerWidth:innerWidth,innerHeight:innerHeight,viewportWidthCss:width,devicePixelRatio:devicePixelRatio,span:span,htmlStyle:h.style.cssText.slice(0,8192),bodyFont:bs.fontFamily,bodyFontSize:bs.fontSize,bodyLineHeight:bs.lineHeight,bodyWidth:bs.width,bodyHeight:bs.height,bodyPadding:bs.padding,paragraphFont:ps?ps.fontFamily:null,paragraphFontSize:ps?ps.fontSize:null,paragraphLineHeight:ps?ps.lineHeight:null,fontStatus:document.fonts?document.fonts.status:null,imageCount:document.images.length};}
            return {pages:pages,page:Math.floor(offset/span+.001),ready:ready,geometry:geometry,detail:detail};
        })()"""
    }
}

/** Diagnostic evidence stores strings/integers only, never renderer/WebView references. */
internal data class PaginationMeasurement(val source: String,val sample: PaginationSample,val width: Int,val height: Int,
    val settings: String,val webViews: List<String>) {
    fun json()=JSONObject().put("source",source).put("pages",sample.pages).put("geometry",sample.geometry)
        .put("detail",runCatching { JSONObject(sample.detail) }.getOrNull()).put("width",width).put("height",height)
        .put("settings",settings).put("webViews",org.json.JSONArray(webViews.map { JSONObject(it) }))
    companion object {
        fun capture(renderer: EpubNavigatorFragment,source: String,sample: PaginationSample): PaginationMeasurement {
            val views=mutableListOf<String>()
            fun inspect(view: View) {
                if(view is WebView) {
                    val parent=view.parent as? View
                    views+=JSONObject().put("url",view.url).put("width",view.width).put("height",view.height)
                        .put("textZoom",view.settings.textZoom).put("paddingTop",view.paddingTop).put("paddingBottom",view.paddingBottom)
                        .put("parentPaddingTop",parent?.paddingTop).put("parentPaddingBottom",parent?.paddingBottom)
                        .put("visibility",view.visibility).toString()
                }
                if(view is ViewGroup) for(index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(renderer.publicationView)
            return PaginationMeasurement(source,sample,renderer.publicationView.width,renderer.publicationView.height,
                renderer.settings.value.toString(),views)
        }
    }
}

/** Initial fragment attachment can precede its first layout even when the host is already sized. */
internal class PaginationViewport {
    enum class Status { WAIT, MATCH, STALE }
    var width=0; private set
    var height=0; private set
    fun reset(width: Int=0,height: Int=0) {
        this.width=if(width>0 && height>0) width else 0
        this.height=if(width>0 && height>0) height else 0
    }
    fun inspect(width: Int,height: Int,laidOut: Boolean,layoutRequested: Boolean): Status {
        if(!laidOut || layoutRequested || width<=0 || height<=0) return Status.WAIT
        if(this.width==0 || this.height==0) reset(width,height)
        return if(this.width==width && this.height==height) Status.MATCH else Status.STALE
    }
}

/** Require full geometry (not only rounded page count) stable after fonts/images are ready. */
internal class PaginationSettler(private val quietMillis: Long = 80) {
    private var geometry: String? = null
    private var readySince = 0L
    private var samples = 0
    fun reset() { geometry=null; samples=0 }
    fun accept(sample: PaginationSample, now: Long): Boolean {
        if (!sample.ready) { reset(); return false }
        if (geometry != sample.geometry) { geometry=sample.geometry; readySince=now; samples=1; return false }
        samples++
        return samples>=2 && now-readySince>=quietMillis
    }
}

internal data class PaginationCacheSnapshot(val atomic: AtomicFile, val hrefs: List<String>, val counts: IntArray,
    val boundaryAtomic: AtomicFile? = null, val boundaries: Map<String,Int> = emptyMap())
internal object PaginationCache {
    private val mutex=Mutex()
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    suspend fun read(atomic: AtomicFile, hrefs: List<String>): IntArray = mutex.withLock { readUnlocked(atomic,hrefs) }
    private fun readUnlocked(atomic: AtomicFile, hrefs: List<String>): IntArray = runCatching {
        val json=atomic.openRead().use { input ->
            val bytes=ByteArray(2_000_001); var length=0
            while(length<bytes.size) {
                val read=input.read(bytes,length,bytes.size-length)
                if(read<0) break
                if(read==0) continue
                length+=read
            }
            require(length<=2_000_000)
            JSONObject(String(bytes,0,length,Charsets.UTF_8))
        }
        IntArray(hrefs.size) {
            val number=(json.opt(hrefs[it]) as? Number)?.toDouble()
            if(number!=null && number.isFinite() && number%1.0==0.0 && number in 1.0..100000.0) number.toInt() else 0
        }
    }.getOrElse { IntArray(hrefs.size) }
    suspend fun write(saved: PaginationCacheSnapshot) = mutex.withLock {
        // Preserve already measured chapters if two readers flush partial snapshots of the same key.
        val merged=PaginationCounts.merge(readUnlocked(saved.atomic,saved.hrefs),saved.counts)
        val bytes=JSONObject().apply { saved.hrefs.indices.filter { merged[it]>0 }.forEach { put(saved.hrefs[it],merged[it]) } }.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>2_000_000) return@withLock
        saved.atomic.baseFile.parentFile?.mkdirs()
        runCatching {
            val out=saved.atomic.startWrite()
            try { out.write(bytes); saved.atomic.finishWrite(out) } catch(e:Throwable) { saved.atomic.failWrite(out); throw e }
        }
        saved.boundaryAtomic?.let { PaginationBoundaryCache.write(it,saved.boundaries) }
        Unit
    }
    fun flush(saved: PaginationCacheSnapshot) { io.launch { write(saved) } }
}

internal fun pageBoundaryKey(locator: Locator): String {
    val identity=org.json.JSONArray(listOf(locator.href.removeFragment().toString(),locator.locations.fragments.joinToString("|"),locator.locations["cssSelector"]?.toString().orEmpty(),(locator.locations["folioOffset"] as? Number)?.toInt() ?: -1,locator.text.before.orEmpty(),locator.text.highlight.orEmpty(),locator.text.after.orEmpty())).toString()
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}
internal data class PageInformation(val label: String="Calculating pages…",val hrefs: List<String> = emptyList(),val counts: List<Int> = emptyList(),val boundaries: Map<String,Int> = emptyMap(),val scrolling: Boolean=false) {
    fun page(locator: Locator): Long? {
        val index=hrefs.indexOf(locator.href.removeFragment().toString());if(index !in counts.indices||counts.take(index+1).any { it<=0 })return null
        val local=boundaries[pageBoundaryKey(locator)] ?: if(counts[index]==1||(locator.text.highlight.isNullOrBlank()&&locator.locations.fragments.isEmpty()&&locator.locations["cssSelector"]==null)) 0 else return null
        if(local !in 0 until counts[index])return null
        return counts.take(index).sumOf { it.toLong() }+local+1L
    }
    fun lastPageInResource(locator: Locator): Long? {
        val index=hrefs.indexOf(locator.href.removeFragment().toString())
        if(index !in counts.indices||counts.take(index+1).any { it<=0 })return null
        return counts.take(index+1).sumOf { it.toLong() }
    }
    val total: Long? get() = counts.takeIf { it.isNotEmpty()&&it.all { n->n>0 } }?.sumOf { it.toLong() }
}
internal object PaginationBoundaryCache {
    fun read(atomic: AtomicFile): Map<String,Int> = runCatching {
        atomic.openRead().use { input -> val bytes=input.readNBytesCompat(500001);require(bytes.size<=500000);val j=JSONObject(bytes.toString(Charsets.UTF_8));require(j.length()<=5000)
            j.keys().asSequence().mapNotNull { key -> val value=(j.opt(key) as? Number)?.toInt();if(key.matches(Regex("[a-f0-9]{64}"))&&value!=null&&value in -1..99999)key to value else null }.toMap() }
    }.getOrDefault(emptyMap())
    fun write(atomic: AtomicFile,values: Map<String,Int>) {
        if(values.isEmpty())return
        val merged=read(atomic)+values;val bytes=JSONObject(merged.entries.take(5000).associate { it.toPair() }).toString().toByteArray()
        if(bytes.size>500000)return
        atomic.baseFile.parentFile?.mkdirs()
        runCatching { val out=atomic.startWrite();try{out.write(bytes);atomic.finishWrite(out)}catch(e:Throwable){atomic.failWrite(out);throw e} }
    }
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit){val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;if(n>0)out.write(buffer,0,n)}
        return out.toByteArray()
    }
}
