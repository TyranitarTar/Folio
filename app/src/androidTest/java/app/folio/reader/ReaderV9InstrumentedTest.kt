@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderV9InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private suspend fun waitFor(check:()->Boolean)=withTimeout(20000){while(!check())delay(40)}
    private fun guide(a:ReaderActivity)=(ReaderActivity::class.java.getDeclaredField("guided"+'$'+"delegate").apply { isAccessible=true }.get(a) as androidx.compose.runtime.State<*>).value as GuidedReading
    private fun counter(a:ReaderActivity)=(ReaderActivity::class.java.getDeclaredField("counter"+'$'+"delegate").apply { isAccessible=true }.get(a) as androidx.compose.runtime.State<*>).value as PageCounter
    @Test fun advancingWindowsPreserveFullPaintOnEveryFrameAt300And500Wpm()=runBlocking {
        val old=Appearance.load(app);val bytes=ReaderV8InstrumentedTest().fixture();val book=app.library.importStream { ByteArrayInputStream(bytes) }
        val reports=JSONArray()
        try {
            SpeechController.stop(app);Appearance(publisher=false,font="Serif",fontSize=1.8f).save(app)
            val pub=app.engine.open(app.library.file(book.id));val text=try { BookText.load(pub,book.id) } finally { pub.close() }
            val point=text.sections.indices.firstNotNullOf { section->text.sections[section].words.indices.firstNotNullOfOrNull { word->text.cursor(section,word)?.takeIf { text.paragraph(it)!!.endWord-word>100 } } }
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
                lateinit var g:GuidedReading;lateinit var n:EpubNavigatorFragment
                scenario.onActivity { g=guide(it);n=it.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment;g.prepare() }
                waitFor { g.ready.value }
                for(wpm in listOf(300,500))for(words in listOf(4,8)) {
                    withContext(Dispatchers.Main) { g.setOptions(GuidedOptions(words=words,punctuation=false));g.setPace(Pace("WPM",wpm.toDouble()));g.seek(point.locator) }
                    withContext(Dispatchers.Main) { withTimeout(10000){while(n.evaluateJavascript("!!(window.folioGuidedPaint&&window.folioGuidedPaint.range&&window.folioGuidedPaint.range.toString().trim().split(/\\s+/u).length===$words)")!="true")delay(20)} }
                    val monitor="""(function(){if(window.folioFrameAudit)window.folioFrameAudit.stop=true;let a={frames:0,missing:0,partial:0,uncovered:0,overlapLost:0,changedOpacity:0,transitions:0,signatures:[],stop:false,previous:null};window.folioFrameAudit=a;
                    function covered(rects,paint){return rects.every(r=>paint.some(p=>p.left<=r.left+.8&&p.right>=r.right-.8&&p.top<=r.top+.8&&p.bottom>=r.bottom-.8));}
                    function tick(){if(a.stop)return;a.frames++;let s=window.folioGuidedPaint;if(!s||!s.range||!s.nodes.length){a.missing++;requestAnimationFrame(tick);return;}
                    let rects=Array.from(s.range.getClientRects()).filter(r=>r.width>.1&&r.height>.1),paint=s.nodes.map(n=>n.getBoundingClientRect());
                    if(s.range.toString().trim().split(/\s+/u).length!==$words)a.partial++;
                    if(!covered(rects,paint)){a.uncovered++;if(!a.firstGeometry)a.firstGeometry={raw:rects.map(r=>({left:r.left,top:r.top,right:r.right,bottom:r.bottom})),paint:paint.map(r=>({left:r.left,top:r.top,right:r.right,bottom:r.bottom})),colors:s.nodes.map(n=>getComputedStyle(n).backgroundColor),opacities:s.nodes.map(n=>getComputedStyle(n).opacity),layer:s.layer.getBoundingClientRect().toJSON(),probe:s.probe.getBoundingClientRect().toJSON(),bodyPosition:getComputedStyle(document.body).position,bodyPadding:getComputedStyle(document.body).padding};}
                    if(s.nodes.some(n=>getComputedStyle(n).opacity!=='1'||getComputedStyle(n).backgroundColor!=='rgba(119, 193, 170, 0.38)'))a.changedOpacity++;
                    if(a.previous&&a.previous.signature!==s.signature){a.transitions++;let overlap=a.previous.range.cloneRange();try{if(overlap.compareBoundaryPoints(Range.START_TO_START,s.range)<0)overlap.setStart(s.range.startContainer,s.range.startOffset);if(overlap.compareBoundaryPoints(Range.END_TO_END,s.range)>0)overlap.setEnd(s.range.endContainer,s.range.endOffset);if(!overlap.collapsed&&!covered(Array.from(overlap.getClientRects()).filter(r=>r.width>.1&&r.height>.1),paint))a.overlapLost++;}catch(e){a.overlapLost++;}}
                    a.previous={signature:s.signature,range:s.range.cloneRange()};if(a.signatures.length<120&&a.signatures[a.signatures.length-1]!==s.signature)a.signatures.push(s.signature);requestAnimationFrame(tick);}requestAnimationFrame(tick);return true;})()"""
                    withContext(Dispatchers.Main) { n.evaluateJavascript(monitor);g.start() };delay(3200)
                    withContext(Dispatchers.Main) { g.pause() }
                    val paused=g.cursor.value;delay(150)
                    val result=withContext(Dispatchers.Main) { JSONObject(n.evaluateJavascript("(function(){let a=window.folioFrameAudit;a.stop=true;return {frames:a.frames,missing:a.missing,partial:a.partial,uncovered:a.uncovered,overlapLost:a.overlapLost,changedOpacity:a.changedOpacity,transitions:a.transitions,firstGeometry:a.firstGeometry||null};})()")!!) }
                    reports.put(result.put("wpm",wpm).put("words",words));File(app.filesDir,"v9-guided-frames.json").writeText(reports.toString(2))
                    assertTrue(result.toString(),result.getInt("frames")>50);assertTrue(result.toString(),result.getInt("transitions")>=6)
                    for(key in listOf("missing","partial","uncovered","overlapLost","changedOpacity"))assertEquals(result.toString(),0,result.getInt(key))
                    assertEquals(paused,g.cursor.value)
                }
                withContext(Dispatchers.Main){g.clear();withTimeout(5000){while(n.evaluateJavascript("!!window.folioGuidedPaint")!="false")delay(20)}}
            }
        } finally { SpeechController.stop(app);app.library.removeBook(book.id);old.save(app);GuidedOptions().save(app);Pace().save(app) }
        Unit
    }
    @Test fun pageInformationUsesCachedWordsAndDoesNotRestartPaginationOrAdvancePausedGuide()=runBlocking {
        val old=Appearance.load(app)
        try {
            SpeechController.stop(app);Appearance().save(app);val book=app.library.importSample()
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Reading information")),15000))
                lateinit var g:GuidedReading;var generation=0L
                scenario.onActivity { g=guide(it);g.prepare() }
                waitFor { g.ready.value };delay(800);val cursor=g.cursor.value
                scenario.onActivity { generation=PageCounter::class.java.getDeclaredField("generation").apply{isAccessible=true}.getLong(counter(it)) }
                device.findObject(By.desc("Reading information")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Chapter distribution")),20000))
                assertTrue(device.wait(Until.hasObject(By.textContains("readable words")),10000))
                assertEquals(cursor,g.cursor.value);assertFalse(g.running.value)
                scenario.onActivity { assertEquals(generation,PageCounter::class.java.getDeclaredField("generation").apply{isAccessible=true}.getLong(counter(it))) }
                device.pressBack();assertTrue(device.wait(Until.hasObject(By.desc("Reading information")),10000))
                device.findObject(By.desc("Reading information")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Chapter distribution")),5000))
                assertEquals(cursor,g.cursor.value)
            }
        } finally { old.save(app);GuidedOptions().save(app) }
        Unit
    }
}
