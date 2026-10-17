package app.folio.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import java.util.Collections
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PicoInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private suspend fun installFixture() {
        app.voicePacks.existingCheck.join()
        val pack = app.voicePacks.forBackend("pico")!!
        if (app.voicePacks.state(pack.id).phase != "Ready") {
            val archive = File(app.cacheDir, "pico-input.zip")
            check(archive.isFile) { "Push the checksummed Pico archive into the debug app cache before running this test." }
            app.voicePacks.update(VoicePackState(pack.id, "Installing"), force=true)
            app.voicePacks.install(pack, archive) { _, _ -> }
        }
        assertTrue(app.voicePacks.verify(app.voicePacks.directory(pack), pack))
    }
    @Test fun nativeWaveformCancellationAndCheckpointRecovery() = runBlocking {
        installFixture()
        val engine = PicoEngine(app)
        val selected = SpeechSettings(backend="pico")
        try {
            val text = "Hun lukker bogen, tager sin jakke og går stille ud i den klare morgen."
            val clip = engine.generate(text, selected)
            assertEquals(24000, clip.sampleRate)
            assertTrue(clip.samples.size in 24000..480000)
            assertEquals(SpeechPcm.digest(clip.samples,24000),clip.digest)
            wav(File(app.filesDir,"pico-native-da.wav"),clip)
            val key = selected.audioKey(text)
            val store = AiListeningStore(app)
            store.saveClip(key,clip)
            val book = "f".repeat(64)
            val point = AiListeningPoint(2,17,key,clip.samples.size/2,clip.digest)
            store.write(book,point)
            val fast = selected.copy(rate=3f)
            assertEquals(key, fast.audioKey(text))
            assertEquals(point,AiListeningStore(app).read(book))
            assertArrayEquals(clip.samples,requireNotNull(AiListeningStore(app).loadClip(key)).samples,0f)
            val pending=async(Dispatchers.Default) { engine.generate("Det gamle bibliotek ligger på hjørnet af Nørregade. Hun finder sin bog og begynder at læse videre i historien.",selected) }
            delay(100); pending.cancelAndJoin()
            val recovery=engine.generate("Godmorgen. Hun åbner bogen.",selected)
            assertTrue(recovery.samples.size>24000)
            assertEquals(point,AiListeningStore(app).read(book))
            File(app.filesDir,"pico-native-validation.json").writeText(JSONObject()
                .put("text",text).put("sampleRate",clip.sampleRate).put("samples",clip.samples.size)
                .put("synthesisMillis",clip.synthesisMillis).put("rtf",clip.synthesisMillis*24.0/clip.samples.size)
                .put("cancelAndRegenerate",true).put("checkpointPreserved",true).put("digest",clip.digest).toString(2))
            store.forget(book)
        } finally { engine.close() }
    }
    @Test fun realPicoAudioAppendsInOrderAtOnePointFiveWithoutEarlyCompletion() = runBlocking {
        installFixture()
        val engine=PicoEngine(app)
        val settings=SpeechSettings(backend="pico")
        val complete=Collections.synchronizedList(mutableListOf<String>())
        val error=java.util.concurrent.atomic.AtomicReference<String?>(null)
        val next=java.util.concurrent.atomic.AtomicReference<AiAudioClip?>(null)
        lateinit var player:AiAudioPlayer
        player=AiAudioPlayer(object:AiAudioPlayer.Listener {
            override fun onStarted(utteranceId:String) {}
            override fun onPosition(utteranceId:String,playedSamples:Int,sampleRate:Int,totalSamples:Int) {}
            override fun onCompleted(utteranceId:String) {
                complete.add(utteranceId)
                if(utteranceId=="first" && !player.append(requireNotNull(next.get()),"second")) error.set("Natural append failed")
            }
            override fun onError(utteranceId:String,message:String) { error.set(message) }
        })
        try {
            val first=engine.generate("Hun læser en bog.",settings)
            val second=engine.generate("Historien fortsætter i morgen.",settings)
            next.set(second)
            assertTrue(player.setPlaybackRate(1.5f))
            val started=android.os.SystemClock.elapsedRealtime()
            player.play(first,"first")
            assertTrue(player.queueNext(second))
            withTimeout(30000) { while(complete.size<2 && error.get()==null) delay(30) }
            assertNull(error.get())
            assertEquals(listOf("first","second"),complete.toList())
            val expectedMillis=(first.samples.size+second.samples.size)*1000.0/24000/1.5
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            assertTrue("Passages must finish after their source audio: $elapsed vs $expectedMillis",elapsed >= expectedMillis-150)
            File(app.filesDir,"pico-playback-validation.json").writeText(JSONObject()
                .put("completionOrder","first,second").put("rate",1.5).put("elapsedMillis",elapsed)
                .put("expectedSourcePlaybackMillis",expectedMillis).put("noEarlyCompletion",true).toString(2))
        } finally { player.stop();engine.close() }
    }
    @Test fun serviceReadsDanishBookAndRateChangeKeepsPresentedSourcePosition()=runBlocking {
        installFixture()
        val bytes=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            fun entry(name:String,text:String) {
                val raw=text.toByteArray(Charsets.UTF_8)
                val item=java.util.zip.ZipEntry(name)
                if(name=="mimetype") {item.method=java.util.zip.ZipEntry.STORED;item.size=raw.size.toLong();item.crc=java.util.zip.CRC32().apply{update(raw)}.value}
                zip.putNextEntry(item);zip.write(raw);zip.closeEntry()
            }
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf","""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:folio:pico-test</dc:identifier><dc:title>En rolig morgen</dc:title><dc:language>da</dc:language><meta property="dcterms:modified">2026-10-16T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry("nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Indhold</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">En rolig morgen</a></li></ol></nav></body></html>""")
            val paragraphs=listOf("Hun åbner bogen og finder sin plads. Udenfor skinner solen gennem vinduet.","Historien begynder i en lille by. En kat sover på en stol ved døren.","Hun læser videre og smiler stille. Der er stadig mange sider tilbage.","På bordet står en kop varm te. Hun tager en lille slurk og vender siden.","Biblioteket åbner senere på dagen. Indtil da bliver hun siddende med sin bog.")
            entry("chapter.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="da"><head><title>En rolig morgen</title></head><body>${paragraphs.joinToString(""){"<p>$it</p>"}}</body></html>""")
        }
        val book=app.library.importStream { bytes.toByteArray().inputStream() }
        val selected=SpeechSettings(backend="pico")
        selected.save(app)
        suspend fun until(check:()->Boolean)=withTimeout(180000){while(!check())delay(50)}
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use {
                SpeechController.start(app,book.id)
                until { SpeechController.state.value.error!=null || (SpeechController.state.value.playing && SpeechController.state.value.audioSample>2400) }
                assertNull(SpeechController.state.value.error)
                val first=SpeechController.state.value.passage
                SpeechController.pause(app)
                until { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
                val saved=requireNotNull(AiListeningStore(app).read(book.id))
                assertTrue(saved.playedSamples>0)
                SpeechController.settings(app,selected.copy(rate=1.5f))
                until { SpeechController.state.value.playbackRate==1.5f }
                assertEquals(saved.key,AiListeningStore(app).read(book.id)!!.key)
                SpeechController.resume(app)
                until { SpeechController.state.value.error!=null || (SpeechController.state.value.playing && SpeechController.state.value.passage!=first) }
                assertNull(SpeechController.state.value.error)
                SpeechController.stop(app)
                until { !SpeechController.serviceRunning }
                val advanced=requireNotNull(AiListeningStore(app).read(book.id))
                assertTrue(advanced.word>saved.word || advanced.section>saved.section)
                File(app.filesDir,"pico-service-validation.json").writeText(JSONObject()
                    .put("book",book.id).put("rate",1.5).put("rateChangeKeptWaveform",true)
                    .put("savedWord",saved.word).put("advancedWord",advanced.word).put("passageAdvancedAfterPlayback",true).toString(2))
            }
        } finally { SpeechController.stop(app);SpeechSettings().save(app) }
        Unit
    }
    @Test fun allFiveVoicesProduceValidatedPaddingProcessedDanishAudio() = runBlocking {
        installFixture()
        val engine = PicoEngine(app)
        val reports = org.json.JSONArray()
        try {
            for (voice in PicoEngine.voiceNames.indices) {
                SpeechDiagnostics.start()
                val clip = engine.generate("Fire små fugle synger stille, før hun åbner sin bog.", SpeechSettings(backend="pico", picoVoice=voice))
                val generated = SpeechDiagnostics.stop().single { it.kind == "pico.synthesis.end" }
                assertTrue(clip.samples.size >= 24000)
                assertTrue(generated.originalFrames >= clip.samples.size)
                assertTrue("Never discard most of a generated utterance", clip.samples.size > generated.originalFrames / 2)
                wav(File(app.filesDir,"pico-padding-voice-$voice.wav"),clip)
                reports.put(JSONObject().put("voice",PicoEngine.voiceNames[voice]).put("originalSamples",generated.originalFrames)
                    .put("samples",clip.samples.size).put("removedSeconds",(generated.originalFrames-clip.samples.size)/24000.0).put("digest",clip.digest))
            }
            File(app.filesDir,"pico-padding-voices-validation.json").writeText(reports.toString(2))
        } finally { SpeechDiagnostics.stop(); engine.close() }
    }
    private fun wav(file:File,clip:AiAudioClip) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun short(value:Int) { out.writeByte(value and 255);out.writeByte((value ushr 8) and 255) }
            fun int(value:Int) { short(value and 65535);short(value ushr 16) }
            out.writeBytes("RIFF");int(36+clip.samples.size*2);out.writeBytes("WAVEfmt ");int(16)
            short(1);short(1);int(24000);int(48000);short(2);short(16);out.writeBytes("data");int(clip.samples.size*2)
            clip.samples.forEach { short((it.coerceIn(-1f,1f)*32767).toInt()) }
        }
    }
}
