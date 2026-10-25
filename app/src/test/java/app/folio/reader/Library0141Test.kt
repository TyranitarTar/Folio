package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Library0141Test {
    @Test fun audiobook_display_and_status_do_not_overwrite_reading_preferences() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        HomePreferences(layout="List",columns=4,status="Read").save(context)
        HomePreferences(layout="Compact",columns=2,status="New").save(context,"audioHome")
        val reading=HomePreferences.load(context);val audio=HomePreferences.load(context,"audioHome")
        assertEquals("List",reading.layout);assertEquals("Finished",reading.status);assertEquals(4,reading.columns)
        assertEquals("Compact",audio.layout);assertEquals("New",audio.status);assertEquals(2,audio.columns)
    }
    @Test fun audiobook_filters_sort_and_manual_status_preserve_the_listening_cursor() {
        val tracks=listOf(AudioTrack("track.wav","Chapter",10000))
        val new=Audiobook("a","Alpha","One",tracks=tracks)
        val reading=Audiobook("b","Beta","Two",tracks=tracks,started=true,positionMs=4000,lastPlayed=30)
        val read=reading.copy(id="c",title="Gamma",readState="Read",lastPlayed=20)
        val items=listOf(read,new,reading)
        assertEquals(listOf("a"),HomePreferences(status="New").visibleAudiobooks(items,"").map{it.id})
        assertEquals(listOf("b","a"),HomePreferences(status="Unread").visibleAudiobooks(items,"").map{it.id})
        assertEquals(listOf("c"),HomePreferences(status="Read").visibleAudiobooks(items,"").map{it.id})
        assertEquals(listOf("a","b","c"),HomePreferences(sort="Title",descending=false).visibleAudiobooks(items,"").map{it.id})
        assertEquals(listOf("b"),HomePreferences().visibleAudiobooks(items,"beta").map{it.id})
        assertEquals(4000L,read.positionMs);assertEquals(4000L,reading.positionMs)
    }
    @Test fun unknown_track_durations_cannot_mark_the_last_chapter_read_prematurely() {
        val book=Audiobook("a","Book","",tracks=listOf(AudioTrack("first.wav","First",10000),AudioTrack("last.wav","Last",0)),
            positionTrack=1,positionMs=2000,started=true)
        assertFalse(book.durationKnown);assertFalse(book.isRead)
        assertEquals("In progress",audioLibraryBadge(book))
    }
    @Test fun linked_media_requires_the_same_granted_content_tree() {
        val root=android.net.Uri.parse("content://test.documents/tree/root")
        assertTrue(validLinkedAudio(root,android.net.Uri.parse("content://test.documents/tree/root/document/track")))
        assertFalse(validLinkedAudio(root,android.net.Uri.parse("content://test.documents/tree/other/document/track")))
        assertFalse(validLinkedAudio(root,android.net.Uri.parse("file:///sdcard/track.mp3")))
        assertFalse(validLinkedAudio(root,android.net.Uri.parse("https://example.org/track.mp3")))
    }
    // Exercise the backup/rename AtomicFile API on Windows; actual SDK 35 IO is
    // covered by FolderLibrary0141InstrumentedTest on the emulator.
    @Config(sdk=[28])
    @Test fun folder_index_uses_no_audio_copy_and_relink_keeps_cursor_bookmarks_and_identity() = kotlinx.coroutines.runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=android.net.Uri.parse("content://test.documents/tree/root")
        fun tracks(tree: android.net.Uri)=listOf("CD 1/01.mp3" to android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"track1"),
            "CD 2/02.mp3" to android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"track2"))
        val store=AudiobookStore(context)
        // This provider does not exist: indexing must not open a media stream or decoder.
        val first=store.linkAudio(root,"book","metadata-v1","Book",tracks(root))
        assertFalse(first.durationKnown)
        store.savePosition(first.id,1,4000,1.5f)
        val mark=store.addBookmark(first.id,1,3500,"Saved point")
        store.recordHistory(first.id,1,3400,"Before seek")
        val privateFiles=java.io.File(context.filesDir,"audiobooks/${first.id}").listFiles().orEmpty()
        assertTrue(privateFiles.isNotEmpty());assertTrue(privateFiles.none{it.name.startsWith("track-")})
        val reloaded=AudiobookStore(context);reloaded.load()
        val restored=reloaded.books.value.first{it.id==first.id}
        assertEquals(4000L,restored.positionMs);assertEquals(mark,restored.bookmarks.single())
        val moved=android.net.Uri.parse("content://test.documents/tree/moved")
        val relinked=reloaded.linkAudio(moved,"book","metadata-v2","Book",tracks(moved),first.id)
        assertEquals(first.id,relinked.id);assertEquals(4000L,relinked.positionMs)
        assertEquals(1.5f,relinked.speed,.001f);assertEquals(mark,relinked.bookmarks.single())
        assertEquals(1,reloaded.books.value.count{it.id==first.id})
        assertEquals(moved.toString(),relinked.sourceRoot)
        val inserted="CD 0/00.mp3" to android.provider.DocumentsContract.buildDocumentUriUsingTree(moved,"intro")
        val refreshed=reloaded.linkAudio(moved,"book","metadata-v3","Book",listOf(inserted)+tracks(moved),knownId=relinked.id)
        assertEquals(first.id,refreshed.id);assertEquals(2,refreshed.positionTrack)
        assertEquals(4000L,refreshed.positionMs);assertEquals(2,refreshed.bookmarks.single().track)
        assertEquals(2,refreshed.history.single().track)
        val restarted=AudiobookStore(context);restarted.load()
        assertEquals(2,restarted.books.value.first{it.id==first.id}.positionTrack)
    }
}
