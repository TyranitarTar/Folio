package app.folio.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordedNarrationTest {
    @Test fun audio_cue_uses_the_reading_resource_weights_instead_of_audio_percentage() {
        fun point(href:String,total:Double,local:Double=0.0)=checkNotNull(Locator.fromJSON(JSONObject().put("href",href).put("type","application/xhtml+xml")
            .put("locations",JSONObject().put("totalProgression",total).put("progression",local))))
        val source=point("b.xhtml",0.0,.5)
        val positions=listOf(point("a.xhtml",0.0),point("b.xhtml",.25),point("b.xhtml",.5,.5),point("c.xhtml",.75))
        val resolved=RecordedNarration.readingLocator(source,positions)
        assertEquals(.5,resolved.locations.totalProgression!!,0.00001)
        assertEquals(source.href,resolved.href);assertEquals(source.text,resolved.text)
        assertEquals(source,RecordedNarration.readingLocator(source,emptyList()))
    }
    private fun source(before: String = "", after: String = "", fragment: String = "p1"): Locator =
        Locator.fromJSON(JSONObject().put("href", "EPUB/chapter1.xhtml").put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("cssSelector", "#$fragment"))
            .put("text", JSONObject().put("highlight", "Yes.").put("before", before).put("after", after)))!!
    private fun book(cues: List<AudioCue>, id: String = "test", preview: Boolean = false, last: Long = 0) =
        Audiobook(id, "Test", "Author", "epub-id", listOf(AudioTrack("track-0001.wav", "Chapter", 5000, cues)), lastPlayed = last, preview = preview)

    @Test fun repeatedSentencesUseBothSidesOfSourceContextWithinTheSameParagraph() {
        val first = AudioCue(0, 1000, "EPUB/chapter1.xhtml", "p1", "Yes.", "The first question. ", "She nodded.")
        val second = AudioCue(1000, 2000, "EPUB/chapter1.xhtml", "p1", "Yes.", "The second question. ", "He laughed.")
        val selected = RecordedNarration.find(book(listOf(first, second)), source("The second question. ", "He laughed."))
        assertEquals(RecordedNarration.Target(0, 1000), selected)
        assertEquals("Yes.", RecordedNarration.locator(second)?.text?.highlight)
    }

    @Test fun anAmbiguousRepeatedQuoteIsRejectedInsteadOfJumpingToAnArbitrarySentence() {
        val cue = AudioCue(0, 1000, "EPUB/chapter1.xhtml", "p1", "Yes.")
        assertNull(RecordedNarration.find(book(listOf(cue, cue.copy(startMs = 1000, endMs = 2000))), source()))
    }

    @Test fun aPreviewCannotMatchAnUnrecordedParagraphThatContainsTheSameWords() {
        val cue = AudioCue(0, 1000, "EPUB/chapter1.xhtml", "p1", "Yes.")
        assertNull(RecordedNarration.find(book(listOf(cue), preview = true), source(fragment = "p3")))
    }

    @Test fun completedRecordingsTakePriorityOverPreviewsUnlessOneWasExplicitlySelected() {
        val preview = book(emptyList(), "preview", preview = true, last = 99)
        val complete = book(emptyList(), "complete", last = 1)
        assertEquals("complete", RecordedNarration.preferred(listOf(preview, complete), "epub-id")?.id)
        assertEquals("preview", RecordedNarration.preferred(listOf(preview, complete), "epub-id", "preview")?.id)
    }
}
