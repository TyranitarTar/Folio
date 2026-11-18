package app.folio.reader

import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CafePrototypeInstrumentedTest {
    @Test fun engineCanCloseAndReopenWithoutEndingFolioOrItsPlayingAudiobook() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FolioApp
        val device = UiDevice.getInstance(instrumentation)
        val process = app.packageName+":cafePrototype"
        suspend fun waitFor(test:()->Boolean) = withTimeout(35000) { while(!test()) delay(150) }
        fun pid(name:String) = device.executeShellCommand("pidof $name").trim()
        fun launch() {
            app.startActivity(Intent().setClassName(app.packageName,"app.folio.reader.CafePrototypeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        AudiobookController.stop(app)
        waitFor { !AudiobookController.serviceRunning }
        val input = Audio016InstrumentedTest().fixture()
        val book = app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                val folioPid = pid(app.packageName)
                AudiobookController.start(app,book.id,0,10000,true)
                waitFor { AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing }
                val start = AudiobookController.state.value.positionMs
                val pids = mutableSetOf<String>()
                repeat(2) { attempt ->
                    device.executeShellCommand("logcat -c")
                    launch()
                    waitFor {
                        device.executeShellCommand("logcat -d -s godot:I FolioCafePrototype:I").contains("FOLIO_CAFE_FIRST_ACTION")
                    }
                    val enginePid = pid(process)
                    assertTrue(enginePid.isNotEmpty());assertNotEquals(folioPid,enginePid)
                    assertTrue("A fresh entry should create a new engine process",pids.add(enginePid))
                    assertTrue(AudiobookController.state.value.playing)
                    val screenshot = File(app.filesDir,"godot-cafe-embedded-$attempt.png")
                    device.takeScreenshot(screenshot)
                    val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
                    val colors = mutableSetOf<Int>()
                    for(y in bitmap.height/5 until bitmap.height*4/5 step 15)
                        for(x in bitmap.width/8 until bitmap.width*7/8 step 15) colors.add(bitmap.getPixel(x,y))
                    bitmap.recycle()
                    assertTrue("Godot logged ready but rendered a blank room",colors.size>80)
                    val logs = device.executeShellCommand("logcat -d -s godot:I FolioCafePrototype:I")
                    File(app.filesDir,"godot-cafe-embedded-$attempt.log").writeText(logs)
                    assertFalse(logs.contains("SCRIPT ERROR"))
                    // First verify background cleanup, then verify explicit Android Back.
                    if(attempt==0) device.pressHome() else device.pressBack()
                    waitFor { pid(process).isEmpty() }
                    assertEquals("Engine exit killed Folio",folioPid,pid(app.packageName))
                    assertTrue("Engine exit stopped narration",AudiobookController.state.value.playing)
                    app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    delay(400)
                }
                assertTrue(AudiobookController.state.value.positionMs>start)
            }
        } finally {
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(400)
            AudiobookController.stop(app)
            waitFor { !AudiobookController.serviceRunning }
            app.audiobooks.remove(book.id)
            book.bookId?.let { app.library.removeBook(it) }
            input.delete()
        }
    }
}
