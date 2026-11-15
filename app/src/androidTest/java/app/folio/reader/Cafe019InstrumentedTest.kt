package app.folio.reader

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Cafe019InstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private var original: Map<String, *> = emptyMap<String, Any?>()
    private var wallet = ""
    private val prefs get() = app.getSharedPreferences("cafe-room-v1", 0)
    @Before fun setup() {
        original = prefs.all.toMap()
        prefs.edit().clear().putString("mode", "manual").commit()
        runBlocking { app.cafe.load(); wallet = app.cafe.export() }
    }
    @After fun cleanup() {
        assertEquals("Room tests changed the existing café save", wallet, runBlocking { app.cafe.export() })
        prefs.edit().clear().also { edit -> original.forEach { (key, value) ->
            when(value) { is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value) }
        } }.commit()
    }
    private fun click(text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 10000)
        assertNotNull("Missing $text", target); target.click(); device.waitForIdle()
    }
    private fun web(scenario: ActivityScenario<MainActivity>): CafeRoomView {
        var result: CafeRoomView? = null
        fun visit(view: View) {
            if(view is CafeRoomView) result = view
            else if(view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
        }
        scenario.onActivity { visit(it.window.decorView) }
        return requireNotNull(result) { "No café WebView" }
    }
    private fun evaluate(view: CafeRoomView, js: String): String {
        val latch = CountDownLatch(1); var result = "null"
        instrumentation.runOnMainSync { view.evaluateJavascript(js) { result = it; latch.countDown() } }
        assertTrue("WebView JS timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun snapshot(view: CafeRoomView): JSONObject = JSONObject(evaluate(view, "window.FolioCafe.snapshot()"))
    private fun ready(scenario: ActivityScenario<MainActivity>): CafeRoomView {
        click("Café")
        val view = web(scenario)
        val end = System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {
            if(runCatching { snapshot(view).optBoolean("ready") }.getOrDefault(false)) {
                assertEquals("Loading overlay covers the room", "\"none\"", evaluate(view, "getComputedStyle(document.getElementById('loading')).display"))
                Thread.sleep(300)
                val pixels = JSONObject(evaluate(view, """(() => {
                    const c=document.getElementById('room'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
                    let different=0;for(let i=0;i<d.length;i+=400)if(d[i]!==242||d[i+1]!==235||d[i+2]!==223)different++;
                    return {width:c.width,height:c.height,different,rect:[c.getBoundingClientRect().width,c.getBoundingClientRect().height],active:FolioCafe.snapshot().active};
                })()"""))
                File(app.filesDir,"v019-render-diagnostics.json").writeText(pixels.toString())
                assertTrue("Blank room pixels: $pixels", pixels.getInt("different") > 100)
                return view
            }
            Thread.sleep(200)
        }
        error("Room did not become ready")
    }
    private fun finish(view: CafeRoomView, timeout: Long = 65000): JSONObject {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) {
            val state=snapshot(view)
            if(!state.optBoolean("busy")) return state
            Thread.sleep(250)
        }
        error("Room action timed out: ${snapshot(view)}")
    }
    private fun shot(label: String) { device.takeScreenshot(File(app.filesDir,"v019-$label.png")) }

    @Test fun realRoomPauseBackgroundResumeAndRemountHaveOneOwnedLoop() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            val view=ready(scenario)
            Thread.sleep(600); assertTrue(snapshot(view).getInt("frames")>0)
            shot("room")
            click("Pause"); Thread.sleep(300)
            val paused=snapshot(view); Thread.sleep(700)
            assertEquals(paused.getLong("frames"), snapshot(view).getLong("frames"))
            click("Fortsæt"); Thread.sleep(500)
            assertTrue(snapshot(view).getLong("frames")>paused.getLong("frames"))
            scenario.moveToState(Lifecycle.State.STARTED); Thread.sleep(500)
            val stopped=snapshot(view); Thread.sleep(700)
            assertFalse(stopped.getBoolean("active"))
            assertEquals(stopped.getLong("frames"),snapshot(view).getLong("frames"))
            scenario.moveToState(Lifecycle.State.RESUMED); Thread.sleep(700)
            assertTrue(snapshot(view).getLong("frames")>stopped.getLong("frames"))
            repeat(3) {
                click("More"); assertFalse(device.hasObject(By.desc("Misos kattecafé · interaktivt værelse")))
                val next=ready(scenario); assertNotSame(view,next)
            }
            scenario.recreate(); Thread.sleep(800)
            assertTrue(snapshot(web(scenario)).getBoolean("ready"))
        }
    }

    @Test fun actionMenuRoutinesAndRoomSettingsDoNotSpendExistingTreats() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            val view=ready(scenario)
            click("Handlinger"); shot("actions"); click("Spis · forfra")
            assertEquals("eat",snapshot(view).getString("action"))
            val done=finish(view)
            assertEquals(1,done.getJSONObject("counts").getInt("eat"))
            assertEquals(1,CafeRoomPreferences(app).counts().getInt("eat"))
            click("Adfærd: Du bestemmer"); click("En rolig morgen")
            assertEquals("morning",snapshot(view).getString("mode"))
            assertEquals("morning",CafeRoomPreferences(app).mode)
            val settings=device.wait(Until.findObject(By.desc("Værelsesindstillinger")),5000); settings.click()
            click("Tempo: Gang"); click("Trav"); click("Luk indstillinger")
            assertEquals("trot",CafeRoomPreferences(app).pace)
            click("Adfærd: En rolig morgen"); click("Du bestemmer")
            scenario.recreate(); Thread.sleep(1000)
            val reopened=web(scenario)
            assertEquals(1,snapshot(reopened).getJSONObject("counts").getInt("eat"))
            assertEquals("manual",snapshot(reopened).getString("mode"))
            shot("persisted")
        }
    }

    @Test fun elevatedNapWakesAndReturnsAlongSafeSupportGraphInRealWebView() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            val view=ready(scenario)
            evaluate(view,"window.FolioCafe.configure({pace:'sprint'})")
            evaluate(view,"window.FolioCafe.command('bookshelf_sleep')")
            val nap=finish(view)
            assertEquals("bookshelf",nap.getJSONObject("actor").getString("support"))
            assertEquals("sleep",nap.getJSONObject("actor").getString("resting"))
            shot("bookshelf-nap")
            evaluate(view,"window.FolioCafe.command('down')")
            val floor=finish(view)
            assertEquals("floor",floor.getJSONObject("actor").getString("support"))
            assertEquals(0.0,floor.getJSONObject("actor").getDouble("z"),.001)
            assertTrue(floor.getJSONObject("actor").isNull("resting"))
            evaluate(view,"window.FolioCafe.command('tunnel')")
            assertEquals(1,finish(view).getJSONObject("counts").getInt("tunnel"))
            shot("tunnel-exit")
        }
    }

    @Test fun landscapeKeepsRoomAndScrollableControlsAccessible() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            ready(scenario)
            try {
                device.setOrientationLeft(); Thread.sleep(1400)
                ready(scenario) // Rotation recreates the WebView; wait for actual painted pixels.
                Thread.sleep(300)
                shot("landscape")
                // Lower controls scroll when landscape leaves less height below the room.
                var control=device.findObject(By.text("Handlinger"))
                if(control == null) {
                    device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,30)
                    control=device.wait(Until.findObject(By.text("Handlinger")),5000)
                }
                assertNotNull(control); control.click()
                assertTrue(device.wait(Until.hasObject(By.text("Lidt tid med Miso")),5000))
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
        }
    }
}
