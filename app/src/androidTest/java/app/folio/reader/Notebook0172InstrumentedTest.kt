package app.folio.reader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Notebook0172InstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private suspend fun waitFor(test:()->Boolean)=withTimeout(15000){while(!test())delay(50)}
    private fun click(selector:BySelector) {
        var node=device.wait(Until.findObject(selector),10000) ?: error("Missing $selector")
        while(!node.isClickable && node.parent!=null)node=node.parent
        node.click();device.waitForIdle()
    }
    private fun editor():Activity? {
        var result:Activity?=null
        val read={result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull{it is BookNotebookActivity};Unit}
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())read() else instrumentation.runOnMainSync(read)
        return result
    }
    private fun caretVisible():Boolean {
        var visible=false
        instrumentation.runOnMainSync {
            fun inspect(view:android.view.View) {
                if(view is NotebookEditText && view.layout!=null) {
                    val line=view.layout.getLineForOffset(view.selectionEnd.coerceAtLeast(0))
                    val top=view.layout.getLineTop(line)-view.scrollY+view.totalPaddingTop
                    val bottom=view.layout.getLineBottom(line)-view.scrollY+view.totalPaddingTop
                    visible=view.height>0 && top>=0 && bottom<=view.height-view.totalPaddingBottom+2
                }
                if(view is android.view.ViewGroup)for(i in 0 until view.childCount)inspect(view.getChildAt(i))
            }
            editor()?.window?.decorView?.let(::inspect)
        }
        return visible
    }
    @Test fun oneTapNotesMigratesFocusesCopiesAutosavesAndRotatesWithoutSeeking()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        val legacy=AudioJournalStore.newNote(0,12000,"Original context").copy(text="En gammel dansk note 🐈")
        app.audiobooks.journal.saveNote(book.id,legacy)
        try {
            AudiobookController.start(app,book.id,0,15000,false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Play audiobook")),10000))
                click(By.desc("Player menu"));click(By.text("Notes"))
                var field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000);assertNotNull(field)
                assertTrue(field.text.contains(legacy.text));assertTrue(field.text.endsWith("\n- "))
                waitFor{editor()!=null}
                var ime=false
                repeat(30){instrumentation.runOnMainSync{ime=ViewCompat.getRootWindowInsets(editor()!!.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true};if(!ime)delay(100)}
                assertTrue("One Notes tap should open the keyboard",ime)
                val text=field.text+"Ny note: æøå 🐈\n- Another thought"
                field.text=text
                assertTrue(device.wait(Until.hasObject(By.text("Saved")),10000))
                click(By.text("Copy all"))
                var copied=""
                instrumentation.runOnMainSync{copied=(app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text.toString()}
                assertEquals(text,copied)
                click(By.desc("Undo note edit"));click(By.desc("Redo note edit"))
                assertEquals(text,device.findObject(By.clazz("android.widget.EditText")).text)
                val current=editor()!!;instrumentation.runOnMainSync{current.recreate()}
                waitFor{editor()!=null && editor()!==current}
                field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
                assertEquals(text,field.text)
                assertFalse(AudiobookController.state.value.playing)
                assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs-15000)<400)
                device.takeScreenshot(File(app.filesDir,"v0172-notebook.png"))
                // Rapid edit and immediate Back exercises the onStop flush rather than its debounce.
                field.text=text+"\n- Saved on leaving"
                click(By.desc("Back to player"))
                assertTrue(device.wait(Until.hasObject(By.desc("Player menu")),10000))
                assertTrue(app.audiobooks.notebooks.open(book).text.endsWith("Saved on leaving"))
                click(By.desc("Player menu"));click(By.text("Notes"))
                field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
                assertTrue(field.text.endsWith("Saved on leaving\n- "))
                click(By.desc("Back to player"))
            }
            val archive=File(app.filesDir,"audiobooks/${book.id}/notes-legacy.json")
            assertTrue(archive.isFile);assertTrue(app.audiobooks.journal.load(book.id).notes.isEmpty())
        }finally{
            editor()?.let{activity->instrumentation.runOnMainSync{activity.finish()}}
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }
    @Test fun latestNotesPersistWhenInstalledCachedOrCancelledWithoutOfferingDowngrade() {
        val version=app.packageManager.getPackageInfo(app.packageName,0).versionName!!
        val release=GitHubRelease(version,"v$version","https://github.com/TyranitarTar/Folio/releases/download/v$version/Folio-$version.apk",1000,"a".repeat(64),"## Notes\n- Current release remains visible.","2026-11-14T10:00:00Z")
        val model=AppUpdateModel(app)
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use{scenario->
            scenario.onActivity{model.recordRelease(release);it.setContent{FolioTheme{AppUpdatesSheet({},model)}}}
            assertTrue(device.wait(Until.hasObject(By.text("Latest published · Folio $version")),10000))
            assertTrue(device.hasObject(By.text("• Current release remains visible.")))
            assertTrue(device.hasObject(By.text("Check for updates")));assertNull(model.state.value.release)
            val cached=AppUpdateModel(app);assertEquals(release,cached.latestRelease.value)
            assertNull(cached.state.value.release)
            scenario.onActivity{model.cancel()}
            assertEquals(release,model.latestRelease.value)
            scenario.onActivity{model.recordRelease(release.copy(version="99.0.0",tag="v99.0.0"))}
            assertNotNull(model.state.value.release)
            scenario.onActivity{model.recordRelease(release)}
            device.takeScreenshot(File(app.filesDir,"v0172-update-notes.png"))
        }
        app.getSharedPreferences("app-release-notes",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun notebookOpeningAndBackgroundSaveLeavePlayingAudioRunning()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            AudiobookController.start(app,book.id,0,10000,true)
            waitFor{AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing}
            ActivityScenario.launch<BookNotebookActivity>(Intent(app,BookNotebookActivity::class.java).putExtra("audiobook",book.id)).use {
                val field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000);assertNotNull(field)
                waitFor{editor()!=null && device.findObject(By.clazz("android.widget.EditText"))?.isFocused==true}
                // Wait for the keyboard/input connection before sending an accessibility edit.
                var ime=false
                waitFor{instrumentation.runOnMainSync{ime=ViewCompat.getRootWindowInsets(editor()!!.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true};ime}
                val start=AudiobookController.state.value.positionMs
                field.text="- First";device.waitForIdle()
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_MOVE_END)
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
                device.executeShellCommand("input text Second");device.waitForIdle()
                assertEquals("- First\n- Second",device.findObject(By.clazz("android.widget.EditText")).text)
                field.text="- Audio stays playing"
                device.waitForIdle();assertEquals("- Audio stays playing",device.findObject(By.clazz("android.widget.EditText")).text)
                device.pressHome();delay(600)
                assertTrue(AudiobookController.state.value.playing)
                assertTrue(AudiobookController.state.value.positionMs>start)
                assertEquals("- Audio stays playing",app.audiobooks.notebooks.open(book).text)
            }
        }finally{
            AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }
    @Test fun maximumSizeNotebookAllowsDeletionAndKeepsTextAcrossLandscape()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor{!AudiobookController.serviceRunning}
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        val tail="\nTAIL_MARKER"
        val text=("- Long Danish note æøå\n".repeat(20000)).take(BookNotebookStore.MAX_CHARS-tail.length)+tail
        app.audiobooks.notebooks.open(book)
        app.audiobooks.notebooks.save(book.id,text,app.audiobooks.notebooks.nextOrder(book.id))
        fun current():TextFieldSnapshot {
            var value:TextFieldSnapshot?=null
            instrumentation.runOnMainSync{val model=ViewModelProvider(editor() as BookNotebookActivity)[BookNotebookModel::class.java];value=TextFieldSnapshot(model.value.text,model.value.selection.end)}
            return value!!
        }
        try {
            ActivityScenario.launch<BookNotebookActivity>(Intent(app,BookNotebookActivity::class.java).putExtra("audiobook",book.id)).use {
                assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),15000))
                waitFor{editor()!=null && current().text.length==text.length}
                assertEquals(text.length,current().selection)
                delay(400);device.pressKeyCode(android.view.KeyEvent.KEYCODE_DEL)
                waitFor{current().text.length==text.length-1}
                assertTrue(current().text.endsWith("TAIL_MARKE"))
                assertTrue(device.wait(Until.hasObject(By.text("Saved")),10000))
                waitFor{caretVisible()}
                device.takeScreenshot(File(app.filesDir,"v0172-long-notebook.png"))
                device.setOrientationLeft();delay(800)
                assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000))
                waitFor{editor()!=null}
                assertEquals(text.dropLast(1),current().text)
                waitFor{caretVisible()}
                device.takeScreenshot(File(app.filesDir,"v0172-notebook-landscape.png"))
                device.setOrientationNatural();delay(500)
                waitFor{editor()!=null};device.waitForIdle()
                click(By.desc("Back to player"))
            }
            assertEquals(text.dropLast(1),app.audiobooks.notebooks.open(book).text)
        } finally {
            device.setOrientationNatural();device.unfreezeRotation()
            editor()?.let{activity->instrumentation.runOnMainSync{activity.finish()}}
            app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }
    private data class TextFieldSnapshot(val text:String,val selection:Int)
}
