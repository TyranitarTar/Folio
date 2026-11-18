package app.folio.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CafeGodotInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(instrumentation)
    private val store=CafeGodotState(app)
    private val process get()=app.packageName+":cafe"
    private var original:ByteArray?=null
    private var originalPrefs:Map<String,* > = emptyMap<String,Any?>()
    private var wallet=""
    private var originalWallet=""
    private var phase="setup"
    private var lastStatus="{}"
    private val prefs get()=app.getSharedPreferences("cafe-room-v1",0)
    private fun pid(name:String)=device.executeShellCommand("pidof $name").trim()
    private suspend fun waitFor(timeout:Long=35000,test:suspend ()->Boolean) {
        try { withTimeout(timeout) { while(!test()) delay(150) } }
        catch(error:kotlinx.coroutines.TimeoutCancellationException) {
            device.takeScreenshot(File(app.filesDir,"v019-timeout.png"))
            File(app.filesDir,"v019-timeout-state.txt").writeText("$phase\n$lastStatus")
            throw AssertionError("Timed out in $phase; latest=$lastStatus",error)
        }
    }
    private fun library() { app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }

    @Before fun setup()=runBlocking<Unit> {
        check(app.packageName.endsWith(".debug")) { "Disposable fixtures require the debug application." }
        library();waitFor { pid(process).isEmpty() }
        original=store.file.takeIf { it.exists() }?.readBytes()
        originalPrefs=prefs.all.toMap()
        prefs.edit().clear().putString("mode","manual").putString("pace","sprint")
            .putString("counts","""{"sleep":2}""").putBoolean("ambient",true).commit()
        store.file.delete();File(store.file.path+".bak").delete();File(store.file.path+".new").delete()
        app.cafe.load();originalWallet=app.cafeReading.export()
        app.cafe.setEnabled(false) // Fixture narration must not earn real legacy-cafe rewards.
        app.cafeReading.restore(app.cafe.export());wallet=app.cafe.export()
    }
    @After fun cleanup()=runBlocking<Unit> {
        phase="cleanup";library();waitFor { pid(process).isEmpty() }
        try { assertEquals("Godot room changed previous wallet/story data",wallet,app.cafe.export()) }
        finally {
            app.cafeReading.restore(originalWallet)
            original?.let { store.file.writeBytes(it) } ?: store.file.delete()
            prefs.edit().clear().also { editor -> originalPrefs.forEach { (key,value) -> when(value) {
                is String -> editor.putString(key,value);is Boolean -> editor.putBoolean(key,value)
                is Long -> editor.putLong(key,value);is Int -> editor.putInt(key,value)
            } } }.commit()
            device.setOrientationNatural()
        }
    }

    private suspend fun state(command:JSONObject?=null):JSONObject {
        val result=CompletableDeferred<String>()
        val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { result.complete(resultData ?: "{}") } }
        val intent=Intent(app.packageName+".CAFE_DEBUG").setPackage(app.packageName)
        command?.let { intent.putExtra("command",it.toString()) }
        app.sendOrderedBroadcast(intent,null,receiver,Handler(Looper.getMainLooper()),Activity.RESULT_OK,"{}",null)
        lastStatus=withTimeout(5000) { result.await() };return JSONObject(lastStatus)
    }
    private suspend fun ready():JSONObject {
        var result=JSONObject()
        phase="engine ready";waitFor { result=state();result.optBoolean("ready") }
        assertTrue(result.getBoolean("keepAwake"))
        delay(600) // Surface submission and loading-overlay removal follow the engine callback.
        return result
    }
    private suspend fun enter():JSONObject {
        val tab=device.wait(Until.findObject(By.desc("Café tab")),15000)
        assertNotNull("Folio Café navigation",tab);tab.click()
        return ready()
    }
    private fun shot(label:String) {
        val file=File(app.filesDir,"v019-$label.png")
        assertTrue(device.takeScreenshot(file))
        val image=BitmapFactory.decodeFile(file.absolutePath)
        val colors=mutableSetOf<Int>()
        for(y in image.height/5 until image.height*4/5 step 15)
            for(x in image.width/8 until image.width*7/8 step 15) colors.add(image.getPixel(x,y))
        image.recycle();assertTrue("Engine reported ready but room pixels are blank",colors.size>80)
    }
    private suspend fun action(id:String):JSONObject {
        phase="activity $id"
        val before=state().optJSONObject("counts")?.optInt(id,0) ?: 0
        state(JSONObject().put("action",id))
        var result=JSONObject()
        waitFor(90000) { result=state();!result.optBoolean("busy") && result.optJSONObject("counts")?.optInt(id,0)?.let{it>before}==true }
        return result
    }

    @Test fun navigationMigrationMenusScrollAndRestartPreservePreferencesAndDiscoveries()=runBlocking<Unit> {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            val first=enter();val firstPid=pid(process)
            assertEquals("manual",first.getString("mode"));assertEquals("sprint",first.getString("pace"))
            assertEquals(2,first.getJSONObject("counts").getInt("sleep"))
            shot("native-room")
            val x=(device.displayWidth*(if(device.displayWidth>device.displayHeight).1 else .2)).toInt()
            device.click(x,device.displayHeight-180)
            phase="open activities by touch";waitFor { state().optString("menu")=="activities" }
            val time=state().getDouble("time");delay(700);assertEquals(time,state().getDouble("time"),.001)
            val h=device.displayHeight
            device.swipe(device.displayWidth/2,(h*.73).toInt(),device.displayWidth/2,(h*.53).toInt(),30)
            phase="scroll activities by touch";waitFor { state().optInt("menu_scroll")>0 }
            shot("native-actions-scrolled")
            phase="Back closes menu";device.pressBack();waitFor { state().optString("menu")=="" }
            assertEquals(firstPid,pid(process))
            phase="idle camera settles";waitFor { state().optInt("fps_limit")==8 }
            state(JSONObject().put("settings",JSONObject().put("mode","manual").put("pace","trot").put("density",96).put("ambient",false)))
            phase="apply preferences";waitFor { val value=state();value.optInt("density")==96 && value.optString("pace")=="trot" }
            phase="Back destroys engine";device.pressBack();waitFor { pid(process).isEmpty() }
            enter();assertNotEquals(firstPid,pid(process))
            val reopened=state();assertEquals(96,reopened.getInt("density"));assertEquals("trot",reopened.getString("pace"))
            assertFalse(reopened.getBoolean("ambient"));assertEquals(2,reopened.getJSONObject("counts").getInt("sleep"))
            assertEquals("floor",reopened.getString("support"));assertEquals(0.0,reopened.getDouble("z"),.001)
        }
    }

    @Test fun allNativeActivitiesRenderAndCompleteWithSafeSupportsAndNoWalletCost()=runBlocking<Unit> {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            enter()
            val results=JSONArray()
            for(id in cafeRoomActions.keys) {
                val done=action(id)
                assertEquals("manual",done.getString("mode"))
                results.put(done)
                if(id in setOf("perch_sleep","bookshelf_sleep","box","tunnel","eat_far","groom","window"))shot(id)
            }
            File(app.filesDir,"v019-native-actions.json").writeText(results.toString(2))
            state(JSONObject().put("action","down"))
            waitFor { val value=state();!value.optBoolean("busy") && value.optString("support")=="floor" }
            val saved=store.load().getJSONObject("counts")
            cafeRoomActions.keys.forEach { assertTrue("Discovery missing: $it",saved.optInt(it)>0) }
        }
    }

    @Test fun backgroundMidJumpReleasesEngineWithoutEndingNarrationOrRestoringAnAirborneCat()=runBlocking<Unit> {
        AudiobookController.stop(app);waitFor { !AudiobookController.serviceRunning }
        val input=Audio016InstrumentedTest().fixture();val book=app.audiobooks.importBundle(Uri.fromFile(input))
        try {
            ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
                val mainPid=pid(app.packageName)
                AudiobookController.start(app,book.id,0,10000,true)
                waitFor { AudiobookController.state.value.id==book.id && AudiobookController.state.value.playing }
                val start=AudiobookController.state.value.positionMs
                enter();val firstPid=pid(process)
                state(JSONObject().put("action","bookshelf_sleep"))
                waitFor { state().optString("step_type")=="jump" }
                device.pressHome();waitFor { pid(process).isEmpty() }
                assertTrue(AudiobookController.state.value.playing)
                assertEquals(mainPid,pid(app.packageName))
                library();enter();assertNotEquals(firstPid,pid(process))
                val value=state();assertEquals("floor",value.getString("support"));assertEquals(0.0,value.getDouble("z"),.001)
                assertEquals(0,value.getJSONObject("counts").optInt("bookshelf_sleep"))
                assertTrue(AudiobookController.state.value.positionMs>start)
                assertTrue(AudiobookController.state.value.playing)
            }
        } finally {
            library();waitFor { pid(process).isEmpty() }
            AudiobookController.stop(app);waitFor { !AudiobookController.serviceRunning }
            app.audiobooks.remove(book.id);book.bookId?.let { app.library.removeBook(it) };input.delete()
        }
    }

    @Test fun landscapeAndReducedMotionRemainUsableAndFirstAutonomousActionStartsPromptly()=runBlocking<Unit> {
        prefs.edit().putString("mode","random").commit()
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            enter();waitFor { state().optDouble("first_action_seconds",-1.0)>0 }
            assertTrue(state().getDouble("first_action_seconds")<1.6)
            state(JSONObject().put("action","home"))
            val enginePid=pid(process)
            device.setOrientationLeft()
            waitFor { val view=state().optJSONArray("viewport");view!=null && view.optDouble(0)>view.optDouble(1) }
            assertEquals(enginePid,pid(process))
            state(JSONObject().put("menu","room"));shot("landscape-menu")
            state(JSONObject().put("menu","close").put("settings",JSONObject().put("mode","manual").put("reduced",true).put("ambient",false)))
            waitFor { state().optInt("fps_limit")==2 }
            state(JSONObject().put("paused",true))
            val time=state().getDouble("time");delay(1000);assertEquals(time,state().getDouble("time"),.001)
            device.setOrientationNatural()
        }
    }
}
