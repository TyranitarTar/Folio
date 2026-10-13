@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment

@RunWith(AndroidJUnit4::class)
class ReadingModesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun click(text: String) {
        repeat(8) { if (!device.hasObject(By.text(text))) device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(if (text in listOf("Start from here", "Resume guided reading")) Direction.UP else Direction.DOWN, .5f) }
        val item = device.wait(Until.findObject(By.text(text)), 15000); assertNotNull("Missing $text", item); item.click() }
    private fun js(scenario: ActivityScenario<ReaderActivity>, script: String): String? = runBlocking {
        val done = CompletableDeferred<String?>()
        scenario.onActivity { a -> a.lifecycleScope.launch {
            done.complete((a.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment)?.evaluateJavascript(script))
        } }
        withTimeout(15000) { done.await() }
    }
    private fun pageTotal(): Int {
        val label = device.wait(Until.findObject(By.text(Patterns.page)), 45000)
        assertNotNull("Rendered page count did not finish", label)
        return label.text.substringAfter(" of ").toInt()
    }
    private object Patterns { val page = java.util.regex.Pattern.compile("Page [0-9]+ of [0-9]+") }
    @Test fun tapChangesExactlyOneRenderedPageAndRotationRecounts() = runBlocking {
        SpeechController.stop(app)
        Appearance(publisher = false, font = "Serif", fontSize = 2f).save(app)
        val book = app.library.importSample()
        NavigationSettings.save(app, book.id, NavigationSettings(animate = false), false)
        val pub = app.engine.open(app.library.file(book.id))
        val first = BookText.load(pub).cursor(0, 0)!!.locator
        app.library.progress(book.id, first)
        pub.close()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            val portraitPages = pageTotal()
            fun currentPage() = device.findObject(By.text(Patterns.page)).text.substringAfter("Page ").substringBefore(" of").toInt()
            val before = currentPage()
            device.takeScreenshot(java.io.File(app.cacheDir, "page-count.png"))
            fun tap(fraction: Float) {
                var x = 0; var y = 0
                scenario.onActivity { activity ->
                    val view = (activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment).publicationView
                    val point = IntArray(2); view.getLocationOnScreen(point)
                    x = point[0] + (view.width * fraction).toInt(); y = point[1] + view.height / 2
                }
                device.click(x, y)
            }
            tap(.9f)
            withTimeout(10000) { while (currentPage() != before + 1) delay(100) }
            tap(.1f)
            withTimeout(10000) { while (currentPage() != before) delay(100) }
            try {
                device.setOrientationLeft()
                delay(1200)
                val landscapePages = pageTotal()
                assertNotEquals("Viewport change must produce a new layout count", portraitPages, landscapePages)
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        }
        Appearance().save(app)
    }
    @Test fun guidedWordSurvivesPauseReopenAndTypographyChange() = runBlocking {
        SpeechController.stop(app)
        Appearance(publisher = false, font = "Serif", fontSize = 1f).save(app)
        val book = app.library.importSample()
        NavigationSettings.save(app, book.id, NavigationSettings(animate = false), false)
        java.io.File(app.filesDir, "reader-state/${book.id}-guided.json").delete()
        val pub = app.engine.open(app.library.file(book.id))
        val text = BookText.load(pub)
        app.library.progress(book.id, text.cursor(0, 12)!!.locator)
        pub.close()
        val launch = Intent(app, ReaderActivity::class.java).putExtra("book", book.id)
        var paused: ReadingCursor? = null
        var originalPages = 0
        ActivityScenario.launch<ReaderActivity>(launch).use { scenario ->
            originalPages = pageTotal()
            assertTrue(originalPages >= 3)
            click("Guided reading")
            click("150")
            click("Start from here")
            val storage = ReaderCursorStore(app)
            withTimeout(15000) { while ((storage.read(book.id, "guided")?.wordIndex ?: 0) < 16) delay(80) }
            assertTrue(js(scenario, "window.readium.getDecorations('guided').items.length")!!.toInt() > 0)
            val pause = device.wait(Until.findObject(By.desc("Pause reading")), 5000)
            assertNotNull(pause); pause.click()
            delay(200)
            paused = storage.read(book.id, "guided")
            assertNotNull(paused)
            val actualWord = js(scenario, "window.readium.getDecorations('guided').items[0].range.toString()")
            assertEquals("The temporary highlight must anchor the actual saved word", paused!!.locator.text.highlight,
                org.json.JSONTokener(actualWord).nextValue())
            device.takeScreenshot(java.io.File(app.cacheDir, "guided-reading.png"))
            delay(900)
            assertEquals(paused, storage.read(book.id, "guided"))
            device.findObject(By.text("Got it"))?.click()
            device.pressBack() // Reveal the ordinary reader chrome above the persistent compact player.
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 10000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            pageTotal()
            click("Guided reading")
            assertNotNull(device.wait(Until.findObject(By.text("Saved word: ${paused!!.locator.text.highlight}")), 10000))
            device.pressBack()
        }
        Appearance(publisher = false, font = "Serif", fontSize = 2f).save(app)
        ActivityScenario.launch<ReaderActivity>(launch).use { scenario ->
            assertTrue("Larger type must add rendered pages", pageTotal() > originalPages)
            assertEquals(paused, ReaderCursorStore(app).read(book.id, "guided"))
            click("Guided reading")
            click("Resume guided reading")
            withTimeout(12000) {
                while ((ReaderCursorStore(app).read(book.id, "guided")?.wordIndex ?: 0) <= paused!!.wordIndex) delay(80)
            }
            assertTrue(js(scenario, "window.readium.getDecorations('guided').items.length")!!.toInt() > 0)
        }
        Appearance().save(app)
    }
}
