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
import org.readium.r2.shared.publication.Locator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Interface014Test {
    private fun locator(href:String,fragment:String?=null,quote:String?=null):Locator {
        val json=JSONObject().put("href",href).put("type","application/xhtml+xml")
        if(fragment!=null)json.put("locations",JSONObject().put("fragments",JSONArray(listOf(fragment))))
        if(quote!=null)json.put("text",JSONObject().put("highlight",quote))
        return checkNotNull(Locator.fromJSON(json))
    }
    @Test fun manual_read_state_preserves_position_and_new_filter_survives_reload() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val book=Book("a".repeat(64),"Book","Writer",1,lastRead=2,progress=.4,readState="Read")
        val restored=Book.from(book.json())
        assertTrue(restored.isRead);assertEquals(.4,restored.progress,0.0)
        val prefs=HomePreferences(status="Unread");prefs.save(context)
        assertEquals("Reading",HomePreferences.load(context).status)
        assertTrue(prefs.visibleBooks(listOf(restored),"","All books").isEmpty())
        assertEquals(1,prefs.visibleBooks(listOf(restored.copy(readState="Unread")),"","All books").size)
    }
    @Test fun full_selected_quote_roundtrips_beyond_the_short_label() {
        val quote="En lang dansk passage med æ, ø og å. ".repeat(20)
        val mark=Mark("quote",locator("chapter.xhtml",quote=quote).toJSON().toString(),quote.take(200))
        assertEquals(quote,Mark.from(mark.json()).quote)
    }
    @Test fun chapter_counts_span_resources_and_use_rendered_boundaries() {
        val a=locator("a.xhtml");val b=locator("b.xhtml");val c=locator("c.xhtml")
        val pages=PageInformation(hrefs=listOf("a.xhtml","b.xhtml","c.xhtml"),counts=listOf(3,5,4))
        assertEquals("Chapter: page 5 of 8",ChapterPagination.label(pages,listOf(a,c),"b.xhtml",1))
        val first=locator("b.xhtml","first");val second=locator("b.xhtml","second")
        val bounds=mapOf(pageBoundaryKey(first) to 0,beforeBoundaryKey(first) to -1,pageBoundaryKey(second) to 3,beforeBoundaryKey(second) to 2)
        assertEquals("Chapter: page 3 of 3",ChapterPagination.label(pages.copy(boundaries=bounds),listOf(first,second,c),"b.xhtml",2))
        assertEquals("Chapter: page 1 of 2",ChapterPagination.label(pages.copy(boundaries=bounds),listOf(first,second,c),"b.xhtml",3))
        assertTrue(ChapterPagination.label(pages,listOf(first,second,c),"b.xhtml",2).contains("locating"))
    }
    @Test fun retired_guided_controls_cannot_stay_enabled_in_saved_settings() {
        val options=GuidedOptions(countdown=5,practiceMinutes=3,gradualIncrease=true).normalized()
        assertEquals(0,options.countdown);assertEquals(0,options.practiceMinutes);assertFalse(options.gradualIncrease)
    }
    @Test fun configurable_passages_cover_every_word_once_and_stop_at_chapter_boundaries() {
        val point=locator("a.xhtml")
        val words=(0 until 80).map{ReadingWord("word$it",point,7)}
        val sentences=(0 until 8).map{ReadingSentence("sentence",point,it*10,(it+1)*10)}
        val section=TextSection(point.href,"Chapter",words,sentences)
        val boundary=ReadingCursor(0,35,point)
        val book=BookText(listOf(section),chapters=listOf(ReadingCursor(0,0,point),boundary))
        val settings=SpeechSettings(backend="pico",passageTargetWords=30)
        val chunks=mutableListOf<List<ReadingWord>>()
        var index=0
        while(index<words.size) {
            val chunk=SpeechPassages.words(book,checkNotNull(book.cursor(0,index)),settings)
            assertTrue(chunk.isNotEmpty())
            if(index<35)assertTrue(index+chunk.size<=35)
            chunks+=chunk;index+=chunk.size
        }
        assertEquals(words,chunks.flatten())
        assertEquals(30,chunks.first().size)
    }

    @Test fun audiobook_new_and_manual_read_state_are_independent_of_cursor() {
        val track=AudioTrack("track.mp3","Track",10000)
        val book=Audiobook("a".repeat(64),"Book","",tracks=listOf(track))
        assertFalse(book.started);assertFalse(book.isRead)
        assertTrue(book.copy(readState="Read").isRead)
        assertFalse(book.copy(positionMs=10000,started=true,readState="Unread").isRead)
        assertTrue(book.copy(positionMs=10000,started=true).isRead)
    }
}
