package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class CafeRewardClockTest {
    @Test fun overlappingReadingAndSpeechAreAUnionNotAMultiplier() {
        val clock = CafeRewardClock()
        var reward = 0L
        repeat(61) { i ->
            val reading = true; val listening = i in 10..50
            reward += clock.tick(i * 1000L, reading || listening)
        }
        assertEquals(60_000, reward)
    }
    @Test fun pauseSleepProcessRestartAndWallClockChangesDoNotBackfill() {
        val clock = CafeRewardClock()
        assertEquals(0, clock.tick(1000, true))
        assertEquals(1000, clock.tick(2000, true))
        assertEquals(0, clock.tick(3000, false))
        assertEquals(0, clock.tick(600_000, true))
        assertEquals(1000, clock.tick(601_000, true))
        assertEquals(0, clock.tick(10, true))
        assertEquals(1000, clock.tick(1010, true))
        assertEquals(0, CafeRewardClock().tick(9_999_999, true))
    }
}
