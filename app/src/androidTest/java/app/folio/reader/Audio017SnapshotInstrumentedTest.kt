package app.folio.reader

import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class Audio017SnapshotInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    private fun snapshot(base:File, revision:Int, shift:Int=0, changeAudio:Boolean=false, corruptHash:Boolean=false, removeCue:Boolean=false):File {
        val entries=linkedMapOf<String,ByteArray>()
        ZipFile(base).use { zip -> zip.entries().asSequence().forEach { entry -> entries[entry.name]=zip.getInputStream(entry).use{it.readBytes()} } }
        val manifest=JSONObject(String(checkNotNull(entries.remove("manifest.json")),Charsets.UTF_8))
        val edition="folio-"+manifest.getString("sourceSha256")
        manifest.put("editionId",edition).put("revision",revision).put("revisionId",revision.toString(16).padStart(64,'0')).put("incomplete",revision==1)
        val chapters=manifest.getJSONArray("chapters")
        for(i in 0 until chapters.length()) {
            val chapter=chapters.getJSONObject(i);val name=chapter.getString("file")
            if(changeAudio) {
                val original=checkNotNull(entries[name]);val longer=original.copyOf(original.size+32000)
                val buffer=java.nio.ByteBuffer.wrap(longer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(4,longer.size-8);buffer.putInt(40,longer.size-44);entries[name]=longer
                chapter.put("durationMs",61000)
            }
            val cues=chapter.getJSONArray("cues")
            for(n in 0 until cues.length()) { val cue=cues.getJSONObject(n)
                cue.put("startMs",cue.getInt("startMs")+shift).put("endMs",minOf(cue.getInt("endMs")+shift,if(changeAudio)61000 else 60000))
            }
            if(removeCue && i==1)cues.remove(0)
            val hash=MessageDigest.getInstance("SHA-256").digest(checkNotNull(entries[name])).joinToString(""){"%02x".format(it)}
            chapter.put("chapterId",name).put("audioSha256",if(corruptHash) "0".repeat(64) else hash)
        }
        return File(app.cacheDir,"snapshot-$revision-${System.nanoTime()}.foliobook").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                fun put(name:String,bytes:ByteArray){zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
                put("manifest.json",manifest.toString().toByteArray(Charsets.UTF_8));entries.forEach{(name,bytes)->put(name,bytes)}
            }
        }
    }
    @Test fun revisions_preserve_state_refuse_active_or_unsafe_updates_and_recover_interrupted_swap()=runBlocking {
        AudiobookController.stop(app)
        withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(50)}
        val base=Audio016InstrumentedTest().fixture();val first=snapshot(base,1);val second=snapshot(base,2)
        val corrected=snapshot(base,3,shift=1000);val changed=snapshot(base,4,shift=2000,changeAudio=true)
        val corrupt=snapshot(base,5,corruptHash=true);val unsafe=snapshot(base,6,shift=2000,changeAudio=true,removeCue=true)
        val conflict=snapshot(base,2,shift=1000)
        val store=app.audiobooks;val book=store.importBundle(Uri.fromFile(first))
        try {
            store.setPassageOffset(book.id,2500);store.savePosition(book.id,1,10000,1.5f)
            store.addBookmark(book.id,1,12000,"saved");store.assignShelves(book.id,listOf("Shelf"))
            val cue=book.tracks[1].cues.first();app.library.addMark(checkNotNull(book.bookId),checkNotNull(RecordedNarration.locator(cue)),true,
                audioId=book.id,audioTrack=1,audioPositionMs=cue.startMs)
            val quote=app.library.books.value.first{it.id==book.bookId}.highlights.last()
            AudiobookController.start(app,book.id,autoplay=false)
            withTimeout(20000){while(AudiobookController.state.value.id!=book.id || AudiobookController.state.value.loading)delay(50)}
            assertTrue(runCatching{store.importBundle(Uri.fromFile(second))}.isFailure)
            AudiobookController.stop(app);withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(50)}
            val updated=store.importBundle(Uri.fromFile(second))
            assertEquals(book.id,updated.id);assertEquals(2L,updated.editionRevision);assertEquals(2500L,updated.passageOffsetMs)
            assertEquals(1,updated.positionTrack);assertEquals(10000L,updated.positionMs);assertEquals(1.5f,updated.speed,0.001f)
            assertEquals("saved",updated.bookmarks.single().label);assertEquals(listOf("Shelf"),updated.shelves)
            assertEquals(2L,store.importBundle(Uri.fromFile(first)).editionRevision)
            assertTrue(runCatching{store.importBundle(Uri.fromFile(conflict))}.isFailure)
            val aligned=store.importBundle(Uri.fromFile(corrected))
            assertEquals(10000L,aligned.positionMs);assertEquals(12000L,aligned.bookmarks.single().positionMs)
            assertEquals(1000L,aligned.quoteTargets.getValue(quote.id).positionMs)
            val remapped=store.importBundle(Uri.fromFile(changed))
            assertEquals(11000L,remapped.positionMs);assertEquals(13000L,remapped.bookmarks.single().positionMs)
            assertEquals(2000L,remapped.quoteTargets.getValue(quote.id).positionMs)
            assertTrue(runCatching{store.importBundle(Uri.fromFile(unsafe))}.isFailure)
            assertTrue(runCatching{store.importBundle(Uri.fromFile(corrupt))}.isFailure)
            val root=File(app.filesDir,"audiobooks");val destination=File(root,book.id);val backup=File(root,".snapshot-backup-${book.id}")
            File(root,"audiobooks.json").copyTo(File(root,".snapshot-catalog-backup"),overwrite=true)
            DurableJson.write(AtomicFile(File(root,".snapshot-receipt.json")),JSONObject().put("id",book.id).toString().toByteArray())
            assertTrue(destination.renameTo(backup));assertTrue(destination.mkdir())
            val reopened=AudiobookStore(app);reopened.load()
            val restored=reopened.books.value.first{it.id==book.id}
            assertEquals(4L,restored.editionRevision);assertEquals(2500L,restored.passageOffsetMs)
            assertTrue(File(destination,restored.tracks.first().file).isFile)
            val orphanId="f".repeat(64);val orphan=File(root,orphanId);assertFalse(orphan.exists());assertTrue(orphan.mkdir())
            File(root,"audiobooks.json").copyTo(File(root,".snapshot-catalog-backup"),overwrite=true)
            DurableJson.write(AtomicFile(File(root,".snapshot-receipt.json")),JSONObject().put("id",orphanId).put("new",true).put("hadCatalog",true).toString().toByteArray())
            val afterInterruptedFirst=AudiobookStore(app);afterInterruptedFirst.load()
            assertFalse(orphan.exists());assertEquals(4L,afterInterruptedFirst.books.value.first{it.id==book.id}.editionRevision)
        } finally {
            AudiobookController.stop(app);store.remove(book.id);book.bookId?.let{app.library.removeBook(it)}
            listOf(base,first,second,corrected,changed,unsafe,corrupt,conflict).forEach{it.delete()}
        }
    }
}
