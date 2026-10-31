package app.folio.reader

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Audio0161InstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private suspend fun waitFor(label:String="playback state",test:()->Boolean) {
        try { withTimeout(20000){while(!test())delay(50)} }
        catch(e:TimeoutCancellationException){throw AssertionError("$label: ${AudiobookController.state.value}",e)}
    }
    private fun text(label:String){val node=device.wait(Until.findObject(By.text(label)),10000);assertNotNull(label,node);node.click();device.waitForIdle()}
    private fun click(label:String){val node=device.wait(Until.findObject(By.desc(label)),10000);assertNotNull(label,node);node.click();device.waitForIdle()}
    private suspend fun fixture(body:suspend (Audiobook,ActivityScenario<AudiobookActivity>)->Unit) {
        SpeechController.stop(app);AudiobookController.stop(app)
        val input=Audio016InstrumentedTest().fixture(gaps=true)
        val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            AudiobookController.start(app,book.id,0,10000,false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use { body(book,it) }
        } finally {
            AudiobookController.stop(app);waitFor{AudiobookController.state.value.id==null}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }

    @Test fun lock_survives_rotation_speed_requires_apply_and_transport_is_larger()=runBlocking {
        fixture { _,scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Play audiobook")),15000))
            val play=device.findObject(By.desc("Play audiobook"))
            val skip=device.findObject(By.desc("Back 60 seconds"))
            val density=app.resources.displayMetrics.density
            assertTrue(play.visibleBounds.height()>=80*density);assertTrue(skip.visibleBounds.height()>=76*density)
            text("1.0× speed");text("2.0×");assertEquals(1f,AudiobookController.state.value.speed)
            text("Cancel");assertEquals(1f,AudiobookController.state.value.speed)
            text("1.0× speed");text("1.5×");text("Apply speed");waitFor{AudiobookController.state.value.speed==1.5f}
            assertTrue(device.findObject(By.desc("Play audiobook")).isEnabled)
            click("Forward 10 seconds");waitFor{AudiobookController.state.value.positionMs in 19800..20200}
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.desc("Unlock chapter seeking")),10000))
            assertTrue(device.findObject(By.desc("Play audiobook")).isEnabled)
            click("Unlock chapter seeking")
            assertTrue(device.wait(Until.hasObject(By.desc("Lock chapter seeking")),5000))
            click("Lock chapter seeking")
            click("Player menu");text("Current passage settings")
            assertTrue(device.wait(Until.hasObject(By.desc("Passage timing offset")),5000));text("Later 0.1 s");text("Cancel")
            repeat(4){if(!device.hasObject(By.text("Current passage"))) device.swipe(device.displayWidth/2,device.displayHeight*4/5,device.displayWidth/2,device.displayHeight/3,25)}
            text("Current passage");assertTrue(device.wait(Until.hasObject(By.desc("Passage timing offset")),5000));text("Reset");text("Cancel")
            device.takeScreenshot(File(app.filesDir,"v0161-player.png"))
        }
    }

    @Test fun passage_offset_survives_catalog_reload_and_reimport()=runBlocking {
        val input=Audio016InstrumentedTest().fixture(gaps=true)
        val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            assertEquals(0L,book.passageOffsetMs)
            app.audiobooks.setPassageOffset(book.id,2500L)
            val fresh=AudiobookStore(app);fresh.load()
            assertEquals(2500L,fresh.books.value.first{it.id==book.id}.passageOffsetMs)
            assertEquals(2500L,app.audiobooks.importBundle(Uri.fromFile(input)).passageOffsetMs)
            app.audiobooks.savePosition(book.id,0,10000,1f)
            val saved=app.audiobooks.books.value.first{it.id==book.id}
            assertEquals(10000L,saved.positionMs);assertEquals(2500L,saved.passageOffsetMs)
        } finally { app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete() }
    }

    @Test fun retained_gap_passage_can_be_saved_with_audio_and_screen_awake_respects_lifecycle()=runBlocking {
        val appearance=Appearance.load(app)
        appearance.withAwake(ReaderAwakePolicy("timed",10)).save(app)
        try { fixture { book,scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("First test passage.")),15000))
            AudiobookController.seek(app,32000);waitFor{AudiobookController.state.value.positionMs in 31800..32200}
            assertNull(AudiobookController.state.value.cue)
            assertTrue(device.hasObject(By.text("First test passage.")))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("First test passage.")),10000))
            click("Player menu");text("Save passage as quote")
            waitFor{app.library.books.value.first{it.id==book.bookId}.highlights.isNotEmpty()}
            val mark=app.library.books.value.first{it.id==book.bookId}.highlights.single()
            assertEquals("First test passage.",mark.quote);assertEquals(book.id,mark.audioId)
            assertEquals(0,mark.audioTrack);assertEquals(0L,mark.audioPositionMs)
            click("Player menu");text("Saved highlights & quotes")
            assertTrue(device.wait(Until.hasObject(By.text("Open audio")),10000))
            device.takeScreenshot(File(app.filesDir,"v0161-quotes.png"));device.pressBack()
            delay(1200)
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.onActivity { assertEquals(0,it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            AudiobookController.seek(app,36000);waitFor{AudiobookController.state.value.cue?.text=="Second test passage."}
            assertTrue(device.wait(Until.hasObject(By.text("Second test passage.")),5000))
            AudiobookController.play(app);waitFor{AudiobookController.state.value.playing}
            click("Player menu");text("Saved highlights & quotes");text("Open text")
            waitFor{!AudiobookController.state.value.playing}
            assertTrue(device.wait(Until.hasObject(By.desc("Listen from here")),20000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("eBooks library")),10000))
        } } finally { appearance.save(app) }
    }

    @Test fun passage_and_chapter_stops_prevent_advance_and_timer_can_extend()=runBlocking {
        fixture { _,_ ->
            AudiobookController.seek(app,29000);waitFor{AudiobookController.state.value.positionMs in 28800..29200}
            AudiobookController.sleepBoundary(app,true);waitFor{AudiobookController.state.value.sleepLabel!=null}
            AudiobookController.play(app);waitFor{AudiobookController.state.value.playing}
            waitFor{!AudiobookController.state.value.playing && AudiobookController.state.value.sleepLabel==null}
            assertTrue(AudiobookController.state.value.positionMs in 29800..30300)
            AudiobookController.seek(app,59000);waitFor{AudiobookController.state.value.positionMs in 58800..59200}
            AudiobookController.sleepBoundary(app,false);waitFor{AudiobookController.state.value.sleepLabel!=null}
            AudiobookController.play(app);waitFor{AudiobookController.state.value.playing}
            waitFor{!AudiobookController.state.value.playing && AudiobookController.state.value.sleepLabel==null}
            assertEquals(0,AudiobookController.state.value.trackIndex)
            AudiobookController.play(app);waitFor{AudiobookController.state.value.trackIndex==1 && AudiobookController.state.value.playing}
            AudiobookController.pause(app);waitFor{!AudiobookController.state.value.playing}
            AudiobookController.sleep(app,5,true);waitFor{AudiobookController.state.value.sleepRemainingSeconds!=null}
            assertTrue(AudiobookController.state.value.sleepLabel!!.contains("fade"))
            AudiobookController.extendSleep(app);waitFor{(AudiobookController.state.value.sleepRemainingSeconds ?: 0)>590}
            AudiobookController.clearSleep(app);waitFor{AudiobookController.state.value.sleepRemainingSeconds==null}
            assertNull(AudiobookController.state.value.sleepLabel)
        }
    }
}
