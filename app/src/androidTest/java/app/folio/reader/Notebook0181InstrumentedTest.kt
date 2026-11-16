package app.folio.reader

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Notebook0181InstrumentedTest {
    @Test fun longNotesScrollAndRestoreViewportWithChapterContext()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        val text=(1..160).joinToString("\n"){"- Earlier thought $it about this chapter and its characters."}
        app.audiobooks.notebooks.open(book)
        app.audiobooks.notebooks.save(book.id,text,app.audiobooks.notebooks.nextOrder(book.id))
        try {
            ActivityScenario.launch<BookNotebookActivity>(Intent(app,BookNotebookActivity::class.java).putExtra("audiobook",book.id)).use { scenario ->
                val field=device.wait(Until.findObject(By.clazz("android.widget.EditText")),15000)
                assertNotNull(field);assertTrue(field.text.contains("[C1 ·"))
                Thread.sleep(800)
                fun editView(activity:BookNotebookActivity):NotebookEditText {
                    fun visit(v:View):NotebookEditText? {
                        if(v is NotebookEditText)return v
                        if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))?.let{return it}
                        return null
                    }
                    return requireNotNull(visit(activity.window.decorView))
                }
                var initial=0
                scenario.onActivity { initial=editView(it).scrollY }
                assertTrue(initial>1000)
                val bounds=field.visibleBounds
                repeat(4){device.swipe(bounds.centerX(),bounds.top+bounds.height()/4,bounds.centerX(),bounds.top+bounds.height()*3/4,35)}
                Thread.sleep(600)
                var scrolled=0;scenario.onActivity { scrolled=editView(it).scrollY }
                assertTrue("Dragging should reach earlier notes: $initial -> $scrolled",scrolled<initial-300)
                Thread.sleep(1000);scenario.onActivity { assertEquals("Editor snapped back to caret",scrolled,editView(it).scrollY) }
                scenario.recreate();Thread.sleep(1200)
                scenario.onActivity { assertTrue("Recreation should retain scroll viewport",kotlin.math.abs(editView(it).scrollY-scrolled)<80) }
                device.takeScreenshot(File(app.filesDir,"v0181-notes-scrolled.png"))
            }
            assertEquals("Opening/scrolling must not save an empty timestamp entry",text,app.audiobooks.notebooks.open(book).text)
        } finally {app.audiobooks.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()}
    }
}
