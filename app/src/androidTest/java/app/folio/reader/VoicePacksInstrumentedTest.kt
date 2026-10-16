package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoicePacksInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private suspend fun waitFor(limit:Long=180000,check:()->Boolean)=withTimeout(limit){while(!check())delay(100)}
    @Test fun thinApkReusesPreparedVoicesAndUiExposesAuthenticSamples()=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("thinVoicePacks")=="true")
        app.voicePacks.existingCheck.join()
        for(pack in app.voicePacks.catalog) {
            assertEquals("Ready",app.voicePacks.state(pack.id).phase)
            assertTrue(app.voicePacks.verify(app.voicePacks.directory(pack),pack))
            val asset=when(pack.backend){"pico" -> "pico/pico.onnx"; "kokoro" -> "kokoro/model.onnx"; else -> "supertonic/voice.bin"}
            assertTrue("Development upgrade must be thin",runCatching { app.assets.open(asset).close() }.isFailure)
        }
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("More")),10000));device.findObject(By.text("More")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Voice packs")),10000));device.findObject(By.text("Voice packs")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Preview sample")),10000))
            assertNotNull(device.findObject(By.text("Supertonic 3")))
            device.pressBack()
        }
        Unit
    }
    @Test fun predownloadSampleDoesNotLoadModelsOrChangeVoiceOrBookCheckpoint()=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("networkVoiceSamples")=="true")
        SpeechController.stop(app);waitFor { !SpeechController.serviceRunning }
        app.voicePacks.existingCheck.join()
        val before=SpeechSettings.load(app)
        val book=app.library.importSample();val listening=AiListeningStore(app).read(book.id)
        val cursor=ReaderCursorStore(app).read(book.id,"speech")
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            for(pack in app.voicePacks.catalog) {
                val voice=pack.voices.first();val state=app.voicePacks.state(pack.id)
                withContext(Dispatchers.Main){app.voiceSamples.play(pack,voice)}
                waitFor(60000){app.voiceSamples.state.value.phase=="Playing" || app.voiceSamples.state.value.error!=null}
                assertNull(app.voiceSamples.state.value.error)
                assertFalse(SpeechController.serviceRunning)
                assertEquals(before,SpeechSettings.load(app));assertEquals(listening,AiListeningStore(app).read(book.id))
                assertEquals(cursor,ReaderCursorStore(app).read(book.id,"speech"));assertEquals(state,app.voicePacks.state(pack.id))
                withContext(Dispatchers.Main){app.voiceSamples.stop()}
                assertEquals("Stopped",app.voiceSamples.state.value.phase)
            }
        }
        Unit
    }
    /** Explicit real network acceptance; skipped from an ordinary offline test run. */
    @Test fun actualPublicPacksCancelResumeVerifyInstallAndSynthesize()=runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("downloadVoicePacks")=="true")
        SpeechController.stop(app);waitFor { !SpeechController.serviceRunning };app.voicePacks.existingCheck.join()
        val report=JSONArray()
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            for(pack in app.voicePacks.catalog.filter { InstrumentationRegistry.getArguments().getString("packBackend")?.let { selected -> it.backend==selected } ?: true }) {
                app.voicePacks.remove(pack.id)
                // A missing model does not prevent comparison through the small recorded sample.
                withContext(Dispatchers.Main){app.voiceSamples.play(pack,pack.voices.first())}
                waitFor(60000){app.voiceSamples.state.value.phase=="Playing" || app.voiceSamples.state.value.error!=null}
                assertNull(app.voiceSamples.state.value.error)
                assertFalse(app.voicePacks.directory(pack).exists())
                withContext(Dispatchers.Main){app.voiceSamples.stop()}
                app.voicePacks.enqueue(pack.id)
                waitFor { app.voicePacks.state(pack.id).bytes>1_000_000 || app.voicePacks.state(pack.id).phase=="Failed" }
                app.voicePacks.cancelDownload(pack.id);assertEquals("Cancelled",app.voicePacks.state(pack.id).phase)
                delay(1200);assertEquals("Cancelled",app.voicePacks.state(pack.id).phase)
                val partial=File(app.voicePacks.transferFolder(pack),"archive.part").length()
                assertTrue(partial>0);app.voicePacks.enqueue(pack.id)
                waitFor(360000){app.voicePacks.state(pack.id).phase in setOf("Ready","Failed")}
                val state=app.voicePacks.state(pack.id);assertEquals(state.message,"Ready",state.phase)
                assertTrue(app.voicePacks.verify(app.voicePacks.directory(pack),pack))
                val engine=createLocalSpeechEngine(app,pack.backend)
                try {
                    val clip=engine.generate(if(pack.backend=="pico") "Denne stemme virker uden internet." else "This downloaded voice works offline.",SpeechSettings(backend=pack.backend))
                    assertTrue(clip.samples.size>clip.sampleRate)
                    report.put(JSONObject().put("pack",pack.id).put("resumedPartialBytes",partial).put("filesVerified",pack.files.size)
                        .put("nativeSamples",clip.samples.size).put("digest",clip.digest))
                } finally { engine.close() }
                File(app.filesDir,"v9-voice-download-validation.json").writeText(report.toString(2))
            }
        }
        Unit
    }
    @Test fun kokoroNativeHighSpeedsProduceBoundedFullSentenceAudio()=runBlocking {
        app.voicePacks.existingCheck.join()
        val engine=KokoroEngine(app)
        val report=JSONArray()
        try {
            val text="She closed the book and remembered the final words: quiet, last, stop, and good night."
            val normal=engine.generate(text,21,1f)
            for(rate in listOf(2.5f,3f)) {
                val fast=engine.generate(text,21,rate)
                assertTrue(fast.samples.size>fast.sampleRate)
                assertTrue(fast.samples.size<normal.samples.size)
                assertTrue(fast.samples.all { it.isFinite() })
                report.put(JSONObject().put("rate",rate.toDouble()).put("normalSamples",normal.samples.size).put("fastSamples",fast.samples.size)
                    .put("digest",fast.digest).put("acousticEndingsVerified",false))
            }
        } finally { engine.close() }
        File(app.filesDir,"v9-kokoro-fast-validation.json").writeText(report.toString(2))
        Unit
    }
}
