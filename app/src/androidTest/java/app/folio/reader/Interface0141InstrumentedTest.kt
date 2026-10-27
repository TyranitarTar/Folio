@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class Interface0141InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private fun click(text:String) { val node=device.wait(Until.findObject(if(text=="Reading")By.desc("eBooks library") else By.text(text)),15000);assertNotNull(text,node);node.click();device.waitForIdle() }
    private suspend fun waitFor(test:()->Boolean) { withTimeout(30000){while(!test())delay(50)} }

    @Test fun audiobook_display_uses_the_shared_library_settings_and_persists() {
        SpeechController.stop(app);AudiobookController.stop(app)
        HomePreferences().save(app);HomePreferences().save(app,"audioHome")
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use { scenario ->
            click("Audiobooks")
            assertTrue(device.hasObject(By.desc("Search library")))
            device.findObject(By.desc("Library settings")).click()
            click("Display");click("List")
            assertEquals("List",HomePreferences.load(app,"audioHome").layout)
            device.pressBack();click("Reading")
            assertEquals("Comfortable",HomePreferences.load(app).layout)
            assertFalse(device.hasObject(By.textContains("Read + listen")))
            scenario.recreate()
            assertEquals("List",HomePreferences.load(app,"audioHome").layout)
        }
    }

    @Test fun leaving_fullscreen_reader_returns_directly_to_reading_library() = runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app)
        val book=app.library.importSample()
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            click("Reading")
            val reader=ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id))
            try {
                assertTrue(device.wait(Until.hasObject(By.desc("Library")),20000))
                // System Back must leave even when reader chrome is hidden.
                reader.onActivity { activity ->
                    val field=ReaderActivity::class.java.getDeclaredField("controls"+"$"+"delegate").apply{isAccessible=true}
                    @Suppress("UNCHECKED_CAST") val state=field.get(activity) as androidx.compose.runtime.MutableState<Boolean>
                    state.value=false
                }
                device.waitForIdle();device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.desc("Import EPUB")),15000))
                assertTrue(device.hasObject(By.text("Reading")))
            } finally {reader.close()}
        }
        Unit
    }

    @Test fun stable_page_callbacks_reuse_geometry_and_cached_reopen_retains_counts() = runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app)
        val book=app.library.importSample()
        val intent=Intent(app,ReaderActivity::class.java).putExtra("book",book.id)
        File(app.cacheDir,"pagination").listFiles().orEmpty().filter{it.name.startsWith(book.id+"-")}.forEach{it.delete()}
        lateinit var counter:PageCounter
        var coldCounts=emptyList<Int>()
        val coldStart=android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),20000))
            scenario.onActivity {counter=(ReaderActivity::class.java.getDeclaredField("counter"+"$"+"delegate").apply{isAccessible=true}.get(it) as androidx.compose.runtime.State<*>).value as PageCounter}
            waitFor {counter.countedPages().isNotEmpty() && counter.countedPages().all{it>0} && !counter.counting.value}
            delay(300)
            val before=counter.diagnostics.value.visibleVerifications
            scenario.onActivity { activity ->
                val nav=ReaderActivity::class.java.getDeclaredField("navigator").apply{isAccessible=true}.get(activity) as org.readium.r2.navigator.epub.EpubNavigatorFragment
                repeat(40){counter.pageChanged(0,nav.currentLocator.value)}
            }
            delay(150)
            assertEquals(before,counter.diagnostics.value.visibleVerifications)
            assertTrue(counter.diagnostics.value.visibleReuses>=40)
            coldCounts=counter.countedPages().toList()
            File(app.filesDir,"v0141-pagination.json").writeText(JSONObject().put("coldMillis",android.os.SystemClock.elapsedRealtime()-coldStart)
                .put("verifiedBefore",before).put("verifiedAfter",counter.diagnostics.value.visibleVerifications).put("callbacks",40).put("counts",org.json.JSONArray(coldCounts)).toString(2))
        }
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),20000))
            scenario.onActivity {counter=(ReaderActivity::class.java.getDeclaredField("counter"+"$"+"delegate").apply{isAccessible=true}.get(it) as androidx.compose.runtime.State<*>).value as PageCounter}
            waitFor {counter.countedPages().toList()==coldCounts}
            assertEquals(coldCounts,counter.countedPages().toList())
        }
        Unit
    }
}
