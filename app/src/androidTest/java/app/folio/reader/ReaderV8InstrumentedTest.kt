@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.readium.r2.navigator.epub.EpubNavigatorFragment

@RunWith(AndroidJUnit4::class)
class ReaderV8InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private suspend fun waitFor(check:()->Boolean) { withTimeout(20000) { while(!check()) delay(50) } }
    private fun guide(a: ReaderActivity)=(ReaderActivity::class.java.getDeclaredField("guided\$delegate").apply { isAccessible=true }.get(a) as androidx.compose.runtime.State<*>).value as GuidedReading
    private fun awake(a: ReaderActivity)=ReaderActivity::class.java.getDeclaredField("awake").apply { isAccessible=true }.get(a) as ReaderAwakeController
    private fun model(a: ReaderActivity)=ReaderActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible=true }.invoke(a) as ReaderModel
    private fun counter(a: ReaderActivity)=(ReaderActivity::class.java.getDeclaredField("counter\$delegate").apply { isAccessible=true }.get(a) as androidx.compose.runtime.State<*>).value as PageCounter
    private fun generation(a: ReaderActivity)=PageCounter::class.java.getDeclaredField("generation").apply { isAccessible=true }.getLong(counter(a))
    @Test fun timedProtectionSurvivesShortDeviceTimeoutRotationAndActualPageKeys()=runBlocking {
        val old=Appearance.load(app)
        val timeout=device.executeShellCommand("settings get system screen_off_timeout").trim()
        try {
            SpeechController.stop(app);Appearance(volumeKeys=true).save(app)
            device.wakeUp();device.executeShellCommand("settings put system screen_off_timeout 5000")
            val book=app.library.importSample()
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
                var last=0L
                scenario.onActivity { a -> last=awake(a).session.lastInput;assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0) }
                delay(6500)
                assertTrue("Device timeout must not interrupt the protected interval",device.isScreenOn)
                scenario.onActivity { a -> assertEquals(last,awake(a).session.lastInput) }
                scenario.recreate()
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
                scenario.onActivity { a -> assertEquals("Recreation must not renew input",last,awake(a).session.lastInput) }
                device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
                waitFor { var advanced=false;scenario.onActivity { advanced=awake(it).session.lastInput>last };advanced }
                lateinit var g: GuidedReading
                scenario.onActivity { g=guide(it);g.prepare() };waitFor { g.ready.value }
                withContext(Dispatchers.Main) { g.seek(g.cursor.value!!.locator);g.start() }
                waitFor { g.running.value };delay(900)
                var beforeGeneration=0L
                scenario.onActivity { beforeGeneration=generation(it);model(it).settings(model(it).appearance.withAwake(ReaderAwakePolicy("device"))) }
                delay(300)
                assertTrue("Timeout-only settings must not pause the guide",g.running.value)
                scenario.onActivity { assertEquals(beforeGeneration,generation(it));assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0) }
                withContext(Dispatchers.Main) { g.pause() }
                waitFor { var released=false;scenario.onActivity { released=it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON==0 };released }
            }
        } finally {
            if(timeout=="null") device.executeShellCommand("settings delete system screen_off_timeout") else device.executeShellCommand("settings put system screen_off_timeout $timeout")
            old.save(app);GuidedOptions().save(app);Pace().save(app)
        }
        Unit
    }
    internal fun fixture(): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String,text: String,stored: Boolean=false) {
                val bytes=text.toByteArray();val entry=ZipEntry(name)
                if(stored){entry.method=ZipEntry.STORED;entry.size=bytes.size.toLong();entry.compressedSize=entry.size;entry.crc=CRC32().apply { update(bytes) }.value}
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
            add("mimetype","application/epub+zip",true)
            add("META-INF/container.xml","""<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            add("book.opf","""<package version="3.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">folio-guided-band-test-v8</dc:identifier><dc:title>Guided band test</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-13T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            add("nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">A continuous passage</a></li></ol></nav></body></html>""")
            val passage=(1..8).joinToString(" ") { "A continuous <em>highlight</em> should cover <strong>every word</strong> and all intervening spaces, even when the text wraps across lines." }
            add("chapter.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A continuous passage</title></head><body><h1>A continuous passage</h1><p id="passage">$passage</p></body></html>""")
        }
        return out.toByteArray()
    }
    @Test fun guidedWindowsUseUniformContiguousLineSegmentsWithInlineMarkupAndAllThemes()=runBlocking {
        val old=Appearance.load(app);val bytes=fixture()
        val book=app.library.importStream { ByteArrayInputStream(bytes) }
        try {
            SpeechController.stop(app);Appearance(publisher=false,font="Serif").save(app)
            val pub=app.engine.open(app.library.file(book.id))
            val text=try { BookText.load(pub,book.id) } finally { pub.close() }
            val point=text.sections.indices.firstNotNullOf { section -> text.sections[section].words.indices.firstNotNullOfOrNull { word ->
                text.cursor(section,word)?.takeIf { text.paragraph(it)!!.endWord-it.wordIndex>70 }
            } }
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
                lateinit var g:GuidedReading;lateinit var n:EpubNavigatorFragment
                scenario.onActivity { g=guide(it);n=it.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment;g.prepare() }
                waitFor { g.ready.value }
                val target=text.cursor(point.sectionIndex,point.wordIndex+1)!!
                app.library.addMark(book.id,target.locator,true,"Band preserves annotation")
                val annotation=app.library.books.value.single { it.id==book.id }.highlights.single()
                assertNotNull("Saved annotation must retain a valid locator",annotation.position())
                val annotationId=JSONObject.quote(annotation.id)
                val annotationScript="""(function(){let items=window.readium.getDecorations('highlights').items;return {ids:items.map(x=>x.decoration.id),rendered:items.some(x=>x.decoration.id===$annotationId&&x.container&&x.container.children.length>0)};})()"""
                // addMark updates the store before the lifecycle collector, Readium dispatch and
                // requestAnimationFrame have painted it. Establish that real baseline first.
                var annotationBaseline:JSONObject?=null
                withContext(Dispatchers.Main) {
                    n.go(target.locator,animated=false)
                    withTimeoutOrNull(10000) {
                        while(true) {
                            annotationBaseline=runCatching { JSONObject(n.evaluateJavascript(annotationScript) ?: "null") }.getOrNull()
                            if(annotationBaseline?.optBoolean("rendered")==true)break
                            delay(30)
                        }
                    }
                }
                assertTrue("New saved annotation must render before guided preservation is tested: $annotationBaseline",annotationBaseline?.optBoolean("rendered")==true)
                for(theme in listOf("Paper","Sepia","Night")) for(size in listOf(1f,1.9f)) for(words in listOf(4,8)) {
                    withContext(Dispatchers.Main) {
                        val appearance=Appearance(theme=theme,publisher=false,font="Serif",fontSize=size)
                        appearance.save(app);n.submitPreferences(appearance.preferences());delay(350)
                        g.setOptions(GuidedOptions(words=words));g.seek(target.locator)
                    }
                    val script="""(function(){let d=window.readium.getDecorations('guided').items[0];if(!d||!d.folioWindowRange)return null;let nodes=Array.from(window.folioGuidedPaint.layer.querySelectorAll('.folio-guided-band')),raw=Array.from(d.folioWindowRange.getClientRects()).filter(r=>r.width>.1&&r.height>.1),paint=nodes.map(x=>x.getBoundingClientRect());let covered=raw.every(r=>paint.some(p=>p.left<=r.left+.6&&p.right>=r.right-.6&&p.top<=r.top+.6&&p.bottom>=r.bottom-.6));let colors=nodes.map(x=>getComputedStyle(x).backgroundColor),opacities=nodes.map(x=>getComputedStyle(x).opacity);let overlap=paint.some((p,i)=>paint.some((q,j)=>i<j&&Math.min(p.right,q.right)-Math.max(p.left,q.left)>1&&Math.min(p.bottom,q.bottom)-Math.max(p.top,q.top)>1));return {words:d.folioWindowRange.toString().trim().split(/\s+/u).length,selected:d.folioWindowRange.toString(),segments:paint.length,uniform:new Set(colors).size===1&&new Set(opacities).size===1,covered:covered,overlap:overlap,rawRects:raw.map(r=>({left:r.left,top:r.top,right:r.right,bottom:r.bottom})),paintRects:paint.map(r=>({left:r.left,top:r.top,right:r.right,bottom:r.bottom})),colors:colors,opacities:opacities,layerRect:window.folioGuidedPaint.layer.getBoundingClientRect().toJSON(),probeRect:window.folioGuidedPaint.probe.getBoundingClientRect().toJSON(),bodyPosition:getComputedStyle(document.body).position,bodyPadding:getComputedStyle(document.body).padding,annotationIds:window.readium.getDecorations('highlights').items.map(x=>x.decoration.id),annotationRendered:window.readium.getDecorations('highlights').items.some(x=>x.decoration.id===$annotationId&&x.container&&x.container.children.length>0)};})()"""
                    var result:JSONObject?=null
                    withContext(Dispatchers.Main) { withTimeoutOrNull(10000) { while(true) { result=runCatching { JSONObject(n.evaluateJavascript(script) ?: "null") }.getOrNull();if(result?.optInt("words")==words && result?.optBoolean("annotationRendered")==true)break;delay(30) } } }
                    assertEquals("Guided window must settle for $theme/$size/$words: $result",words,result?.optInt("words") ?: -1)
                    val r=result!!;File(app.filesDir,"v9-guided-geometry.json").writeText(r.put("theme",theme).put("fontSize",size).put("windowWords",words).toString(2));assertTrue(r.toString(),r.getBoolean("uniform"));assertTrue(r.toString(),r.getBoolean("covered"));assertFalse(r.toString(),r.getBoolean("overlap"));assertEquals(target,g.cursor.value)
                    assertTrue("The same saved annotation must remain rendered in its own group for $theme/$size/$words: $r",r.getBoolean("annotationRendered"))
                    assertEquals(annotation.id,r.getJSONArray("annotationIds").getString(0))
                    if(size>1.5f && words==8) device.takeScreenshot(File(app.filesDir,"v8-guided-${theme.lowercase()}-large.png"))
                }
            }
        } finally { SpeechController.stop(app);app.library.removeBook(book.id);old.save(app);GuidedOptions().save(app) }
        Unit
    }
}
