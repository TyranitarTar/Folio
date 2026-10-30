package app.folio.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SavedQuote0161Test {
    @Test fun existing_highlights_and_audio_quotes_round_trip_without_truncating_text() {
        val cue=AudioCue(1000,2000,"one.xhtml",text="A full quoted passage. ".repeat(30))
        val locator=RecordedNarration.locator(cue)!!
        val old=Mark("old",locator.toJSON().toString(),"Short label")
        val oldRestored=Mark.from(old.json())
        assertEquals(cue.text,oldRestored.quote);assertNull(oldRestored.audioId)
        val saved=old.copy(audioId="edition",audioTrack=2,audioPositionMs=1000)
        assertEquals(saved,Mark.from(JSONObject(saved.json().toString())))
        assertEquals(cue.text,Mark.from(saved.json()).quote)
    }
}
