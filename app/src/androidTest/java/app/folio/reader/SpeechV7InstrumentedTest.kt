package app.folio.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Supertonic JNI/AudioTrack checks; source frames are evidence, not acoustic verification. */
@RunWith(AndroidJUnit4::class)
class SpeechV7InstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private val fixtureBooks=mutableSetOf<String>()
    private fun waitFor(limit:Long=120000,check:()->Boolean)=runBlocking { withTimeout(limit) { while(!check()) delay(50) } }
    @After fun cleanup() {
        try {
            SpeechController.cancelPreview(app); SpeechController.stop(app)
            waitFor { !SpeechController.state.value.previewing && SpeechController.state.value.bookId==null }
        } finally {
            try { runBlocking { fixtureBooks.forEach { app.library.removeBook(it) } } }
            finally { fixtureBooks.clear(); SpeechSettings().save(app) }
        }
    }
    @Test fun supertonicLiveRatesReuseWaveformCheckpointAndSurvivePauseRestart() {
        SpeechSettings(backend="supertonic",rate=1f).save(app)
        val book=runBlocking { app.library.importSample() }
        val pub=runBlocking { app.engine.open(app.library.file(book.id)) }
        val text=try { runBlocking { BookText.load(pub,book.id) } } finally { pub.close() }
        val section=text.sections.indexOfFirst { it.words.size>200 }
        val requested=text.cursor(section,12)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use {
            SpeechController.start(app,book.id,requested.locator)
            waitFor { SpeechController.state.value.error!=null || SpeechController.state.value.audioSample>2400 }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app); waitFor { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
            waitFor { AiListeningStore(app).read(book.id)?.playedSamples==SpeechController.state.value.audioSample }
            val before=AiListeningStore(app).read(book.id)!!
            assertNotNull(before.audioDigest)
            val clip=AiListeningStore(app).loadClip(before.key)!!
            val summary=JSONObject().put("samples",clip.samples.size).put("rate",clip.sampleRate).put("digest",before.audioDigest)
            for(rate in listOf(1.25f,1.5f,1.75f,2f,2.5f,3f,.75f,1f)) {
                SpeechController.settings(app,SpeechSettings(backend="supertonic",rate=rate))
                waitFor { SpeechController.state.value.error!=null || SpeechController.state.value.playbackRate==rate }
                assertNull(SpeechController.state.value.error)
                assertFalse(SpeechController.state.value.playing)
                assertEquals(before.playedSamples,SpeechController.state.value.audioSample)
                waitFor { AiListeningStore(app).read(book.id)?.playedSamples==before.playedSamples }
                val now=AiListeningStore(app).read(book.id)!!
                assertEquals(before.key,now.key); assertEquals(before.audioDigest,now.audioDigest)
            }
            SpeechController.settings(app,SpeechSettings(backend="supertonic",rate=1.5f))
            waitFor { SpeechController.state.value.playbackRate==1.5f }
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error!=null || SpeechController.state.value.audioSample>before.playedSamples+2400 }
            assertNull(SpeechController.state.value.error)
            assertEquals(before.key,AiListeningStore(app).read(book.id)!!.key)
            SpeechController.settings(app,SpeechSettings(backend="supertonic",rate=3f))
            waitFor { SpeechController.state.value.error!=null || SpeechController.state.value.playbackRate==3f }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app); waitFor { !SpeechController.state.value.playing }
            waitFor { AiListeningStore(app).read(book.id)?.playedSamples==SpeechController.state.value.audioSample }
            val fast=AiListeningStore(app).read(book.id)!!
            assertEquals(before.key,fast.key); assertEquals(before.audioDigest,fast.audioDigest)
            SpeechController.stop(app); waitFor { SpeechController.state.value.bookId==null }
            SpeechController.start(app,book.id)
            waitFor { SpeechController.state.value.error!=null || SpeechController.state.value.audioSample>fast.playedSamples }
            assertNull(SpeechController.state.value.error)
            summary.put("initialSample",before.playedSamples).put("fastPauseSample",fast.playedSamples)
            File(app.filesDir,"speech-v7-source-rate-validation.json").writeText(summary.toString(2))
            SpeechController.pause(app)
        }
    }
    @Test fun latestPausedSeekWinsAndPreviewNeverReplacesBookCheckpoint() {
        SpeechSettings(backend="supertonic").save(app)
        val precedingBook=runBlocking { app.library.importSample() }
        // Same valid content, distinct archive identity: exercise startBook in the live service.
        val rewritten=ByteArrayOutputStream()
        ZipOutputStream(rewritten).use { output ->
            output.setComment("Paused seek replacement regression")
            ZipInputStream(app.assets.open("sample.epub")).use { input ->
                while(true) {
                    val entry=input.nextEntry ?: break
                    val bytes=input.readBytes()
                    val copied=ZipEntry(entry.name).apply {
                        method=entry.method
                        if(method==ZipEntry.STORED) {
                            size=bytes.size.toLong(); compressedSize=size
                            crc=CRC32().apply { update(bytes) }.value
                        }
                    }
                    output.putNextEntry(copied)
                    output.write(bytes); output.closeEntry(); input.closeEntry()
                }
            }
        }
        val replacementBytes=rewritten.toByteArray()
        val book=runBlocking { app.library.importStream { ByteArrayInputStream(replacementBytes) } }
            .also { fixtureBooks.add(it.id) }
        assertNotEquals(precedingBook.id,book.id)
        val pub=runBlocking { app.engine.open(app.library.file(book.id)) }
        val text=try { runBlocking { BookText.load(pub,book.id) } } finally { pub.close() }
        val section=text.sections.indexOfFirst { it.words.size>200 }
        val first=text.cursor(section,12)!!; val last=text.cursor(section,90)!!
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use {
            SpeechController.start(app,precedingBook.id,first.locator)
            waitFor { SpeechController.state.value.bookId==precedingBook.id &&
                (SpeechController.state.value.error!=null || SpeechController.state.value.audioSample>2400) }
            assertNull(SpeechController.state.value.error)
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
            assertFalse(SpeechController.state.value.passage.isBlank())
            SpeechController.seek(app,book.id,first.locator,true)
            SpeechController.seek(app,book.id,last.locator,true)
            waitFor { SpeechController.state.value.bookId==book.id && !SpeechController.state.value.preparing && SpeechController.state.value.locator?.text==last.locator.text }
            assertFalse(SpeechController.state.value.playing)
            waitFor { ReaderCursorStore(app).read(book.id,"speech")?.let {
                it.sectionIndex==last.sectionIndex && it.wordIndex==last.wordIndex }==true }
            Thread.sleep(250)
            assertEquals(book.id,SpeechController.state.value.bookId)
            assertEquals(last.locator,SpeechController.state.value.locator)
            val initialRevision=SpeechController.state.value.seekRevision
            assertTrue(initialRevision>0)
            SpeechController.seek(app,book.id,first.locator,true)
            waitFor { SpeechController.state.value.seekRevision==initialRevision+1 }
            assertEquals(first.locator.text,SpeechController.state.value.locator?.text)
            SpeechController.seek(app,book.id,last.locator,true)
            waitFor { SpeechController.state.value.seekRevision==initialRevision+2 }
            assertEquals(last.locator.text,SpeechController.state.value.locator?.text)
            val targetRevision=SpeechController.state.value.seekRevision
            SpeechController.pause(app)
            waitFor { !SpeechController.state.value.playing && !SpeechController.state.value.preparing }
            assertEquals(targetRevision,SpeechController.state.value.seekRevision)
            waitFor { ReaderCursorStore(app).read(book.id,"speech")?.wordIndex==last.wordIndex }
            val saved=ReaderCursorStore(app).read(book.id,"speech")!!
            SpeechController.preview(app,SpeechSettings(backend="supertonic",rate=1.5f,supertonicLanguage="da"))
            waitFor { SpeechController.state.value.previewing || SpeechController.state.value.error!=null }
            waitFor { !SpeechController.state.value.previewing }
            assertNull(SpeechController.state.value.error)
            assertEquals(saved,ReaderCursorStore(app).read(book.id,"speech"))
            assertEquals(targetRevision,SpeechController.state.value.seekRevision)
            assertFalse(SpeechController.state.value.playing)
            SpeechController.preview(app,SpeechSettings(backend="supertonic"))
            waitFor { SpeechController.state.value.previewing }
            SpeechController.cancelPreview(app)
            waitFor { !SpeechController.state.value.previewing }
            Thread.sleep(300)
            assertFalse(SpeechController.state.value.playing)
            assertEquals(saved,ReaderCursorStore(app).read(book.id,"speech"))
            assertEquals(targetRevision,SpeechController.state.value.seekRevision)
        }
    }
    @Test fun endChapterTimerDrainsThenPausesAtTheFollowingChapterAnchor() {
        SpeechSettings(backend="supertonic",rate=1.5f).save(app)
        val book=runBlocking { app.library.importSample() }
        val pub=runBlocking { app.engine.open(app.library.file(book.id)) }
        val text=try { runBlocking { BookText.load(pub,book.id) } } finally { pub.close() }
        val starts=SpeechNavigation.chapters(text)
        assertTrue(starts.size>=2)
        val next=starts[1]
        var requested=text.previous(next)!!
        repeat(18) { requested=text.previous(requested) ?: requested }
        ActivityScenario.launch<ReaderActivity>(Intent(app,ReaderActivity::class.java).putExtra("book",book.id)).use {
            SpeechController.seek(app,book.id,requested.locator,true)
            waitFor { SpeechController.state.value.bookId==book.id && !SpeechController.state.value.preparing }
            SpeechController.sleep(app,endChapter=true)
            waitFor { SpeechController.state.value.sleepEndChapter }
            SpeechController.resume(app)
            waitFor { SpeechController.state.value.error!=null || (!SpeechController.state.value.sleepEndChapter && SpeechController.state.value.sleepRemainingSeconds==0L) }
            assertNull(SpeechController.state.value.error)
            assertFalse(SpeechController.state.value.playing)
            waitFor { ReaderCursorStore(app).read(book.id,"speech")?.let { it.sectionIndex==next.sectionIndex && it.wordIndex==next.wordIndex }==true }
            assertEquals(next.locator.text,SpeechController.state.value.locator?.text)
        }
    }
}
