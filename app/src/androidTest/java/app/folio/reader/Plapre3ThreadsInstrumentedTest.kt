package app.folio.reader

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Plapre3ThreadsInstrumentedTest {
    @Test fun selectedModelBenchmarksAllSupportedThreadCountsWithoutChangingSettings()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
        assertTrue(app.packageName.endsWith(".debug"))
        assertFalse(SpeechController.serviceRunning)
        app.voicePacks.existingCheck.join()
        assertEquals("Ready",app.voicePacks.state("plapre3").phase)
        val before=SpeechSettings.load(app)
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use { activity ->
            activity.onActivity{it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val report=PicoBenchmarks.run(app,rounds=1,groups=false,backend="plapre3")
            assertEquals("plapre3",report.getString("backend"))
            val trials=report.getJSONArray("trials")
            assertEquals(5,trials.length())
            assertEquals(setOf(1,2,4,6,8),(0 until trials.length()).map{trials.getJSONObject(it).getInt("threads")}.toSet())
            for(i in 0 until trials.length()) {
                val trial=trials.getJSONObject(i)
                assertTrue(trial.getDouble("wallMillis")>0)
                assertTrue(trial.getDouble("audioSeconds") in .2..20.0)
                assertTrue(trial.getDouble("decodeMillis")>0)
                assertTrue(trial.getDouble("hiftMillis")>0)
            }
            assertEquals(before,SpeechSettings.load(app))
            File(app.filesDir,"plapre3-thread-benchmark.json").writeText(report.toString(2))
        }
    }
}
