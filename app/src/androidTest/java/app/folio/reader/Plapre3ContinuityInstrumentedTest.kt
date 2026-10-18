package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real service, cold generated-audio cache: upcoming synthesis must overlap playback. */
@RunWith(AndroidJUnit4::class)
class Plapre3ContinuityInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private val passages = listOf(
        "Hun åbner bogen og finder sin plads.", "Udenfor skinner solen gennem vinduet.",
        "Historien begynder i en lille by.", "En kat sover på en stol ved døren.",
        "Hun læser videre og smiler stille.", "Der er stadig mange sider tilbage.",
        "På bordet står en kop varm te.", "Hun tager en lille slurk og vender siden.",
        "Biblioteket åbner senere på dagen.", "Indtil da bliver hun siddende med sin bog.",
        "En fugl synger i det gamle æbletræ.", "Vinden bevæger bladene ganske langsomt.",
        "Hun lægger forsigtigt et bogmærke på bordet.", "Den næste side fortæller om en lang rejse.",
        "Toget kører gennem den grønne skov.", "En lille dreng vinker fra en bro.",
        "Ved stationen venter en venlig gammel mand.", "Han har en brun taske og en blå hat.",
        "De går sammen ned ad den smalle gade.", "Til sidst finder de huset ved havnen."
    )
    private suspend fun makeBook(lines:List<String> = passages): Book {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, text: String) {
                val raw = text.toByteArray(Charsets.UTF_8)
                val item = ZipEntry(name)
                if (name == "mimetype") { item.method = ZipEntry.STORED; item.size = raw.size.toLong(); item.crc = java.util.zip.CRC32().apply { update(raw) }.value }
                zip.putNextEntry(item); zip.write(raw); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:folio:continuity:${UUID.randomUUID()}</dc:identifier><dc:title>Plapre 3 continuity check</dc:title><dc:language>da</dc:language><meta property="dcterms:modified">2026-10-17T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            entry("nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Indhold</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Morgen</a></li></ol></nav></body></html>""")
            entry("chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="da"><head><title>Morgen</title></head><body>${lines.joinToString("") { "<p>$it</p>" }}</body></html>""")
        }
        return app.library.importStream { bytes.toByteArray().inputStream() }
    }
    @Test fun prepareAheadKeepsBookmarkAndResumeUsesGeneratedCache()=runBlocking {
        assertTrue(app.packageName.endsWith(".debug"))
        assertFalse(SpeechController.serviceRunning)
        app.voicePacks.existingCheck.join()
        assertEquals("Ready",app.voicePacks.state("plapre3").phase)
        val original=SpeechSettings.load(app)
        val selected=SpeechSettings(backend="plapre3")
        val book=makeBook(passages.take(3))
        val publication=app.library.engine.open(app.library.file(book.id))
        val text=try { BookText.load(publication) } finally { publication.close() }
        val start=text.cursor(0,2)!!
        var scan:ReadingCursor?=start
        while(scan!=null) {
            val here=scan;val words=SpeechPassages.words(text,here,selected)
            val key=selected.audioKey(words.joinToString(" "){it.text})
            File(app.cacheDir,"ai-audio/$key.pcm").delete()
            scan=text.next(text.cursor(here.sectionIndex,here.wordIndex+words.lastIndex)!!)
        }
        ReaderCursorStore(app).write(book.id,"speech",start)
        selected.save(app)
        SpeechDiagnostics.start()
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use {
                SpeechController.prepareChapter(app,book.id)
                withTimeout(180000) {
                    while(SpeechController.state.value.preparationStage!="prepared" && SpeechController.state.value.error==null)delay(50)
                }
                assertNull(SpeechController.state.value.error)
                assertFalse(SpeechController.state.value.playing)
                assertEquals(start,ReaderCursorStore(app).read(book.id,"speech"))
                val prepared=SpeechDiagnostics.stop()
                val generated=prepared.count{it.kind=="plapre3.synthesis.end"}
                assertEquals(3,generated)
                assertFalse(prepared.any{it.kind=="player.presented-start"})
                SpeechDiagnostics.start()
                SpeechController.resume(app)
                withTimeout(90000) { while(!SpeechController.state.value.finished && SpeechController.state.value.error==null)delay(30) }
                assertNull(SpeechController.state.value.error)
                val playback=SpeechDiagnostics.stop()
                assertEquals(3,playback.count{it.kind=="player.presented-end"})
                assertEquals(0,playback.count{it.kind=="plapre3.synthesis.start"})
                File(app.filesDir,"plapre3-prepare-ahead-validation.json").writeText(JSONObject().put("preparedPassages",generated)
                    .put("bookmarkPreserved",true).put("silentPreparation",true).put("resumeSynthesisCalls",0).put("completedPassages",3).toString(2))
            }
        } finally {
            SpeechDiagnostics.stop();SpeechController.stop(app)
            withTimeout(10000) { while(SpeechController.serviceRunning)delay(20) }
            original.save(app);app.library.removeBook(book.id)
        }
    }
    @Test fun compareBoundedSentenceGroupsWithSeparatePassages() = runBlocking {
        assertTrue(app.packageName.endsWith(".debug"))
        assertFalse(SpeechController.serviceRunning)
        val sentences = listOf("Hun åbner bogen og finder sin plads.", "Udenfor skinner solen gennem vinduet.", "En kat sover på stolen ved døren.")
        val grouped = sentences.joinToString(" ")
        assertTrue(grouped.length <= 160)
        val engine = PicoEngine(app,plapre3=true)
        val settings = SpeechSettings(backend="plapre3")
        val trials = JSONArray()
        val activity = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        try {
            engine.generate("Hun læser videre.", settings) // Exclude engine loading and first-run warmup.
            for (round in 0..1) for (batch in if (round == 0) listOf(false, true) else listOf(true, false)) {
                val started = System.nanoTime()
                val clips = (if (batch) listOf(grouped) else sentences).map { engine.generate(it, settings) }
                trials.put(JSONObject().put("round", round).put("grouped", batch)
                    .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0)
                    .put("audioSeconds", clips.sumOf { it.samples.size.toDouble() / it.sampleRate }))
                if (round == 0) {
                    val pcm = FloatArray(clips.sumOf { it.samples.size })
                    var offset = 0
                    clips.forEach { it.samples.copyInto(pcm, offset); offset += it.samples.size }
                    java.io.DataOutputStream(File(app.filesDir, "plapre3-grouping-${if(batch) "grouped" else "separate"}.wav").outputStream().buffered()).use { out ->
                        fun short(value: Int) { out.writeByte(value and 255); out.writeByte((value ushr 8) and 255) }
                        fun int(value: Int) { short(value and 65535); short(value ushr 16) }
                        out.writeBytes("RIFF"); int(36 + pcm.size * 2); out.writeBytes("WAVEfmt "); int(16)
                        short(1); short(1); int(24000); int(48000); short(2); short(16); out.writeBytes("data"); int(pcm.size * 2)
                        pcm.forEach { short((it * 32767).toInt()) }
                    }
                }
            }
            File(app.filesDir, "plapre3-grouping-validation.json").writeText(JSONObject().put("text", grouped)
                .put("wordCount", grouped.split(" ").size).put("sourceCharacters", grouped.length)
                .put("trials", trials).put("productionGroupingChanged", false).toString(2))
        } finally { engine.close(); activity.close() }
    }
    @Test fun coldPlapre3GeneratesAheadDuringTwentyPassagesOfServicePlayback() = runBlocking {
        assertTrue(app.packageName.endsWith(".debug"))
        app.voicePacks.existingCheck.join()
        assertEquals("Ready", app.voicePacks.state(app.voicePacks.forBackend("plapre3")!!.id).phase)
        assertFalse(SpeechController.serviceRunning)
        AiListeningStore.clearCache(app)
        val book = makeBook()
        SpeechSettings(backend = "plapre3").save(app)
        SpeechDiagnostics.start()
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use {
                SpeechController.start(app, book.id)
                withTimeout(900000) {
                    while (SpeechController.state.value.bookId != book.id) delay(50)
                    while (!SpeechController.state.value.finished && SpeechController.state.value.error == null) delay(50)
                }
                assertNull(SpeechController.state.value.error)
                val trace = SpeechDiagnostics.stop()
                val starts = trace.filter { it.kind == "player.presented-start" }
                val ends = trace.filter { it.kind == "player.presented-end" }
                val generation = trace.filter { it.kind == "plapre3.synthesis.end" }
                assertEquals(passages.size, starts.size)
                assertEquals(passages.size, ends.size)
                assertEquals(passages.size, generation.size)
                val windows = starts.map { start -> start.nanos to ends.single { it.id == start.id }.nanos }
                val generationStarts = trace.filter { it.kind == "plapre3.synthesis.start" }
                val overlapping = generationStarts.count { job -> windows.any { (from, until) -> job.nanos in from..until } }
                assertTrue("Upcoming Plapre 3 synthesis must run during playback", overlapping > 0)
                val synthesisSeconds = generationStarts.zip(generation).sumOf { (start, end) -> (end.nanos - start.nanos) / 1_000_000_000.0 }
                val queuedBeforeEnd = starts.drop(1).mapIndexed { index, next ->
                    trace.any { it.kind == "player.queued" && it.id == next.id && it.nanos < ends[index].nanos }
                }.count { it }
                assertTrue("Prepared lookahead must reach AudioTrack before the previous passage ends", queuedBeforeEnd >= 2)
                val gaps = starts.drop(1).mapIndexed { index, next -> maxOf(0.0, (next.nanos - ends[index].nanos) / 1_000_000.0) }.sorted()
                File(app.filesDir, "plapre3-continuity-validation.json").writeText(JSONObject()
                    .put("passages", starts.size).put("rate", 1.0).put("coldAudioCache", true)
                    .put("generationStartsDuringPlayback", overlapping).put("lookaheadQueuedBeforePreviousEnd", queuedBeforeEnd)
                    .put("medianHandoffMillis", gaps[gaps.size / 2]).put("maxHandoffMillis", gaps.last())
                    .put("originalAudioSeconds", generation.sumOf { it.originalFrames.toDouble() / it.rate })
                    .put("processedAudioSeconds", generation.sumOf { it.frames.toDouble() / it.rate })
                    .put("effectiveSynthesisRtf", synthesisSeconds / generation.sumOf { it.frames.toDouble() / it.rate })
                    .put("events", JSONArray(trace.map { event -> JSONObject().put("nanos", event.nanos).put("kind", event.kind).put("id", event.id).put("frames", event.frames).put("rate", event.rate).put("originalFrames", event.originalFrames) })).toString(2))
            }
        } finally {
            SpeechDiagnostics.stop(); SpeechController.stop(app); SpeechSettings().save(app)
        }
    }
}
