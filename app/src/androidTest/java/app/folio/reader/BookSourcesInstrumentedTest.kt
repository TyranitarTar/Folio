@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourcesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun catalog() = BookCatalog(SourceHttp(File(app.cacheDir, "source-catalogs")))

    private suspend fun imported(edition: SourceBook): Book {
        withContext(Dispatchers.IO) { app.bookDownloads.enqueue(edition) }
        val job = withTimeout(180000) {
            while (true) {
                val item = app.bookDownloads.entries.value.firstOrNull { it.key == edition.key }
                if (item?.state == "Failed") error("${edition.source}/${edition.language}: ${item.message}")
                if (item?.state == "Complete") return@withTimeout item
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
        app.library.load()
        val book = app.library.books.value.first { it.id == job.bookId }
        assertEquals(edition.key, book.origin!!.key)
        val publication = app.engine.open(app.library.file(book.id))
        val text = try { BookText.load(publication, book.id) } finally { publication.close() }
        assertTrue("The downloaded work must contain actual readable text", text.sections.sumOf { it.words.size } > 500)
        assertTrue(app.library.file(book.id).length() > 1000)
        return book
    }

    @Test fun liveGutenbergEnglishAndDanishDownloadIntoOfflineLibraryAndOpenInReader() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            val client = catalog()
            val english = client.edition(SourceBook(BookSource.GUTENBERG, "11", "en", "Alice's Adventures in Wonderland", pageUrl = "https://www.gutenberg.org/ebooks/11"))
            assertTrue(english.downloadUrl!!.contains("images"))
            val englishBook = imported(english)
            val danishResult = client.search(BookSource.GUTENBERG, "da", "Ved Vejen")
            assertTrue("Danish catalog must find Ved Vejen", danishResult.books.isNotEmpty())
            val danish = client.edition(danishResult.books.first())
            val danishBook = imported(danish)
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", danishBook.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
                device.takeScreenshot(File(app.filesDir, "v08-gutenberg-danish-reader.png"))
            }
            val before = app.library.books.value.first { it.id == englishBook.id }
            imported(english)
            assertEquals(1, app.library.books.value.count { it.origin?.key == english.key })
            assertEquals(before.locator, app.library.books.value.first { it.id == englishBook.id }.locator)
        }
        Unit
    }

    @Test fun liveWikisourceEnglishAndDanishPrepareCompleteWorksWithAttribution() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { _ ->
            val client = catalog()
            val english = client.edition(SourceBook(BookSource.WIKISOURCE, "The Gift of the Magi", "en", "The Gift of the Magi", pageUrl = SourceUrls.wikiPage("en", "The Gift of the Magi")))
            assertEquals("wiki", english.format)
            val englishBook = imported(english)
            val danish = client.edition(SourceBook(BookSource.WIKISOURCE, "Den lille Havfrue", "da", "Den lille Havfrue", pageUrl = SourceUrls.wikiPage("da", "Den lille Havfrue")))
            assertTrue(danish.author.contains("Andersen"))
            val danishBook = imported(danish)
            java.util.zip.ZipFile(app.library.file(danishBook.id)).use { zip ->
                assertTrue(zip.entries().asSequence().filter { it.name.endsWith(".xhtml") }.any { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }.contains("Source and attribution")
                })
            }
            val publication = app.engine.open(app.library.file(englishBook.id))
            try { assertTrue(publication.readingOrder.size >= 2) } finally { publication.close() }
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", danishBook.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
                device.takeScreenshot(File(app.filesDir, "v08-wikisource-danish-reader.png"))
            }
        }
        Unit
    }

    @Test fun browseShowsOnlyTheTwoSourcesAndQueueSurvivesActivityRecreation() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Browse")), 15000))
            device.findObject(By.text("Browse")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Project Gutenberg")), 10000))
            assertTrue(device.hasObject(By.text("Wikisource")))
            assertFalse(device.hasObject(By.text("Litteraturbanken")))
            device.takeScreenshot(File(app.filesDir, "v08-book-sources.png"))
            device.findObject(By.text("Downloads")).click()
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Downloads")), 10000))
            val reloaded = BookDownloads(app)
            assertEquals(app.bookDownloads.entries.value.map { it.key }.toSet(), reloaded.entries.value.map { it.key }.toSet())
            device.takeScreenshot(File(app.filesDir, "v08-downloads.png"))
        }
        Unit
    }
}
