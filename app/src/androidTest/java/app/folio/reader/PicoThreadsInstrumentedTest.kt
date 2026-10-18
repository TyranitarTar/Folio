package app.folio.reader

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uncached native work on the actual target device; fixed text, no personal library access. */
@RunWith(AndroidJUnit4::class)
class PicoThreadsInstrumentedTest {
    @Test fun compareOneThroughEightThreadsAndBoundedGroups()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
        assertTrue(app.packageName.endsWith(".debug"))
        assertFalse(SpeechController.serviceRunning)
        app.voicePacks.existingCheck.join()
        assertEquals("Ready",app.voicePacks.state("pico").phase)
        val settings=SpeechSettings.load(app)
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use { activity ->
            activity.onActivity{it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val report=PicoBenchmarks.run(app,progress={android.util.Log.i("FolioPicoBenchmark",it)})
            val trials=report.getJSONArray("trials")
            assertEquals(10,trials.length())
            val measured=(0 until trials.length()).map{trials.getJSONObject(it)}
            assertEquals(setOf(1,2,4,6,8),measured.map{it.getInt("threads")}.toSet())
            measured.forEach {
                assertTrue(it.getDouble("wallMillis")>0)
                assertTrue(it.getDouble("audioSeconds") in .2..20.0)
                assertTrue(it.getDouble("prefillMillis")>0)
                assertTrue(it.getDouble("decodeMillis")>0)
                assertTrue(it.getDouble("kanadeMillis")>0)
                assertTrue(it.getDouble("hiftMillis")>0)
            }
            assertEquals(6,report.getJSONArray("grouping").length())
            assertEquals(settings,SpeechSettings.load(app))
            File(app.filesDir,"pico-thread-benchmark.json").writeText(report.toString(2))
        }
    }
}
