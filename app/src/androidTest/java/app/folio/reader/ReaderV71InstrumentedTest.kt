@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderV71InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun guide(a: ReaderActivity) = (ReaderActivity::class.java.getDeclaredField("guided$" + "delegate").apply { isAccessible = true }.get(a) as androidx.compose.runtime.State<*>).value as GuidedReading
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(15000) { while(!condition()) delay(50) } }
    @After fun cleanup() { SpeechController.stop(app); GuidedOptions().save(app); Pace().save(app); Appearance().save(app) }
    @Test fun guidedButtonsSkipSentencesAndParagraphsAndStopRemovesThePlayer() = runBlocking {
        SpeechController.stop(app); waitFor { SpeechController.state.value.bookId == null }
        GuidedOptions().save(app)
        val book = app.library.importSample(); val pub = app.engine.open(app.library.file(book.id))
        val text = try { BookText.load(pub,book.id) } finally { pub.close() }
        val point = text.sections.indices.firstNotNullOf { section -> text.sections[section].words.indices.firstNotNullOfOrNull { word ->
            text.cursor(section,word)?.takeIf { c -> val para=text.paragraph(c)!!; val sentence=text.sentence(c)!!; para.endWord-para.firstWord>20 && sentence.endWord-sentence.firstWord>3 && para.endWord<text.sections[section].words.size }
        } }
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
            lateinit var g: GuidedReading
            scenario.onActivity { g=guide(it); g.prepare() }; waitFor { g.ready.value }
            withContext(Dispatchers.Main) { g.seek(point.locator) }; waitFor { g.hasHighlight }
            val sentence=text.sentence(point)!!
            device.wait(Until.findObject(By.desc("Next sentence")),10000).click()
            waitFor { g.cursor.value?.wordIndex == sentence.endWord }
            assertFalse(g.running.value)
            withContext(Dispatchers.Main) { g.setOptions(g.options.copy(navigationUnit="paragraph")) }
            val paragraph=text.paragraph(g.cursor.value!!)!!
            device.wait(Until.findObject(By.desc("Next paragraph")),10000).click()
            waitFor { g.cursor.value?.wordIndex == paragraph.endWord }
            device.takeScreenshot(File(app.filesDir,"v71-guided-stop.png"))
            device.wait(Until.findObject(By.desc("Stop guided reading")),10000).click()
            waitFor { !g.active.value }; assertFalse(g.running.value)
            assertTrue(device.wait(Until.gone(By.desc("Reading actions")),10000))
        }
        Unit
    }
    @Test fun readerWebViewsAreOfflineBeforeLoadWhileLocalEpubContentStillRenders() = runBlocking {
        val book=app.library.importSample()
        val pub=app.engine.open(app.library.file(book.id))
        val extracted=try { BookText.load(pub,book.id) } finally { pub.close() }
        val point=extracted.sections.indices.firstNotNullOf { section -> extracted.cursor(section,0)?.takeIf { extracted.sections[section].words.size>20 } }
        app.library.progress(book.id,point.locator)
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),15000))
            var checked=0
            waitFor { scenario.onActivity { a ->
                fun visit(view: android.view.View) {
                    if(view is android.webkit.WebView) {
                        assertTrue("Network must be blocked",view.settings.blockNetworkLoads)
                        assertEquals("The inflation hook must run before Readium starts loading",true,view.getTag(R.id.offline_webview_constructed)); checked++
                    }
                    if(view is android.view.ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(a.window.decorView)
            }; checked>0 }
            lateinit var n: org.readium.r2.navigator.epub.EpubNavigatorFragment
            scenario.onActivity { n=it.supportFragmentManager.findFragmentByTag("epub") as org.readium.r2.navigator.epub.EpubNavigatorFragment }
            withContext(Dispatchers.Main) { withTimeout(10000) { while(n.evaluateJavascript("document.body.innerText.length>10")!="true") delay(50) } }
        }
        Unit
    }
    @Test fun versionHistoryAndManualUpdateControlsAreAvailableFromMore() {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            device.wait(Until.findObject(By.text("More")),15000).click()
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Version history")
            device.wait(Until.findObject(By.text("Version history")),10000).click()
            assertTrue(device.wait(Until.hasObject(By.text("Folio 0.7.1")),10000))
            device.takeScreenshot(File(app.filesDir,"v71-version-history.png"))
            device.pressBack()
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("App updates")
            device.wait(Until.findObject(By.text("App updates")),10000).click()
            assertTrue(device.wait(Until.hasObject(By.text("Check for updates")),10000))
            assertFalse(device.hasObject(By.text("Downloading APK…")))
            device.takeScreenshot(File(app.filesDir,"v71-updates.png"))
        }
    }
}
