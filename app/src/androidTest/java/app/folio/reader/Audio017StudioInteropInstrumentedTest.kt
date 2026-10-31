package app.folio.reader

import android.media.MediaPlayer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Audio017StudioInteropInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    @Test fun studio_authored_snapshots_import_complete_and_remap_real_audio()=runBlocking {
        val fixtureDir=File(app.filesDir,"v017-studio-fixtures")
        val files=(1..3).map{File(fixtureDir,"revision-$it.foliobook")}
        assumeTrue("Studio fixtures have not been supplied",files.all{it.isFile})
        val manifest=java.util.zip.ZipFile(files[0]).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        val edition=manifest.getString("editionId")
        val epub=manifest.getString("sourceSha256")
        val store=app.audiobooks;store.load();app.library.load()
        assumeTrue("Authored fixture is already in this library",store.books.value.none{it.editionId==edition} && app.library.books.value.none{it.id==epub})
        AudiobookController.stop(app);withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(30)}
        val first=store.importBundle(Uri.fromFile(files[0]));var success=false
        try {
            assertEquals(edition,first.editionId);assertTrue(first.audioIncomplete);assertEquals(1,first.tracks.size)
            AudiobookController.start(app,first.id,0,0,true)
            withTimeout(15000){while(AudiobookController.state.value.id!=first.id || !AudiobookController.state.value.playing)delay(20)}
            withTimeout(15000){while(AudiobookController.state.value.playing || AudiobookController.state.value.positionMs<1000)delay(30)}
            delay(250)
            assertFalse(store.books.value.first{it.id==first.id}.isRead)
            AudiobookController.stop(app);withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(30)}
            store.savePosition(first.id,0,500L,1f);store.setPassageOffset(first.id,100L)
            store.addBookmark(first.id,0,550L,"fixture bookmark")
            val cue=first.tracks[0].cues[1]
            app.library.addMark(checkNotNull(first.bookId),checkNotNull(RecordedNarration.locator(cue)),true,
                audioId=first.id,audioTrack=0,audioPositionMs=cue.startMs)
            val mark=app.library.books.value.first{it.id==first.bookId}.highlights.last()
            val second=store.importBundle(Uri.fromFile(files[1]))
            assertEquals(first.id,second.id);assertEquals(2,second.tracks.size);assertFalse(second.audioIncomplete)
            assertFalse(second.isRead);assertEquals(500L,second.positionMs);assertEquals(550L,second.bookmarks.single().positionMs)
            assertEquals(100L,second.passageOffsetMs);assertEquals(400L,second.quoteTargets.getValue(mark.id).positionMs)
            val third=store.importBundle(Uri.fromFile(files[2]))
            assertEquals(first.id,third.id);assertEquals(3L,third.editionRevision)
            assertEquals(700L,third.positionMs);assertEquals(750L,third.bookmarks.single().positionMs)
            assertEquals(600L,third.quoteTargets.getValue(mark.id).positionMs);assertEquals(100L,third.passageOffsetMs)
            assertEquals(3L,store.importBundle(Uri.fromFile(files[0])).editionRevision)
            val player=MediaPlayer()
            try {
                player.setDataSource(store.file(third.id,third.tracks[0].file).absolutePath);player.prepare();player.start()
                delay(100);assertTrue("Updated MP3 must actually play",player.isPlaying)
            } finally { player.release() }
            success=true
        } finally {
            AudiobookController.stop(app);withTimeout(10000){while(AudiobookController.state.value.id!=null)delay(30)}
            store.remove(first.id);first.bookId?.let{app.library.removeBook(it)}
        }
        if(success) File(app.filesDir,"v017-studio-interop.json").writeText(JSONObject()
            .put("passed",true).put("partialCompletionNotFinished",true).put("revision1To2PreservesState",true)
            .put("revision3MapsCursorBookmarkQuote",true).put("olderRevisionIgnored",true).put("updatedMp3Played",true).toString(2),Charsets.UTF_8)
    }
}
