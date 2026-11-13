package app.folio.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Audio0171InstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private suspend fun waitFor(test:()->Boolean)=withTimeout(20000){while(!test())delay(40)}

    @Test fun player_has_notes_history_and_unlocked_chapter_controls_without_changing_paused_position()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun text(value:String) {
            val node=device.wait(Until.findObject(By.text(value)),8000)
            if(node==null) {device.dumpWindowHierarchy(File(app.filesDir,"v0171-ui-failure.xml"));device.takeScreenshot(File(app.filesDir,"v0171-ui-failure.png"))}
            assertNotNull(value,node)
            var target=node
            while(!target.isClickable && target.parent!=null)target=target.parent
            target.click();device.waitForIdle()
        }
        try {
            AudiobookController.start(app,book.id,0,15000,false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Play audiobook")),10000))
                assertFalse(device.hasObject(By.desc("Unlock chapter seeking")))
                repeat(4){
                    val notes=device.findObject(By.text("Notes"))
                    if(notes==null || notes.visibleBounds.height()<20)device.swipe(20,device.displayHeight*4/5,20,device.displayHeight/3,20)
                }
                device.waitForIdle();delay(1000)
                text("Notes");text("Write a note")
                val field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000);assertNotNull(field)
                field.text="Remember this passage"
                text("Save note")
                waitFor{app.audiobooks.journal.state.value[book.id]?.notes?.size==1}
                assertEquals(15000L,app.audiobooks.journal.state.value.getValue(book.id).notes.single().positionMs)
                assertFalse(AudiobookController.state.value.playing)
                assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs-15000)<300)
                device.takeScreenshot(File(app.filesDir,"v0171-notes.png"))
                device.pressBack();device.waitForIdle()
                val menu=device.wait(Until.findObject(By.desc("Player menu")),8000);assertNotNull(menu);menu.click()
                text("Listening history")
                assertTrue(device.wait(Until.hasObject(By.textContains("No listening sessions yet")),5000))
                device.takeScreenshot(File(app.filesDir,"v0171-history.png"))
            }
        } finally {
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }

    @Test fun durable_positions_use_observation_order_and_keep_time_until_decoded_duration_is_known()=runBlocking<Unit> {
        val input=Audio016InstrumentedTest().fixture();val store=app.audiobooks
        val book=store.importBundle(Uri.fromFile(input))
        try {
            val old=AudioCursorOrder.next();val latest=AudioCursorOrder.next()
            store.savePosition(book.id,0,45000,1.5f,1000,latest)
            store.savePosition(book.id,0,5000,1f,900000,old)
            assertEquals(45000L,store.books.value.first{it.id==book.id}.positionMs)
            store.savePosition(book.id,0,10000,2f,500)
            assertEquals(10000L,store.books.value.first{it.id==book.id}.positionMs)
            // Cached metadata is not permission to overwrite a verified saved cursor.
            store.savePosition(book.id,0,70000,2f)
            val reopened=AudiobookStore(app);reopened.load()
            assertEquals(70000L,reopened.books.value.first{it.id==book.id}.positionMs)
            AudiobookController.start(app,book.id,autoplay=false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            assertFalse(AudiobookController.state.value.playing)
            assertTrue(AudiobookController.state.value.positionMs in 59500..60500)
        } finally {
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            store.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }

    @Test fun notes_and_sessions_survive_restart_and_delayed_ticks_cannot_reopen_a_closed_session()=runBlocking<Unit> {
        val directory=File(app.cacheDir,"journal0171-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val id="a".repeat(64);File(directory,id).mkdirs()
        try {
            val store=AudioJournalStore(directory)
            val note=AudioJournalStore.newNote(1,12345,"A remembered passage").copy(text="My Danish note: æøå")
            store.saveNote(id,note)
            val clock=AudioSessionClock();clock.playing(0,1000,1f,10000,1000)
            val tick=clock.playing(1,2000,1.5f,11000,2000);val closed=clock.close()!!
            store.saveSession(id,closed);store.saveSession(id,tick)
            val reopened=AudioJournalStore(directory);val loaded=reopened.load(id)
            assertEquals(note,loaded.notes.single());assertTrue(loaded.sessions.single().closed)
            assertEquals(1000L,loaded.sessions.single().listenedMs)
            val stage=File(directory,"stage").apply{mkdirs()}
            store.stageMapped(id,stage){_,at->at+1000}
            val staged=AudioJournalStore.decode(org.json.JSONObject(File(stage,"journal.json").readText()))
            assertEquals(13345L,staged.notes.single().positionMs)
            assertEquals(3000L,staged.sessions.single().endMs)
            reopened.removeNote(id,note.id);assertTrue(AudioJournalStore(directory).load(id).notes.isEmpty())
            store.stageMapped(id,stage){_,_->throw IllegalArgumentException("No unique anchor")}
            val unmapped=AudioJournalStore.decode(org.json.JSONObject(File(stage,"journal.json").readText()))
            assertTrue(unmapped.sessions.single().originalPositions)
            assertEquals(2000L,unmapped.sessions.single().endMs)
            store.clearSessions(id);store.saveSession(id,tick)
            assertTrue(AudioJournalStore(directory).load(id).sessions.isEmpty())
            val later=closed.copy(id="later",startedAt=20000,endedAt=21000)
            val earlier=closed.copy(id="earlier",startedAt=10000,endedAt=11000)
            store.saveSession(id,later);store.saveSession(id,earlier)
            assertEquals(listOf("earlier","later"),store.load(id).sessions.map{it.id})
        } finally {directory.deleteRecursively()}
    }

    @Test fun paused_open_is_not_a_listening_session_and_seek_stop_restore_stays_paused()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            AudiobookController.start(app,book.id,0,10000,false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            assertTrue(app.audiobooks.journal.load(book.id).sessions.isEmpty())
            AudiobookController.play(app);waitFor{AudiobookController.state.value.playing};delay(1800)
            AudiobookController.pause(app);waitFor{!AudiobookController.state.value.playing}
            waitFor{app.audiobooks.journal.state.value[book.id]?.sessions?.lastOrNull()?.closed==true}
            val sessions=app.audiobooks.journal.load(book.id).sessions
            assertEquals(1,sessions.size);assertTrue(sessions.single().listenedMs in 900..4000)
            AudiobookController.seekTrack(app,1,35000)
            waitFor{AudiobookController.state.value.trackIndex==1 && !AudiobookController.state.value.loading}
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            AudiobookController.start(app,book.id,autoplay=false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            assertEquals(1,AudiobookController.state.value.trackIndex)
            assertTrue(AudiobookController.state.value.positionMs in 34500..35500)
            assertFalse(AudiobookController.state.value.playing)
        } finally {
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }

    @Test fun recreated_play_intent_never_restarts_a_paused_book()=runBlocking<Unit> {
        AudiobookController.stop(app)
        waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture()
        val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java)
                .putExtra("audiobook",book.id).putExtra("autoplay",true)).use { activity ->
                waitFor{AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing}
                AudiobookController.pause(app)
                waitFor{!AudiobookController.state.value.playing}
                val at=AudiobookController.state.value.positionMs
                activity.recreate()
                delay(1500)
                assertFalse("A consumed Play intent must not restart playback after recreation",AudiobookController.state.value.playing)
                assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs-at)<400)
            }
        } finally {
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }
}
