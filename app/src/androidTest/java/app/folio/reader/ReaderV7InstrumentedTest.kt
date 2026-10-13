@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderV7InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(30000) { while (!condition()) delay(50) } }
    private fun navigator(a: ReaderActivity) = a.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
    private fun guide(a: ReaderActivity) = (ReaderActivity::class.java.getDeclaredField("guided$" + "delegate")
        .apply { isAccessible = true }.get(a) as androidx.compose.runtime.State<*>).value as GuidedReading
    @After fun cleanup() {
        SpeechController.stop(app); GuidedOptions().save(app); Pace().save(app); Appearance().save(app); SpeechSettings().save(app)
    }
    @Test fun contextualWindowsGroupsAndRenderedLinesKeepTheirDominantWordAfterReflow() = runBlocking {
        SpeechController.stop(app)
        val book = app.library.importSample()
        val publication = app.engine.open(app.library.file(book.id))
        val text = try { BookText.load(publication, book.id) } finally { publication.close() }
        val point = text.sections.indices.firstNotNullOf { section -> text.sections[section].words.indices
            .mapNotNull { word -> text.cursor(section, word)?.takeIf { text.paragraph(it)!!.endWord - word >= 12 } }.firstOrNull() }
        app.library.progress(book.id, point.locator)
        Appearance(publisher = false, fontSize = 1f).save(app)
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            lateinit var g: GuidedReading; lateinit var n: EpubNavigatorFragment
            scenario.onActivity { a -> g = guide(a); n = navigator(a); g.prepare() }
            waitFor { g.ready.value }
            withContext(Dispatchers.Main) { g.setOptions(GuidedOptions(words = 4)); g.seek(point.locator) }
            waitFor { g.hasHighlight }
            withContext(Dispatchers.Main) {
                withTimeout(10000) { while (n.evaluateJavascript("window.readium.getDecorations('guided').items.length") != "1") delay(80) }
                withTimeout(10000) { while(n.evaluateJavascript("(function(){let d=window.readium.getDecorations('guided').items[0];return !!(d&&d.folioWindowRange&&d.folioWindowRange.toString().trim().split(/\\s+/u).length===4);})()")!="true") delay(20) }
                val line = n.renderedReadingLine(point.locator)!!
                assertEquals(point.wordIndex, text.locate(line.first)!!.wordIndex)
                assertTrue(text.locate(line.last)!!.wordIndex >= point.wordIndex)
                val next = n.renderedReadingLine(point.locator, 1)!!
                assertTrue(text.locate(next.first)!!.wordIndex > text.locate(line.last)!!.wordIndex)
                g.setOptions(GuidedOptions(mode = "Groups", words = 4)); g.setPace(Pace(speed = 200.0))
                g.start()
            }
            waitFor { (g.cursor.value?.wordIndex ?: 0) >= point.wordIndex + 4 }
            withContext(Dispatchers.Main) { g.pause() }
            val paused = g.cursor.value!!
            assertEquals(0, (paused.wordIndex - point.wordIndex) % 4)
            assertTrue(device.wait(Until.hasObject(By.desc("Resume reading")), 10000))
            withContext(Dispatchers.Main) {
                g.setOptions(GuidedOptions(mode = "Line"))
                n.submitPreferences(Appearance(publisher = false, fontSize = 2f).preferences("Book"))
                delay(500); g.restoreHighlight()
                assertEquals(paused, g.cursor.value)
                val line = n.renderedReadingLine(paused.locator)!!
                assertEquals(paused.wordIndex, text.locate(line.first)!!.wordIndex)
                assertTrue(text.locate(line.last)!!.wordIndex >= paused.wordIndex)
            }
            device.takeScreenshot(File(app.filesDir, "v7-line-player.png"))
        }
        Unit
    }
    @Test fun compactPlayerRemainsAccessibleBelowTextAtTwoHundredPercentSystemFont() = runBlocking {
        val oldScale = device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 2.0")
            val book = app.library.importSample()
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
                lateinit var g: GuidedReading; lateinit var n: EpubNavigatorFragment
                scenario.onActivity { a -> g = guide(a); n = navigator(a); g.prepare() }
                waitFor { g.ready.value }
                withContext(Dispatchers.Main) { g.seek(g.cursor.value!!.locator) }
                assertTrue(device.wait(Until.hasObject(By.desc("Resume reading")), 10000))
                delay(700)
                var viewportBottom = 0; var density = 1f
                scenario.onActivity { a ->
                    val position = IntArray(2); n.publicationView.getLocationOnScreen(position)
                    viewportBottom = position[1] + n.publicationView.height
                    density = a.resources.displayMetrics.density
                }
                device.takeScreenshot(File(app.filesDir, "v7-large-system-font.png"))
                device.dumpWindowHierarchy(File(app.filesDir, "v7-large-system-font.xml"))
                for (description in listOf("Previous sentence", "Resume reading", "Next sentence", "Stop guided reading", "Reading actions")) {
                    val control = device.findObject(By.desc(description)); assertNotNull(description, control)
                    assertTrue("$description overlays the text", control.visibleBounds.top >= viewportBottom - 2)
                    assertTrue("$description needs a 48dp target: bounds=${control.visibleBounds}, density=$density, clickable=${control.isClickable}", control.visibleBounds.height() >= (47 * density).toInt())
                }
                device.takeScreenshot(File(app.filesDir, "v7-large-system-font.png"))
            }
        } finally {
            if (oldScale == "null") device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $oldScale")
        }
        Unit
    }
    @Test fun pausedExplicitSeekFollowsTargetWhileManualBrowsingKeepsItsOwnPage() = runBlocking {
        SpeechController.stop(app); waitFor { SpeechController.state.value.bookId == null }
        SpeechSettings(backend = "supertonic").save(app)
        val book = app.library.importSample()
        val pub = app.engine.open(app.library.file(book.id))
        val text = try { BookText.load(pub, book.id) } finally { pub.close() }
        val section = text.sections.indexOfFirst { it.words.size > 200 }
        val target = text.cursor(section, 150)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            lateinit var n: EpubNavigatorFragment
            scenario.onActivity { n = navigator(it) }
            SpeechController.seek(app, book.id, target.locator, true)
            waitFor { SpeechController.state.value.locator?.text == target.locator.text && !SpeechController.state.value.preparing }
            assertFalse(SpeechController.state.value.playing)
            withContext(Dispatchers.Main) {
                try {
                    withTimeout(10000) { while (n.evaluateJavascript("(function(){let d=window.readium.getDecorations('speech').items[0];if(!d)return false;let r=d.range.getBoundingClientRect();return r.width>0&&r.left>=-2&&r.right<=innerWidth+2&&r.top>=-2&&r.bottom<=innerHeight+2;})()") != "true") delay(80) }
                } catch (failure: Throwable) {
                    val info = n.evaluateJavascript("(function(){let d=window.readium.getDecorations('speech').items[0];return {href:location.href,width:innerWidth,height:innerHeight,scrollX:scrollX,count:window.readium.getDecorations('speech').items.length,rect:d?d.range.getBoundingClientRect():null,word:d?d.range.toString():null};})()")
                    File(app.filesDir, "v7-paused-seek-debug.json").writeText(info ?: "null")
                    device.takeScreenshot(File(app.filesDir, "v7-paused-seek-debug.png"))
                    throw failure
                }
            }
            val before = SpeechController.state.value.seekRevision
            val next = text.cursor(section, 195)!!
            SpeechController.seek(app, book.id, next.locator, true)
            waitFor { SpeechController.state.value.seekRevision > before && SpeechController.state.value.locator?.text == next.locator.text }
            assertFalse(SpeechController.state.value.playing)
            withContext(Dispatchers.Main) { delay(500); assertEquals(next.locator.href, n.currentLocator.value.href) }
            val page = device.findObject(By.desc("Next page")); assertNotNull(page); page.click(); delay(700)
            assertEquals(next.locator.text, SpeechController.state.value.locator?.text)
            assertFalse(SpeechController.state.value.playing)
        }
        Unit
    }
    @Test fun acceptedResumeClosesSettingsAndShowsPreparationOnTheReadingPage() = runBlocking {
        SpeechController.stop(app)
        waitFor { SpeechController.state.value.bookId == null }
        SpeechSettings(backend = "supertonic", supertonicLanguage = "en").save(app)
        val book = app.library.importSample()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            val read = device.wait(Until.findObject(By.text("Read aloud")), 15000); assertNotNull(read); read.click()
            val resume = device.wait(Until.findObject(By.text("Resume listening")), 15000); assertNotNull(resume); resume.click()
            waitFor { SpeechController.state.value.bookId == book.id }
            assertTrue(device.wait(Until.gone(By.text("Resume listening")), 10000))
            assertTrue("Reader must keep its playback controls during preparation",
                device.wait(Until.hasObject(By.desc("Reading actions")), 10000))
            device.findObject(By.text("Got it"))?.click()
            device.takeScreenshot(File(app.filesDir, "v7-loading-player.png"))
            SpeechController.stop(app)
            waitFor { SpeechController.state.value.bookId == null }
            delay(1500)
            assertFalse("Cancelled preparation must not start sound later", SpeechController.state.value.playing)
            assertTrue(SpeechController.follow.value)
        }
    }
}
