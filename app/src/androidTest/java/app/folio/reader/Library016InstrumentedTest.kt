package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Library016InstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun click(desc:String){val node=device.wait(Until.findObject(By.desc(desc)),10000);assertNotNull(desc,node);node.click();device.waitForIdle()}
    @Test fun librariesHaveTopAlignedGridsAndFullWidthMenus()=runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app)
        val old=HomePreferences.load(app);val oldAudio=HomePreferences.load(app,"audioHome")
        val book=app.library.importSample()
        val audio=app.audiobooks.linkAudio(android.net.Uri.parse("content://folio.fixture/tree/root"),"016-ui","v1","Library 016 fixture",listOf("Track" to android.net.Uri.parse("content://folio.fixture/tree/root/document/root%2Ftrack.mp3")))
        try {
            val tops=JSONObject()
            for(columns in 2..4) {
                HomePreferences(columns=columns).save(app);HomePreferences(columns=columns).save(app,"audioHome")
                ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                    for((tab,action) in listOf("eBooks library" to "Read ${book.title}","Audiobooks tab" to "Listen ${audio.title}")) {
                        click(tab)
                        val tile=device.wait(Until.findObject(By.desc(action)),10000);assertNotNull(action,tile)
                        val filter=device.findObject(By.text("All"));assertNotNull(filter)
                        val gap=tile.visibleBounds.top-filter.visibleBounds.bottom
                        assertTrue("$tab $columns columns: gap $gap",gap in 0..180)
                        tops.put("$tab-$columns",gap)
                        device.takeScreenshot(File(app.filesDir,"v016-library-${if(tab.startsWith("eBooks"))"ebooks" else "audio"}-$columns.png"))
                    }
                }
            }
            HomePreferences(layout="List").save(app,"audioHome")
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                click("Audiobooks tab")
                var play=device.wait(Until.findObject(By.desc("Listen ${audio.title}")),10000);assertNotNull(play)
                while(play!=null && !play.isClickable)play=play.parent
                assertNotNull(play);assertTrue("Larger play button",play.visibleBounds.width()>=56*app.resources.displayMetrics.density-4)
                val title=device.findObject(By.text(audio.title));assertNotNull(title);title.longClick();device.waitForIdle()
                val label=device.wait(Until.findObject(By.text("Mark finished")),10000);assertNotNull(label)
                var row=label
                while(row!=null && !row.isClickable)row=row.parent
                assertNotNull(row);assertTrue(row.visibleBounds.width()>device.displayWidth*.75)
                device.click(row.visibleBounds.right-24,row.visibleBounds.centerY())
                withTimeout(5000){while(app.audiobooks.books.value.first{it.id==audio.id}.isRead.not())delay(25)}
                click("Library menu");assertFalse(device.hasObject(By.text("Library settings")));device.pressBack()
                click("More tab")
                assertFalse(device.hasObject(By.text("Library display")));assertFalse(device.hasObject(By.text("Shelves")))
                assertFalse(device.hasObject(By.text("Open sample book")))
                assertTrue(device.hasObject(By.text("Bluetooth & headset")))
                click("Library menu");assertFalse(device.hasObject(By.text("Manage shelves")));device.pressBack()
            }
            File(app.filesDir,"v016-library.json").writeText(JSONObject().put("passed",true).put("gridGapPixels",tops).put("fullWidthMenu",true).put("largeListPlay",true).toString(2))
        } finally {
            old.save(app);oldAudio.save(app,"audioHome");app.audiobooks.remove(audio.id)
            // The built-in authored sample may pre-exist in this emulator; keep it.
        }
    }
}
