package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Plapre3PolicyTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private fun resource(name:String)=requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).bufferedReader().use{it.readText()}
    @Test fun dedicatedTokenizerMatchesReferenceAndHasBothTerminatingTokens() {
        val tokenizer=PicoTokenizer(resource("plapre3-tokenizer.json"))
        assertEquals(0,tokenizer.eos);assertEquals(21223,tokenizer.audioEnd)
        val cases=JSONArray(resource("plapre3-tokenizer-cases.json"))
        for(i in 0 until cases.length()) {
            val item=cases.getJSONObject(i);val expected=item.getJSONArray("tokens")
            assertArrayEquals(item.getString("text"),IntArray(expected.length()){expected.getInt(it)},tokenizer.encode(item.getString("text")))
        }
    }
    @Test fun modelVoiceAndThreadsHaveSeparateSettingsAndWaveformIdentity() {
        val selected=SpeechSettings(backend="plapre3",plapre3Voice=4,plapre3Threads=2,picoVoice=1,picoThreads=8,rate=1.5f)
        selected.save(app);assertEquals(selected,SpeechSettings.load(app))
        assertEquals(4,selected.localVoice);assertEquals(2,selected.inferenceThreads)
        assertTrue(selected.isLocal && selected.stretchesPlayback)
        assertTrue(selected.sameSource(selected.copy(rate=3f)))
        assertEquals(selected.audioKey("Hej."),selected.copy(rate=3f).audioKey("Hej."))
        assertNotEquals(selected.audioKey("Hej."),selected.copy(backend="pico").audioKey("Hej."))
        assertFalse(selected.sameSource(selected.copy(plapre3Threads=4)))
        assertFalse(selected.sameSource(selected.copy(plapre3Voice=0)))
        assertEquals(selected.audioKey("Hej."),selected.copy(picoVoice=2,picoThreads=1).audioKey("Hej."))
    }
    @Test fun largePackUsesLongSizesAndRemainsBoundedWithoutRelaxingOtherPackLimits() {
        val raw=app.assets.open("voice-packs.json").bufferedReader().use{it.readText()}
        val packs=VoicePackCatalog.parse(raw);val pack=packs.single{it.backend=="plapre3"}
        assertEquals(Plapre3Model.VERSION,pack.version)
        assertTrue(pack.files.any{it.bytes>1024L*1024*1024})
        assertTrue(pack.bytes+pack.expandedBytes>Int.MAX_VALUE.toLong())
        assertEquals(pack.files.sumOf{it.bytes},pack.expandedBytes)
        assertEquals(Plapre3PackIdentity.files,pack.files)
        fun rejects(backend:String,size:Long) {
            val j=JSONObject(raw);val a=j.getJSONArray("packs")
            val p=(0 until a.length()).map{a.getJSONObject(it)}.single{it.getString("backend")==backend}
            p.put("bytes",size)
            assertTrue(runCatching{VoicePackCatalog.parse(j.toString())}.isFailure)
        }
        rejects("plapre3",2L*1024*1024*1024)
        rejects("pico",513L*1024*1024)
    }
    @Test fun existingInstallationKeepsItsSelectedVoiceWhenNewPackAppears() {
        val old=SpeechSettings(backend="pico",picoVoice=3,picoThreads=2)
        old.save(app);assertEquals(old,SpeechSettings.load(app))
        assertEquals(0,SpeechSettings.load(app).plapre3Voice)
    }
    @Test fun resumeReservesOnlyRemainingDownloadAndRequiresMatchingIdentity() {
        val pack=VoicePackCatalog.load(app).single{it.backend=="plapre3"}
        val folder=File(app.cacheDir,"large-pack-space").apply{mkdirs()}
        val initial=pack.bytes+pack.expandedBytes+32L*1024*1024
        assertTrue(initial>Int.MAX_VALUE)
        assertEquals(initial,VoicePackInstallSpace.additionalBytes(pack,folder))
        File(folder,"archive.part").writeBytes(ByteArray(65536))
        val metadata=JSONObject().put("url",pack.url).put("sha256",pack.sha256)
        File(folder,"transfer.json").writeText(metadata.toString())
        assertEquals(initial-65536,VoicePackInstallSpace.additionalBytes(pack,folder))
        File(folder,"transfer.json").writeText(metadata.put("sha256","0".repeat(64)).toString())
        assertEquals(initial,VoicePackInstallSpace.additionalBytes(pack,folder))
    }
    @Test fun alreadyDownloadedArchiveReservesOnlyExpandedFilesAndSafetyMargin() {
        val pack=VoicePackCatalog.load(app).single{it.backend=="plapre3"}.copy(bytes=32)
        val folder=File(app.cacheDir,"complete-pack-space").apply{mkdirs()}
        File(folder,"archive.zip").writeBytes(ByteArray(32))
        assertEquals(pack.expandedBytes+32L*1024*1024,VoicePackInstallSpace.additionalBytes(pack,folder))
    }
}
