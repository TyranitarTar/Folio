package app.folio.reader

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise actual Material progress semantics while nullable update state advances. */
@RunWith(AndroidJUnit4::class)
class AppUpdatesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    @Test fun progressTransitionsToVerificationAndReadyWithoutCrashing() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.wait(Until.findObject(By.text("More")), 15000).click()
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("App updates")
            device.wait(Until.findObject(By.text("App updates")), 10000).click()
            assertTrue(device.wait(Until.hasObject(By.text("Check for updates")), 10000))
            lateinit var model: AppUpdateModel
            scenario.onActivity { model = ViewModelProvider(it)[AppUpdateModel::class.java] }
            try {
                withContext(Dispatchers.Main) {
                    repeat(120) { index ->
                        model.state.value = UpdateState("Downloading APK…", busy = true, downloading = true, progress = (index % 100) / 100f)
                        delay(20)
                        model.state.value = UpdateState("Verifying APK…", busy = true)
                        delay(20)
                    }
                    // This test supplies UI states only; real verification is tested through the release updater.
                    val release = GitHubRelease("0.8.2", "v0.8.2", "https://github.com/TyranitarTar/Folio/releases/download/v0.8.2/Folio-0.8.2.apk", 100, "0".repeat(64), "")
                    model.state.value = UpdateState("Verified update ready. Android will ask before installing.", release = release, ready = true)
                }
                assertTrue(device.wait(Until.hasObject(By.text("Install update")), 10000))
                assertTrue(device.hasObject(By.text("Verified update ready. Android will ask before installing.")))
            } finally { withContext(Dispatchers.Main) { model.cancel() } }
        }
        Unit
    }
}
