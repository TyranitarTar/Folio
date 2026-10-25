package app.folio.reader

import android.content.Intent
import android.provider.DocumentsContract
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

/** Uses only the existing emulator's authored SAF acceptance tree and added test folder. */
@RunWith(AndroidJUnit4::class)
class FolderLibrary0141InstrumentedTest {
    @Test fun root_scan_links_without_copy_and_rescan_preserves_live_playback_and_cursor() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as FolioApp
        val device=UiDevice.getInstance(instrumentation)
        SpeechController.stop(app);AudiobookController.stop(app)
        val store=app.audiobooks
        val sources=AudiobookSources(app,store)
        val root=checkNotNull(sources.roots().firstOrNull{DocumentsContract.getTreeDocumentId(it).endsWith("Folio014-acceptance")}) { "Prepare the authored SAF tree first." }
        val began=android.os.SystemClock.elapsedRealtime()
        val result=sources.refresh(root){}
        val elapsed=android.os.SystemClock.elapsedRealtime()-began
        val book=store.books.value.first{it.title=="BookFast0141"}
        try {
            assertEquals(root.toString(),book.sourceRoot);assertEquals(2,book.tracks.size)
            assertTrue(book.tracks.all{it.sourceUri!=null && it.durationMs==0L})
            val privateFiles=File(app.filesDir,"audiobooks/${book.id}").listFiles().orEmpty()
            assertTrue(privateFiles.none{it.name.startsWith("track-")})
            store.savePosition(book.id,1,200,1.5f)
            val bookmark=store.addBookmark(book.id,1,150,"Folder point")
            val fresh=AudiobookStore(app);fresh.load()
            assertEquals(200L,fresh.books.value.first{it.id==book.id}.positionMs)
            AudiobookController.start(app,book.id,autoplay=false)
            withTimeout(15000){while(AudiobookController.state.value.id!=book.id || AudiobookController.state.value.loading)delay(40)}
            assertNull(AudiobookController.state.value.error)
            assertTrue(AudiobookController.state.value.durationMs>0)
            val recheckStart=android.os.SystemClock.elapsedRealtime()
            val recheck=sources.refresh(root){}
            val recheckMs=android.os.SystemClock.elapsedRealtime()-recheckStart
            assertTrue(recheck.contains("unchanged"))
            val current=store.books.value.first{it.id==book.id}
            assertEquals(200L,current.positionMs);assertEquals(bookmark,current.bookmarks.single())
            store.prepareLinkedMetadata()
            withTimeout(15000){while(!store.books.value.first{it.id==book.id}.durationKnown)delay(40)}
            assertTrue(File(app.filesDir,"audiobooks/${book.id}").listFiles().orEmpty().none{it.name.startsWith("track-")})
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).putExtra("librarySection","Audiobooks")).use {
                val refresh=device.wait(Until.findObject(By.desc("Rescan audiobook folders")),15000)
                assertNotNull(refresh);refresh.click()
                assertTrue(device.wait(Until.hasObject(By.textContains("unchanged")),15000))
                device.takeScreenshot(File(app.filesDir,"v0141-root-scan.png"))
            }
            File(app.filesDir,"v0141-root-scan.json").writeText(JSONObject().put("passed",true).put("initialMillis",elapsed)
                .put("rescanMillis",recheckMs).put("initialResult",result).put("rescanResult",recheck)
                .put("linkedTracks",2).put("copiedAudioBytes",0).put("decoderDurationMs",AudiobookController.state.value.durationMs)
                .put("cursorAndBookmarkRetained",true).put("root",root.toString()).toString(2))
        } finally {
            AudiobookController.stop(app)
            withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(40)}
            store.remove(book.id)
            // Removing a linked library entry must leave the original audio intact.
            assertNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(book.tracks.first().sourceUri))?.use{it.read()})
        }
        Unit
    }
}
