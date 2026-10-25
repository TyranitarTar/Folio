@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File

@RunWith(AndroidJUnit4::class)
class Interface014InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private fun click(text:String) { val node=device.wait(Until.findObject(if(text=="Reading")By.desc("Reading library") else By.text(text)),15000);assertNotNull("Missing $text",node);node.click();device.waitForIdle() }
    private fun guide(a:ReaderActivity)=(ReaderActivity::class.java.getDeclaredField("guided$"+"delegate").apply { isAccessible=true }.get(a) as androidx.compose.runtime.State<*>).value as GuidedReading
    private fun nav(a:ReaderActivity)=ReaderActivity::class.java.getDeclaredField("navigator").apply { isAccessible=true }.get(a) as EpubNavigatorFragment
    private fun sheet(a:ReaderActivity,name:String)=ReaderActivity::class.java.getDeclaredMethod("openSheet",String::class.java).apply { isAccessible=true }.invoke(a,name)
    private suspend fun waitFor(predicate:()->Boolean) { withTimeout(20000) { while(!predicate())delay(50) } }

    @Test fun cancelling_live_benchmark_stops_audio_output() = runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app)
        val audio=app.getSystemService(android.media.AudioManager::class.java)
        val trial=launch {
            LiveSpeechBenchmark.run(1,2,1f) { index ->
                if(index>0)delay(10000)
                AiAudioClip(FloatArray(120000){i->(kotlin.math.sin(i*2*Math.PI*220/24000)*.02).toFloat()})
            }
        }
        try {
            withTimeout(10000){while(!audio.isMusicActive)delay(50)}
            trial.cancelAndJoin()
            withTimeout(3000){while(audio.isMusicActive)delay(50)}
            assertFalse(audio.isMusicActive)
        } finally {trial.cancelAndJoin()}
        Unit
    }

    @Test fun live_benchmark_generates_during_real_playback_and_reports_starvation() = runBlocking {
        val result=LiveSpeechBenchmark.run(1,5,1.5f) { index ->
            delay(if(index%2==0)100 else 1200)
            AiAudioClip(FloatArray(12000){i->(kotlin.math.sin(i*2*Math.PI*220/24000)*.02).toFloat()})
        }
        assertEquals(5,result.getJSONArray("passages").length())
        assertEquals(1.5,result.getDouble("appliedPlaybackRate"),.001)
        assertTrue(result.toString(),result.getInt("generationOverlappedPlayback")>0)
        assertTrue(result.toString(),result.getInt("queueWaits")>0)
        assertTrue(result.getDouble("queueWaitSeconds")>0)
        File(app.filesDir,"v014-live-benchmark.json").writeText(result.toString(2))
        Unit
    }

    @Test fun audiobook_home_is_a_library_and_import_is_separate() {
        HomePreferences().save(app)
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Café")),15000))
            click("More")
            assertFalse(device.hasObject(By.text("Cat Café")))
            click("Audiobooks")
            assertTrue(device.wait(Until.hasObject(By.desc("Search library")),15000))
            for(label in libraryStatuses)assertTrue(device.hasObject(By.text(label)))
            assertFalse(device.hasObject(By.text("Import audio files")))
            device.wait(Until.findObject(By.desc("Add audiobooks")),15000).click()
            assertTrue(device.wait(Until.hasObject(By.text("Add audiobooks")),10000))
            assertTrue(device.hasObject(By.text("Import audio files")))
            assertTrue(device.hasObject(By.text("Add library root folder")))
            device.takeScreenshot(File(app.filesDir,"v014-audio-import.png"))
            device.pressBack();device.waitForIdle()
            device.takeScreenshot(File(app.filesDir,"v014-audio-library.png"))
            click("Reading")
            for(label in libraryStatuses)assertTrue(device.hasObject(By.text(label)))
        }
    }

    @Test fun guided_settings_pause_exact_word_and_actions_share_a_row() = runBlocking {
        SpeechController.stop(app);GuidedOptions().save(app)
        val book=app.library.importSample();val publication=app.engine.open(app.library.file(book.id))
        val text=try { BookText.load(publication,book.id) } finally { publication.close() }
        val point=text.sections.indices.firstNotNullOf { section -> text.cursor(section,0)?.takeIf { text.sections[section].words.size>20 } }
        app.library.progress(book.id,point.locator)
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),20000))
            lateinit var reading:GuidedReading
            scenario.onActivity { reading=guide(it);reading.prepare() };waitFor { reading.ready.value }
            scenario.onActivity { reading.start(point.locator,true) };waitFor { reading.running.value }
            scenario.onActivity { val position=reading.cursor.value;sheet(it,"settings");assertFalse(reading.running.value);assertEquals(position,reading.cursor.value) }
            scenario.onActivity { sheet(it,"guided") }
            val resume=device.wait(Until.findObject(By.text("Resume")),10000)
            val start=device.wait(Until.findObject(By.text("Start from here")),10000)
            assertNotNull(resume);assertNotNull(start)
            assertTrue(kotlin.math.abs(resume.visibleBounds.top-start.visibleBounds.top)<20)
            assertFalse(device.hasObject(By.text("Start countdown")))
            assertFalse(device.hasObject(By.text("Practice session")))
            device.takeScreenshot(File(app.filesDir,"v014-guided.png"))
            scenario.onActivity { reading.clear() }
        }
        Unit
    }

    @Test fun actual_readium_selection_copies_the_selected_passage() = runBlocking {
        val book=app.library.importSample();val publication=app.engine.open(app.library.file(book.id))
        val text=try { BookText.load(publication,book.id) } finally { publication.close() }
        val point=text.sections.indices.firstNotNullOf { section -> text.cursor(section,0)?.takeIf { text.sections[section].words.size>20 } }
        app.library.progress(book.id,point.locator)
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")),20000))
            val expected=CompletableDeferred<String>()
            scenario.onActivity { activity -> activity.lifecycleScope.launch {
                try {
                    var quote:String?=null
                    withTimeout(15000) { while(quote.isNullOrBlank()) {
                        nav(activity).evaluateJavascript("(function(){const p=Array.from(document.querySelectorAll('p')).find(n=>n.textContent.trim().length>30);if(!p)return false;const r=document.createRange();r.selectNodeContents(p);const s=getSelection();s.removeAllRanges();s.addRange(r);return true;})()")
                        quote=nav(activity).currentSelection()?.locator?.text?.highlight
                        if(quote.isNullOrBlank())delay(100)
                    } }
                    val callback=ReaderActivity::class.java.getDeclaredField("selectionMenu").apply { isAccessible=true }.get(activity) as ActionMode.Callback
                    val mode=activity.startActionMode(callback,ActionMode.TYPE_FLOATING)
                    checkNotNull(mode)
                    assertTrue(mode.menu.findItem(105).title.toString().contains("online"))
                    expected.complete(checkNotNull(quote))
                } catch(e:Throwable){expected.completeExceptionally(e)}
            } }
            val selected=withTimeout(20000){expected.await()}
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)copy"))),10000).click()
            var copied:String?=null
            waitFor { instrumentation.runOnMainSync { copied=(app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() };copied==selected }
            assertEquals(selected,copied)
        }
        Unit
    }
}
