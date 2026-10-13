@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.services.positions

@RunWith(AndroidJUnit4::class)
class ReaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)

    private fun launch(book: Book): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch(Intent(app, ReaderActivity::class.java).putExtra("book", book.id))

    private fun clickDescription(description: String) {
        val item = device.wait(Until.findObject(By.desc(description)), 15000)
        assertNotNull("Missing control: $description", item)
        item.click()
    }
    private fun clickText(text: String) {
        val item = device.wait(Until.findObject(By.text(text)), 15000)
        assertNotNull("Missing text: $text", item)
        item.click()
    }
    private fun script(scenario: ActivityScenario<ReaderActivity>, js: String): String? = runBlocking {
        val result = CompletableDeferred<String?>()
        scenario.onActivity { activity ->
            val navigator = activity.supportFragmentManager.findFragmentByTag("epub") as? EpubNavigatorFragment
            if (navigator == null) result.complete(null)
            else activity.lifecycleScope.launch {
                try { result.complete(navigator.evaluateJavascript(js)) }
                catch (e: Throwable) { result.completeExceptionally(e) }
            }
        }
        withTimeout(20000) { result.await() }
    }
    private fun waitScript(scenario: ActivityScenario<ReaderActivity>, js: String, contains: String) = runBlocking {
        withTimeout(30000) {
            while (script(scenario, js)?.contains(contains) != true) delay(150)
        }
    }

    @Test fun sampleRendersNavigatesBookmarksAndRestoresAfterClosing() {
        Appearance().save(app)
        val book = runBlocking { app.library.importSample() }
        NavigationSettings.save(app, book.id, NavigationSettings(), false)
        val initialPublication = runBlocking { app.engine.open(app.library.file(book.id)) }
        try { runBlocking { app.library.progress(book.id, initialPublication.positions().first()) } }
        finally { initialPublication.close() }
        runBlocking { book.bookmarks.forEach { app.library.removeMark(book.id, it.id) } }
        launch(book).use { scenario ->
            waitScript(scenario, "Array.from(document.images).some(i=>i.complete && i.naturalWidth>0)", "true")
            clickDescription("Contents and bookmarks")
            clickText("En stille stund")
            waitScript(scenario, "document.body.innerText", "lænestol")
            clickDescription("Save bookmark")
            runBlocking { withTimeout(10000) { while (app.library.books.value.first { it.id == book.id }.bookmarks.isEmpty()) delay(100) } }
            clickDescription("Contents and bookmarks")
            clickText("Bookmarks")
            assertTrue(device.wait(Until.hasObject(By.desc("Remove mark")), 10000))
            device.pressBack()
            clickText("Aa")
            clickText("Night")
            device.pressBack()
            runBlocking { withTimeout(10000) { while (Appearance.load(app).theme != "Night") delay(100) } }
            scenario.recreate()
            waitScript(scenario, "document.body.innerText", "lænestol")
            assertEquals("Night", Appearance.load(app).theme)
        }
        launch(book).use { scenario ->
            waitScript(scenario, "document.body.innerText", "lænestol")
            assertEquals(1, app.library.books.value.first { it.id == book.id }.bookmarks.size)
        }
        Appearance().save(app)
    }

    @Test fun suppliedIllustratedEpubRendersAndOpensAChapter() {
        val path = InstrumentationRegistry.getArguments().getString("externalEpub")
        org.junit.Assume.assumeTrue("Optional local EPUB fixture was not supplied", path != null)
        val book = runBlocking { app.library.importStream { File(path!!).inputStream() } }
        val publication = runBlocking { app.engine.open(app.library.file(book.id)) }
        @OptIn(org.readium.r2.shared.InternalReadiumApi::class)
        val guarded = publication.container is SafePublicationContainer
        assertTrue("Rendered publications must guard late native resource reads", guarded)
        val readingOrder = publication.readingOrder
        assertTrue(readingOrder.size > 3)
        val chapter = publication.tableOfContents.first()
        runBlocking { app.library.progress(book.id, publication.positions().first()) }
        publication.close()
        launch(book).use { scenario ->
            waitScript(scenario, "Array.from(document.images).some(i=>i.complete && i.naturalWidth>0)", "true")
            scenario.onActivity { activity ->
                val navigator = activity.supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                assertTrue(navigator.go(chapter, false))
            }
            waitScript(scenario, "document.body.innerText.length > 200", "true")
        }
    }
}
