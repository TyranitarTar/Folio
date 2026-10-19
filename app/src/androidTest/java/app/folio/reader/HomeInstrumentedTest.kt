package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun click(text: String) {
        // The expanded More screen can put settings below the initial viewport.
        if (text == "Dark") {
            for (attempt in 0 until 8) {
                val bounds = device.findObject(By.text(text))?.visibleBounds
                if (bounds != null && bounds.height() > 24 && bounds.top > 190 && bounds.bottom < device.displayHeight - 260) break
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 45)
                device.waitForIdle()
            }
        }
        repeat(3) { attempt ->
            device.waitForIdle()
            val item = device.wait(Until.findObject(By.text(text)), 15000)
            assertNotNull("Missing $text", item)
            try { item.click(); device.waitForIdle(); return }
            catch (stale: StaleObjectException) { if (attempt == 2) throw stale }
        }
    }
    private fun description(text: String) { val item = device.wait(Until.findObject(By.desc(text)), 15000); assertNotNull("Missing $text", item); item.click(); device.waitForIdle() }
    @After fun cleanup() { HomePreferences().save(app) }
    @Test fun libraryDetailsHistoryBrowseAndDisplayPreferencesSurviveRecreation() {
        HomePreferences().save(app)
        val book = runBlocking { app.library.importSample() }
        // Keep this row visible even when other acceptance tests left newer books.
        runBlocking { app.library.update(book.id) { it.copy(lastRead = System.currentTimeMillis(), progress = .8) } }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("All books")), 15000))
            description("Library settings"); click("Display"); click("List")
            assertEquals("List", HomePreferences.load(app).layout)
            device.pressBack(); scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("80% read")), 15000))
            click("The Quiet Hour")
            assertTrue(device.wait(Until.hasObject(By.text("Book details")), 15000))
            assertTrue(device.wait(Until.hasObject(By.text("Welcome")), 15000))
            assertTrue(device.wait(Until.hasObject(By.text("Resume reading")), 15000))
            device.takeScreenshot(File(app.filesDir, "home-v5-details.png"))
            description("Back to library"); click("More"); click("Reading history")
            device.takeScreenshot(File(app.filesDir, "v013-history-navigation.png"))
            device.dumpWindowHierarchy(File(app.filesDir, "v013-history-navigation.xml"))
            assertTrue(device.wait(Until.hasObject(By.text("The Quiet Hour")), 15000))
            click("Browse"); click("Local"); assertTrue(device.wait(Until.hasObject(By.text("Local EPUB files")), 15000))
            click("More"); assertTrue(device.wait(Until.hasObject(By.text("Reader settings")), 15000))
            click("Dark"); assertEquals("Dark", HomePreferences.load(app).theme)
            // More retains its scroll position, with theme choices still in view.
            scenario.recreate(); assertTrue(device.wait(Until.hasObject(By.text("Dark")), 15000))
            assertEquals("Dark", HomePreferences.load(app).theme)
            click("Books")
            device.takeScreenshot(File(app.filesDir, "home-v5-library.png"))
        }
    }
    @Test fun newShelfAssignmentPersistsAndSearchCannotResetItsBooks() {
        HomePreferences().save(app)
        val book = runBlocking { app.library.importSample() }
        runBlocking { app.library.assignShelves(setOf(book.id), emptyList()); app.library.deleteShelf("Favourites") }
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            description("Library menu"); click("Manage shelves"); click("New shelf")
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!
            input.text = "Favourites"; click("Create")
            assertTrue(HomePreferences.load(app).shelves.contains("Favourites"))
            click("Close")
            val cover = device.wait(Until.findObject(By.desc("Cover of The Quiet Hour")), 15000)!!
            val point = cover.visibleCenter
            device.swipe(point.x, point.y, point.x, point.y, 150)
            description("Assign shelf"); click("Favourites"); click("Apply")
            runBlocking { withTimeout(5000) { while (app.library.books.value.first { it.id == book.id }.shelves != listOf("Favourites")) delay(50) } }
            assertEquals(listOf("Favourites"), app.library.books.value.first { it.id == book.id }.shelves)
            click("Favourites"); scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.desc("Cover of The Quiet Hour")), 15000))
            description("Search library")
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!.text = "no-such-book"
            assertTrue(device.wait(Until.hasObject(By.text("No books match")), 15000))
            description("Clear search")
            assertTrue(device.wait(Until.hasObject(By.desc("Cover of The Quiet Hour")), 15000))
            assertEquals(listOf("Favourites"), app.library.books.value.first { it.id == book.id }.shelves)
            device.takeScreenshot(File(app.filesDir, "home-v5-shelf.png"))
        }
    }
}
