package app.folio.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Harness runs setup, adb force-stop, then verify in a fresh process. */
@RunWith(AndroidJUnit4::class)
class Audio0171ProcessDeathInstrumentedTest {
    @Test fun abrupt_termination_preserves_the_periodic_checkpoint_without_autoplay()=runBlocking<Unit> {
        val phase=InstrumentationRegistry.getArguments().getString("phase")
        assumeTrue(phase in listOf("setup","verify"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
        val receipt=File(app.cacheDir,"audio0171-process-death.json")
        suspend fun waitFor(test:()->Boolean)=withTimeout(20000){while(!test())delay(40)}
        if(phase=="setup") {
            val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
            app.audiobooks.notebooks.open(book)
            app.audiobooks.notebooks.save(book.id,"- Notebook survives process loss: æøå 🐈",app.audiobooks.notebooks.nextOrder(book.id))
            app.audiobooks.setPlaybackSpeed(book.id,2f)
            AudiobookController.start(app,book.id,1,10000,true)
            waitFor{AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing}
            delay(6200)
            receipt.writeText(JSONObject().put("id",book.id).put("bookId",book.bookId).put("input",input.absolutePath)
                .put("position",AudiobookController.state.value.positionMs).toString())
            return@runBlocking
        }
        val row=JSONObject(receipt.readText());val id=row.getString("id")
        try {
            app.audiobooks.load();val saved=app.audiobooks.books.value.first{it.id==id}
            assertEquals("- Notebook survives process loss: æøå 🐈",app.audiobooks.notebooks.open(saved).text)
            assertEquals(1,saved.positionTrack);assertEquals(2f,saved.speed)
            assertTrue("Crash loss must stay within ~2 seconds of wall time at 2x",saved.positionMs in (row.getLong("position")-5500)..(row.getLong("position")+1000))
            assertFalse(AudiobookController.state.value.playing)
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",id)).use {
                waitFor{AudiobookController.state.value.id==id && !AudiobookController.state.value.loading}
                assertFalse(AudiobookController.state.value.playing)
                assertTrue(kotlin.math.abs(saved.positionMs-AudiobookController.state.value.positionMs)<500)
            }
        } finally {
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(id);app.library.removeBook(row.getString("bookId"))
            File(row.getString("input")).delete();receipt.delete()
        }
    }
}
