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
class Status015Test {
    @Test fun legacy_status_preferences_migrate_by_schema_without_rewriting_progress() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val preferences=context.getSharedPreferences("audioHome",0)
        preferences.edit().putInt("statusSchema",1).putString("status","Unread").commit()
        assertEquals("New",HomePreferences.load(context,"audioHome").status)
        preferences.edit().putInt("statusSchema",2).putString("status","Unread").commit()
        assertEquals("Reading",HomePreferences.load(context,"audioHome").status)
        preferences.edit().putInt("statusSchema",2).putString("status","Read").commit()
        assertEquals("Finished",HomePreferences.load(context,"audioHome").status)
        HomePreferences(status="Finished").save(context,"audioHome")
        assertEquals(3,preferences.getInt("statusSchema",0));assertEquals("Finished",HomePreferences.load(context,"audioHome").status)
    }
    @Test fun new_reading_finished_filters_are_disjoint_for_audio_and_epubs() {
        val tracks=listOf(AudioTrack("one.mp3","One",10000))
        val audio=listOf(Audiobook("new","New","",tracks=tracks),Audiobook("reading","Reading","",tracks=tracks,started=true,positionMs=4000),
            Audiobook("finished","Finished","",tracks=tracks,started=true,readState="Read",positionMs=4000))
        listOf("New","Reading","Finished").forEach{status->assertEquals(listOf(status.lowercase()),HomePreferences(status=status).visibleAudiobooks(audio,"").map{it.id})}
        val books=listOf(Book("new","New","",1),Book("reading","Reading","",1,lastRead=2,progress=.3),Book("finished","Finished","",1,lastRead=2,readState="Read",progress=.3))
        listOf("New","Reading","Finished").forEach{status->assertEquals(listOf(status.lowercase()),HomePreferences(status=status).visibleBooks(books,"","All books").map{it.id})}
        assertEquals(4000L,audio.last().positionMs);assertEquals(.3,books.last().progress,0.0)
    }
    @Test fun final_second_or_seeking_to_end_does_not_complete_new_audio() {
        val book=Audiobook("a","Book","",tracks=listOf(AudioTrack("one.mp3","One",10000)),started=true,positionMs=9500,completed=false)
        assertFalse(book.isRead);assertFalse(book.copy(positionMs=10000).isRead)
        assertTrue(book.copy(completed=true).isRead);assertTrue(book.copy(readState="Read").isRead)
        assertFalse(book.copy(completed=true,readState="Unread").isRead)
    }
}
