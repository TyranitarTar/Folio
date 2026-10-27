package app.folio.reader

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class Audio016InstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private suspend fun waitFor(test:()->Boolean)=withTimeout(20000){while(!test())delay(40)}
    private fun click(description:String){val node=device.wait(Until.findObject(By.desc(description)),10000);assertNotNull(description,node);node.click();device.waitForIdle()}
    private fun text(label:String){val node=device.wait(Until.findObject(By.text(label)),10000);assertNotNull(label,node);node.click();device.waitForIdle()}
    private fun scrollToText(label:String) {
        repeat(4){if(device.hasObject(By.text(label)))return;device.swipe(device.displayWidth/2,device.displayHeight*4/5,device.displayWidth/2,device.displayHeight/3,25);device.waitForIdle()}
        assertTrue("Visible $label",device.hasObject(By.text(label)))
    }
    private fun wav():ByteArray {
        val count=60*16000;return ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN).apply{
            put("RIFF".toByteArray());putInt(36+count*2);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
            putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(count*2)
        }.array()
    }
    private fun fixture():File {
        val identity=UUID.randomUUID().toString();val text="First test passage. Second test passage."
        val epub=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->
            fun entry(name:String,data:String){z.putNextEntry(ZipEntry(name));z.write(data.toByteArray());z.closeEntry()}
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","""<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$identity</dc:identifier><dc:title>Folio 016 listening fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            for(name in listOf("one","two"))entry("$name.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$name</title></head><body><p id="p1">First test passage.</p><p id="p2">Second test passage.</p></body></html>""")
        }}.toByteArray()
        val chapters=JSONArray()
        listOf("Opening","Second section").forEachIndexed{i,title->
            val cues=JSONArray()
            repeat(2){n->cues.put(JSONObject().put("startMs",n*30000).put("endMs",(n+1)*30000)
                .put("href",if(i==0)"one.xhtml" else "two.xhtml").put("fragment","p${n+1}").put("cssSelector","#p${n+1}")
                .put("progression",n*.5).put("text",if(n==0)"First test passage." else "Second test passage."))}
            chapters.put(JSONObject().put("file","audio/$i.wav").put("title",title).put("durationMs",60000).put("cues",cues))
        }
        val sha=MessageDigest.getInstance("SHA-256").digest(epub).joinToString(""){"%02x".format(it)}
        val manifest=JSONObject().put("format","folio-audiobook").put("version",1).put("title","Folio 016 listening fixture")
            .put("sourceSha256",sha).put("epub","book.epub").put("chapters",chapters)
        val file=File(app.cacheDir,"audio016-$identity.foliobook")
        ZipOutputStream(file.outputStream()).use{z->
            fun entry(name:String,bytes:ByteArray){z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}
            entry("manifest.json",manifest.toString().toByteArray());entry("book.epub",epub)
            repeat(2){entry("audio/$it.wav",wav())}
        }
        return file
    }

    @Test fun progress_is_read_only_skips_cross_files_and_chapter_seek_requires_apply()=runBlocking {
        SpeechController.stop(app);AudiobookController.stop(app);val input=fixture();val store=app.audiobooks
        val before=AudioPreferences.load(app);val book=store.importBundle(Uri.fromFile(input))
        try {
            AudiobookController.start(app,book.id,0,45000,false)
            waitFor{AudiobookController.state.value.id==book.id && !AudiobookController.state.value.loading}
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use {
                assertTrue(device.wait(Until.hasObject(By.desc("Book position")),15000))
                val bar=device.findObject(By.desc("Book position"));assertFalse(bar.isClickable)
                device.takeScreenshot(File(app.filesDir,"v016-player-overview.png"))
                val bounds=bar.visibleBounds;device.click(bounds.right-4,bounds.centerY());delay(500)
                assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs-45000)<300)
                for((button,track,at) in listOf(Triple("Forward 10 seconds",0,55000L),Triple("Forward 60 seconds",1,55000L),Triple("Back 10 seconds",1,45000L),Triple("Back 60 seconds",0,45000L))) {
                    click(button);waitFor{AudiobookController.state.value.trackIndex==track && !AudiobookController.state.value.loading && kotlin.math.abs(AudiobookController.state.value.positionMs-at)<400}
                    assertFalse(AudiobookController.state.value.playing)
                }
                assertFalse(device.hasObject(By.text("Second section")))
                text("1.0× speed");text("2.0×");waitFor{AudiobookController.state.value.speed==2f}
                assertTrue(device.wait(Until.hasObject(By.textContains("left at 2.0")),5000))
                text("Chapters");text("Second section");assertTrue(device.wait(Until.hasObject(By.text("Jump to chapter 2?")),5000));text("Cancel")
                assertEquals(0,AudiobookController.state.value.trackIndex);device.waitForIdle();delay(600)
                text("Adjust");delay(400);val slider=device.wait(Until.findObject(By.desc("Chapter position")),5000);assertNotNull(slider)
                device.takeScreenshot(File(app.filesDir,"v016-seek-adjust.png"))
                val area=slider.visibleBounds;device.swipe(area.left+area.width()/3,area.centerY(),area.right-30,area.centerY(),25);delay(300)
                device.takeScreenshot(File(app.filesDir,"v016-seek-drag.png"));device.dumpWindowHierarchy(File(app.filesDir,"v016-seek-drag.xml"))
                assertTrue(kotlin.math.abs(AudiobookController.state.value.positionMs-45000)<300)
                scrollToText("Apply position");text("Apply position");waitFor{kotlin.math.abs(AudiobookController.state.value.positionMs-45000)>3000}
                assertFalse(AudiobookController.state.value.playing)
                assertTrue(device.wait(Until.hasObject(By.desc("Chapter progress · locked")),5000));delay(250)
                device.takeScreenshot(File(app.filesDir,"v016-player.png"))
            }
            AudiobookController.seekTrack(app,1,45000);waitFor{AudiobookController.state.value.trackIndex==1 && !AudiobookController.state.value.loading}
            waitFor{app.library.books.value.firstOrNull{it.id==book.bookId}?.position()?.href?.toString()=="two.xhtml"}
            val reading=app.library.books.value.first{it.id==book.bookId}
            assertEquals("Second test passage.",reading.position()?.text?.highlight);assertTrue(reading.progress>.5)
            File(app.filesDir,"v016-player.json").writeText(JSONObject().put("passed",true).put("bookProgressReadOnly",true)
                .put("fourSkipsAndCrossFilePausedState",true).put("chapterJumpConfirmation",true).put("chapterSeekApplyRequired",true)
                .put("speedAdjustedRemainingVisible",true).put("sharedReadingCursor",true).toString(2))
        } finally {
            before.save(app);AudiobookController.stop(app);waitFor{AudiobookController.state.value.id==null}
            store.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }

    @Test fun transientFocusResumesButManualPauseAndPermanentLossDoNot()=runBlocking {
        val input=fixture();val store=app.audiobooks;val book=store.importBundle(Uri.fromFile(input));val before=AudioPreferences.load(app)
        val audio=app.getSystemService(android.media.AudioManager::class.java)
        fun request(kind:Int)=android.media.AudioFocusRequest.Builder(kind)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({},android.os.Handler(android.os.Looper.getMainLooper())).build()
        var other:android.media.AudioFocusRequest?=null
        try {
            before.copy(autoRewind=false,pauseForNotifications=true,resumeAfterInterruption=true).save(app)
            ActivityScenario.launch<AudiobookActivity>(Intent(app,AudiobookActivity::class.java).putExtra("audiobook",book.id)).use {
                AudiobookController.start(app,book.id,0,10000,true);waitFor{AudiobookController.state.value.playing}
                other=request(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(other!!))
                waitFor{!AudiobookController.state.value.playing};audio.abandonAudioFocusRequest(other!!);other=null
                waitFor{AudiobookController.state.value.playing}
                other=request(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(other!!))
                waitFor{!AudiobookController.state.value.playing};AudiobookController.pause(app);delay(300)
                audio.abandonAudioFocusRequest(other!!);other=null;delay(1000);assertFalse(AudiobookController.state.value.playing)
                AudiobookController.play(app);waitFor{AudiobookController.state.value.playing}
                other=request(android.media.AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(other!!))
                waitFor{!AudiobookController.state.value.playing};audio.abandonAudioFocusRequest(other!!);other=null;delay(1000)
                assertFalse(AudiobookController.state.value.playing)
                File(app.filesDir,"v016-focus.json").writeText(JSONObject().put("passed",true).put("transientResume",true).put("manualPauseCancelsResume",true).put("permanentLossStaysPaused",true).toString(2))
            }
        } finally {
            other?.let{audio.abandonAudioFocusRequest(it)};before.save(app);AudiobookController.stop(app);waitFor{AudiobookController.state.value.id==null}
            store.remove(book.id);book.bookId?.let{app.library.removeBook(it)};input.delete()
        }
    }
}
