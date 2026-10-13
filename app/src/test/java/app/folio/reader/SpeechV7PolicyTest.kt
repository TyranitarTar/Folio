package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class SpeechV7PolicyTest {
    @Test fun supertonicPlaybackRateReusesCanonicalWaveformWhileKokoroKeepsNativeRateIdentity() {
        val normal=SpeechSettings(backend="supertonic")
        val fast=normal.copy(rate=1.5f)
        assertEquals(1f,fast.synthesisRate,0f); assertEquals(1.5f,fast.playbackRate,0f)
        assertTrue(fast.sameSource(normal)); assertEquals(normal.audioKey("Final consonants."),fast.audioKey("Final consonants."))
        assertNotEquals(fast.audioKey("Final consonants."),AiListeningStore.key("Final consonants.",0,1.5f,"supertonic","en"))
        val kokoro=SpeechSettings(backend="kokoro")
        assertFalse(kokoro.sameSource(kokoro.copy(rate=1.5f)))
        assertNotEquals(kokoro.audioKey("Final consonants."),kokoro.copy(rate=1.5f).audioKey("Final consonants."))
    }
    @Test fun sleepDeadlineIsMonotonicIndependentOfPlaybackAndChapterSelectionIsExplicit() {
        val timer=SpeechSleepTimer(); timer.armMinutes(5,1000)
        assertEquals(300L,timer.remainingSeconds(1000)); assertEquals(1L,timer.remainingSeconds(300999))
        assertFalse(timer.expired(300999)); assertTrue(timer.expired(301000)); assertEquals(0L,timer.remainingSeconds(302000))
        assertTrue(timer.disarmExpired(302000)); assertFalse(timer.expired(302000)); assertFalse(timer.disarmExpired(302000))
        timer.armChapter(4); assertEquals(4,timer.chapter); assertNull(timer.remainingSeconds(999999)); assertFalse(timer.expired(999999))
        timer.clear(); assertNull(timer.chapter); assertNull(timer.remainingSeconds(1000))
    }
}
