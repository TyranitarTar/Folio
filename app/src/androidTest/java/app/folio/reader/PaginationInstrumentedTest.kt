@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** Records actual timings; cache warmth and the reference renderer's work are reported separately. */
@RunWith(AndroidJUnit4::class)
class PaginationInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private fun counter(activity: ReaderActivity): PageCounter = (ReaderActivity::class.java.getDeclaredField("counter\$delegate").apply { isAccessible=true }.get(activity) as androidx.compose.runtime.State<*>).value as PageCounter
    private fun navigator(activity: ReaderActivity)=activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
    private fun waitFor(check:()->Boolean)=runBlocking { withTimeout(90000) { while(!check()) delay(40) } }
    private fun current(scenario: ActivityScenario<ReaderActivity>): Pair<PageCounter,EpubNavigatorFragment> {
        var result: Pair<PageCounter,EpubNavigatorFragment>?=null
        waitFor { scenario.onActivity { activity ->
            if(activity.supportFragmentManager.findFragmentByTag("epub")!=null) result=counter(activity) to navigator(activity)
        }; result!=null }
        return result!!
    }
    @Test fun cacheFirstReopenAndAdaptiveCountsMatchIndependentSettledReference() = runBlocking {
        SpeechController.stop(app); waitFor { SpeechController.state.value.bookId==null }
        val original=Appearance.load(app)
        val selected=Appearance(publisher=false,font="Serif",fontSize=1.35f)
        selected.save(app)
        val book=app.library.importSample()
        val pub=app.engine.open(app.library.file(book.id))
        val links=pub.readingOrder.toList()
        val initial=pub.locatorFromLink(links.first())!!
        pub.close(); app.library.progress(book.id,initial)
        val directory=File(app.cacheDir,"pagination")
        directory.listFiles()?.filter { it.name.startsWith("${book.id}-v3-") }?.forEach { it.delete() }
        val report=JSONObject().put("timingCaveat","Cold/warm Activity times include startup; the reference uses a warmed visible renderer. These are diagnostics, not a controlled speed benchmark.")
        try {
            val coldStart=android.os.SystemClock.elapsedRealtime()
            var exact=IntArray(0)
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                val (pages,n)=current(scenario)
                waitFor { !pages.counting.value && pages.countedPages().all { it>0 } }
                exact=withContext(Dispatchers.Main) { pages.countedPages() }
                report.put("coldElapsedMillis",android.os.SystemClock.elapsedRealtime()-coldStart)
                    .put("exactCounts",org.json.JSONArray(exact.toList()))
                    .put("cacheReadMillis",pages.diagnostics.value.cacheReadMillis)
                    .put("visibleMeasureMillis",pages.diagnostics.value.visibleMeasureMillis)
                    .put("hiddenMeasureMillis",pages.diagnostics.value.hiddenMeasureMillis)
                    .put("hiddenResources",pages.diagnostics.value.hiddenResources)
                val adaptiveEvidence=withContext(Dispatchers.Main) { pages.measurements.value.toMap() }
                report.put("adaptiveEvidence",JSONObject().apply { adaptiveEvidence.forEach { (href,evidence) -> put(href,evidence.json()) } })
                val referenceEvidence=JSONObject()
                // Independent reference: navigate the same renderer, await its actual resource,
                // then require the former 120ms x4 geometry/asset readiness before comparing.
                val referenceStart=android.os.SystemClock.elapsedRealtime()
                val reference=IntArray(links.size)
                withContext(Dispatchers.Main) {
                    links.forEachIndexed { index,link ->
                        n.go(link,false)
                        withTimeout(12000) {
                            var last=-1; var stable=0
                            while(stable<3) {
                                delay(120)
                                val expected=JSONObject.quote(link.url().removeFragment().toString())
                                val raw=n.evaluateJavascript("""(function(){let wanted=new URL($expected,location.origin+'/').pathname,actual=location.pathname;try{wanted=decodeURIComponent(wanted);actual=decodeURIComponent(actual);}catch(e){}if(actual!==wanted&&!actual.endsWith('/'+wanted.replace(/^\/+/,'')))return null;let d=document.scrollingElement,span=Android.getViewportWidth()/devicePixelRatio;return {pages:Math.max(1,Math.ceil(d.scrollWidth/span-.02)),ready:Array.from(document.images).every(i=>i.complete)&&document.fonts.status==='loaded'};})()""")
                                val value=runCatching { JSONObject(raw ?: "null") }.getOrNull() ?: continue
                                val count=value.getInt("pages")
                                if(value.getBoolean("ready") && count==last) stable++ else stable=0
                                last=count; reference[index]=count
                            }
                        }
                        val sample=PaginationSample.decode(n.evaluateJavascript(PaginationSample.script(link.url().removeFragment().toString(),false,true)))
                        referenceEvidence.put(link.url().toString(),sample?.let { PaginationMeasurement.capture(n,"visible-reference",it).json() })
                    }
                }
                report.put("referenceEvidence",referenceEvidence)
                report.put("referenceElapsedMillis",android.os.SystemClock.elapsedRealtime()-referenceStart)
                    .put("referenceCounts",org.json.JSONArray(reference.toList()))
                File(app.filesDir,"pagination-v071-timings.json").writeText(report.toString(2))
                assertArrayEquals("Adaptive geometry must preserve the settled exact resource counts",reference,exact)
                report.put("equivalent",true)
            }
            withTimeout(5000) { while(directory.listFiles()?.none { it.name.startsWith("${book.id}-v3-") }!=false) delay(25) }
            val warmStart=android.os.SystemClock.elapsedRealtime()
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                val (pages,_)=current(scenario)
                waitFor { pages.countedPages().all { it>0 } }
                report.put("warmElapsedMillis",android.os.SystemClock.elapsedRealtime()-warmStart)
                    .put("warmCacheReadMillis",pages.diagnostics.value.cacheReadMillis)
                assertArrayEquals(exact,withContext(Dispatchers.Main) { pages.countedPages() })
                // No hidden renderer is created just to rediscover a complete v3 cache.
                scenario.onActivity { activity -> assertNull(activity.supportFragmentManager.findFragmentByTag("measure")) }
            }
            File(app.filesDir,"pagination-v071-timings.json").writeText(report.toString(2))
        } finally { original.save(app) }
    }
}
