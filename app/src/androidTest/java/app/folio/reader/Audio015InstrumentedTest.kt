package app.folio.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class Audio015InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private suspend fun waitFor(condition:()->Boolean)=withTimeout(20000){while(!condition())delay(40)}
    @Test fun update_actions_and_progress_remain_above_long_release_notes() {
        val model=AppUpdateModel(app)
        val release=GitHubRelease("0.16.0","v0.16.0","https://github.com/TyranitarTar/Folio/releases/download/v0.16.0/Folio-0.16.0.apk",1_000_000,"a".repeat(64),"Long release notes. ".repeat(500))
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use { scenario ->
            scenario.onActivity{it.setContent{FolioTheme{AppUpdatesSheet({},model)}}}
            val cases=listOf(UpdateState() to "Check for updates",UpdateState(release=release) to "Download 0.16.0 · 1 MB",
                UpdateState(busy=true,downloading=true,progress=.25f,release=release) to "Downloading…",
                UpdateState(busy=true,release=release) to "Verifying…",UpdateState(ready=true,release=release) to "Install update")
            val tops=mutableListOf<Int>()
            cases.forEach{(state,label)->scenario.onActivity{model.state.value=state}
                val button=device.wait(Until.findObject(By.text(label)),10000);assertNotNull(label,button)
                device.waitForIdle();android.os.SystemClock.sleep(450)
                assertTrue(label,button.visibleBounds.top<device.displayHeight/2);tops+=button.visibleBounds.top
            }
            assertTrue(tops.toString(),tops.max()-tops.min()<20)
            File(app.filesDir,"v015-update-controls.json").writeText(JSONObject().put("passed",true).put("buttonTopPositions",org.json.JSONArray(tops)).put("releaseNotesCharacters",release.notes.length).toString(2))
            device.takeScreenshot(File(app.filesDir,"v015-update-controls.png"))
        }
    }
    @Test fun real_m4b_chapters_artwork_paused_seeks_history_and_auto_rewind()=runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=File(app.cacheDir,"chapters-015.m4b")
        instrumentation.context.assets.open("chapters-015.m4b").use{source->input.outputStream().use{source.copyTo(it)}}
        val store=app.audiobooks;val book=store.importAudio(listOf(Uri.fromFile(input)))
        val original=AudioPreferences.load(app)
        try {
            assertEquals(3,book.tracks.single().chapters.size)
            store.prepareTrack(book.id,0)
            var fresh=store.books.value.first{it.id==book.id}
            assertNotNull(store.artworkFile(fresh))
            store.savePosition(book.id,0,7500,1f);val mark=store.addBookmark(book.id,0,7800,"Keep this sentence")
            AudiobookController.start(app,book.id,autoplay=false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            assertNull(AudiobookController.state.value.error);assertFalse(AudiobookController.state.value.playing)
            AudiobookController.next(app);waitFor{!AudiobookController.state.value.loading && kotlin.math.abs(AudiobookController.state.value.positionMs-12000)<300}
            assertFalse(AudiobookController.state.value.playing)
            AudiobookController.previous(app);waitFor{kotlin.math.abs(AudiobookController.state.value.positionMs-6000)<300}
            assertFalse(AudiobookController.state.value.playing)
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Book position")),15000))
                val chapters=device.wait(Until.findObject(By.text("Chapters & position")),10000)
                assertNotNull(chapters);chapters.click()
                assertTrue(device.wait(Until.hasObject(By.text("Chapters")),10000))
                device.pressBack();device.waitForIdle()
                assertTrue(device.hasObject(By.desc("Player menu")))
                device.takeScreenshot(File(app.filesDir,"v015-m4b-player.png"))
            }
            AudioPreferences(backSeconds=5,forwardSeconds=5,autoRewind=true,rewindSeconds=3,afterMinutes=1).save(app)
            AudiobookController.forward(app);waitFor{kotlin.math.abs(AudiobookController.state.value.positionMs-11000)<300}
            assertFalse(AudiobookController.state.value.playing)
            AudiobookController.rewind(app);waitFor{kotlin.math.abs(AudiobookController.state.value.positionMs-6000)<300}
            assertFalse(AudiobookController.state.value.playing)
            AudiobookController.seek(app,9000);waitFor{kotlin.math.abs(AudiobookController.state.value.positionMs-9000)<300}
            app.getSharedPreferences("audiobook-pauses",0).edit().putLong(book.id,System.currentTimeMillis()-120000).commit()
            AudiobookController.play(app);waitFor{AudiobookController.state.value.playing}
            assertTrue(AudiobookController.state.value.positionMs in 5700..8000)
            AudiobookController.pause(app);waitFor{!AudiobookController.state.value.playing}
            waitFor{store.books.value.first{it.id==book.id}.history.isNotEmpty()}
            fresh=store.books.value.first{it.id==book.id};assertEquals(mark,fresh.bookmarks.single())
            val reloaded=AudiobookStore(app);reloaded.load();val saved=reloaded.books.value.first{it.id==book.id}
            assertEquals(3,saved.tracks.single().chapters.size);assertEquals(mark,saved.bookmarks.single());assertTrue(saved.history.isNotEmpty())
            File(app.filesDir,"v015-m4b.json").writeText(JSONObject().put("passed",true).put("chapterStarts",org.json.JSONArray(saved.tracks.single().chapters.map{it.startMs}))
                .put("artworkCached",true).put("pausedNavigationPreserved",true).put("historyPersisted",true).put("bookmarkPreserved",true).put("autoRewindPassed",true).toString(2))
        } finally {
            original.save(app);AudiobookController.stop(app);waitFor{AudiobookController.state.value.id==null};store.remove(book.id);input.delete()
        }
    }
    @Test fun both_libraries_swipe_status_and_about_contains_version_history()=runBlocking {
        HomePreferences().save(app);HomePreferences().save(app,"audioHome")
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.desc("eBooks library")),15000))
            for((navigation,pref) in listOf("eBooks library" to "home","Audiobooks tab" to "audioHome")) {
                device.findObject(By.desc(navigation)).click();device.waitForIdle()
                for(expected in listOf("New","Reading","Finished")) {
                    device.swipe(device.displayWidth*9/10,device.displayHeight/2,device.displayWidth/10,device.displayHeight/2,30)
                    waitFor{HomePreferences.load(app,pref).status==expected}
                }
                assertTrue(device.hasObject(By.desc("Library menu")))
            }
            device.takeScreenshot(File(app.filesDir,"v015-library.png"))
            device.findObject(By.desc("More tab")).click()
            device.waitForIdle()
            for(attempt in 0 until 8) {
                val bounds=device.findObject(By.text("About Folio"))?.visibleBounds
                if(bounds!=null && bounds.height()>24 && bounds.top>190 && bounds.bottom<device.displayHeight-200)break
                device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,45)
                device.waitForIdle()
            }
            android.os.SystemClock.sleep(700)
            val about=device.wait(Until.findObject(By.text("About Folio")),10000)
            assertNotNull(about);assertFalse(device.hasObject(By.text("Version history")));about.click()
            val history=device.wait(Until.findObject(By.text("Version history")),10000);assertNotNull(history);history.click()
            assertTrue(device.wait(Until.hasObject(By.text("Version history")),10000))
            device.pressBack();assertTrue(device.wait(Until.hasObject(By.text("About Folio")),10000))
        }
    }
}
