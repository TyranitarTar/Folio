package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CafeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private var backup: String? = null
    @Before fun setup() = runBlocking {
        SpeechController.stop(app)
        app.cafe.load(); backup = app.cafe.export(); app.cafe.setEnabled(false); app.cafe.resetTest()
        Unit
    }
    @After fun cleanup() = runBlocking {
        backup?.let { app.cafeReading.restore(it) }; app.cafe.resetTest(); Unit
    }
    private fun find(text: String): UiObject2 {
        device.waitForIdle()
        fun placed(): UiObject2? = device.findObject(By.text(text))?.takeIf { item ->
            val bounds = item.visibleBounds
            val home = device.hasObject(By.text("Reading")) && device.hasObject(By.text("More"))
            text in listOf("More", "Books", "Café") || (!home || (bounds.top > 190 && bounds.bottom < device.displayHeight - 260)) && bounds.height() >= 24
        }
        device.wait(Until.hasObject(By.text(text)), 1500)
        placed()?.let { return it }
        repeat(18) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 55)
            Thread.sleep(500); device.waitForIdle()
            device.wait(Until.hasObject(By.text(text)), 300)
            placed()?.let { return it }
        }
        diagnostic("missing-control")
        fail("Missing café text: $text"); error("unreachable")
    }
    private fun diagnostic(label: String) {
        device.takeScreenshot(File(app.filesDir, "v012-$label.png"))
        device.dumpWindowHierarchy(File(app.filesDir, "v012-$label.xml"))
        File(app.filesDir, "v012-$label-state.json").writeText(app.cafe.profiles.value.test.json().toString(2))
    }
    private fun click(text: String) {
        if(text=="Reading") {
            device.wait(Until.findObject(By.desc("Reading library")),10000).click();device.waitForIdle();return
        }
        find(text); Thread.sleep(500)
        val target = device.findObject(By.text(text)) ?: find(text)
        val point = target.visibleBounds
        File(app.filesDir, "v012-clicks.txt").appendText("$text: $point\n")
        device.click(point.centerX(), point.centerY()); Thread.sleep(400); device.waitForIdle()
    }
    private fun top() { repeat(14) { device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 25) }; Thread.sleep(500); device.waitForIdle() }
    private fun tools() {
        top()
        val control=device.wait(Until.findObject(By.desc("Caféindstillinger og testtilstand")),10000)
        assertNotNull(control);control.click();device.waitForIdle()
    }
    private fun enterTest() {
        click("Café"); click("Prøv alt i testcaféen"); top()
        assertTrue(device.wait(Until.hasObject(By.text("TESTCAFÉ")), 5000))
    }
    @Test fun firstCatStoryTestModeAndFreeAnimationReplayUseIsolatedDurableState() = runBlocking {
        val baseline = app.cafe.export()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            enterTest()
            click("2 godbidder")
            val finished = device.wait(Until.hasObject(By.text("Tilbage til Miso")), 10000)
            if(!finished) diagnostic("animation-failed")
            assertTrue(finished)
            assertEquals(998L, app.cafe.profiles.value.test.balance)
            assertEquals(1L, app.cafe.profiles.value.test.redemptions)
            click("Se igen · gratis")
            device.takeScreenshot(File(app.filesDir, "v012-miso-paw.png"))
            if(device.hasObject(By.text("Spring over"))) click("Spring over") else click("Tilbage til Miso")
            assertEquals(998L, app.cafe.profiles.value.test.balance)
            assertEquals(baseline, app.cafe.export())
            tools(); click("Alt åbent"); click("Luk indstillinger")
            assertEquals(63, app.cafe.profiles.value.test.unlocked)
            assertTrue(app.cafe.openScene(true, 5))
            click("Fortsæt kapitlet")
            assertTrue(device.wait(Until.hasObject(By.textContains("Du bestemmer tempoet")), 5000))
            device.takeScreenshot(File(app.filesDir, "v012-miso-story.png"))
            repeat(3) { click("Videre") }
            click(MisoStory.chapters[5].choices[0].caption)
            click("Pause og gem")
            assertEquals(4, app.cafe.profiles.value.test.page)
            click("Fortsæt kapitlet")
            repeat(3) { click("Videre") }
            click("Afslut kapitlet")
            assertTrue(app.cafe.profiles.value.test.completed(5))
            assertEquals(baseline, app.cafe.export())
            top(); device.takeScreenshot(File(app.filesDir, "v012-cafe-room.png"))
            tools(); click("Nulstil kun testcaféen"); click("Nulstil test")
            assertEquals(0, app.cafe.profiles.value.test.read)
            assertEquals(1, app.cafe.profiles.value.test.unlocked)
            assertEquals(baseline, app.cafe.export())
        }
        Unit
    }
    @Test fun normalScenePauseRecreationAndCafeVisitKeepBookAndWallet() = runBlocking {
        app.cafe.setEnabled(true)
        val book = app.library.importSample()
        val original = app.library.books.value.first { it.id == book.id }
        val originalBalance = app.cafe.profiles.value.normal.balance
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            click("Café")
            device.takeScreenshot(File(app.filesDir, "v012-cafe-normal.png"))
            click("Læs kapitlet"); click("Videre"); click("Pause og gem")
            assertEquals(1, app.cafe.profiles.value.normal.page)
            scenario.recreate()
            click("Fortsæt kapitlet")
            assertTrue(device.wait(Until.hasObject(By.text(MisoStory.chapters[0].opening[1])), 5000))
            click("Pause og gem"); click("Reading")
            assertEquals(original, app.library.books.value.first { it.id == book.id })
            assertEquals(originalBalance, app.cafe.profiles.value.normal.balance)
        }
        Unit
    }
    @Test fun readingHeartbeatEarnsOneTreatAndCannotCountMenuOrInactiveTime() = runBlocking {
        app.cafe.setEnabled(true)
        val book = app.library.importSample()
        val before = app.cafe.profiles.value.normal.readingMillis
        val balance = app.cafe.profiles.value.normal.balance
        val samples = org.json.JSONArray()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertTrue(device.wait(Until.gone(By.desc("Save bookmark")), 5000))
            // The real foreground reader emits the heartbeat; no test-only reward hook.
            repeat(66) { second ->
                delay(1000)
                if(second % 5 == 0) scenario.onActivity { reader ->
                    fun field(name: String) = ReaderActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(reader)
                    fun state(name: String) = (field(name + '$' + "delegate") as androidx.compose.runtime.State<*>).value
                    samples.put(org.json.JSONObject().put("second", second).put("focused", reader.hasWindowFocus())
                        .put("controls", state("controls")).put("sheet", state("sheet") ?: org.json.JSONObject.NULL)
                        .put("navigatorReady", field("navigator") != null).put("enabled", app.cafe.profiles.value.normal.enabled)
                        .put("millis", app.cafe.profiles.value.normal.readingMillis))
                }
            }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 5000))
            delay(2500)
        }
        delay(2500)
        val after = app.cafe.profiles.value.normal
        File(app.filesDir, "v012-reading-reward.json").writeText(org.json.JSONObject().put("samples", samples).put("beforeMillis", before).put("afterMillis", after.readingMillis).put("balanceBefore", balance).put("balanceAfter", after.balance).toString(2))
        assertTrue("Real-time café reward was ${after.readingMillis - before}ms", after.readingMillis - before in 60_000..72_000)
        assertEquals(balance + (after.readingMillis / 60_000 - before / 60_000), after.balance)
        val paused = after.readingMillis
        delay(3000)
        assertEquals(paused, app.cafe.profiles.value.normal.readingMillis)
        Unit
    }
}
