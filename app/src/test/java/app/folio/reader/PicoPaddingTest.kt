package app.folio.reader

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class PicoPaddingTest {
    private val rate = 24000
    private fun tone(seconds: Double, amplitude: Double = 0.15) = FloatArray((rate * seconds).toInt()) {
        (amplitude * sin(it * 2 * Math.PI * 220 / rate)).toFloat()
    }
    @Test fun removesBothQuietEdgesAndKeepsNaturalMargins() {
        val head = FloatArray(rate)
        val speech = tone(1.0)
        val tail = FloatArray(rate / 2)
        val raw = head + speech + tail
        val result = PicoPadding.trim(raw, rate)
        assertEquals(rate * 800 / 1000, result.removedStart)
        assertEquals(rate * 380 / 1000, result.removedEnd)
        assertArrayEquals(raw.copyOfRange(result.removedStart, raw.size - result.removedEnd), result.samples, 0f)
    }
    @Test fun preservesInternalPausesAndSoftConsonantsAdjacentToSpeech() {
        val onset = tone(0.12, 0.0008)
        val release = tone(0.08, 0.0008)
        val speech = onset + tone(0.4) + FloatArray(rate / 2) + tone(0.4) + release
        val raw = FloatArray(rate) + speech + FloatArray(rate)
        val result = PicoPadding.trim(raw, rate)
        val originalStart = rate - result.removedStart
        assertTrue(originalStart >= 0)
        assertArrayEquals(speech, result.samples.copyOfRange(originalStart, originalStart + speech.size), 0f)
    }
    @Test fun scalesThresholdForQuietRecordingsAndLeavesBriefUtterancesIntact() {
        val quiet = tone(0.8, 0.0001)
        val raw = FloatArray(rate) + quiet + FloatArray(rate)
        val result = PicoPadding.trim(raw, rate)
        val start = rate - result.removedStart
        assertArrayEquals(quiet, result.samples.copyOfRange(start, start + quiet.size), 0f)
        val brief = tone(0.08)
        assertSame(brief, PicoPadding.trim(brief, rate).samples)
        val silence = FloatArray(rate)
        assertSame(silence, PicoPadding.trim(silence, rate).samples)
    }
    @Test fun leavesUnpaddedAudioAloneAndDoesNotMutateInput() {
        val speech = tone(1.0)
        val copy = speech.copyOf()
        assertSame(speech, PicoPadding.trim(speech, rate).samples)
        assertArrayEquals(copy, speech, 0f)
    }
    @Test fun removesMeasuredDecoderPaddingFromThePublishedNativeReference() {
        val wav = requireNotNull(javaClass.classLoader!!.getResourceAsStream("pico-padding-reference.wav")).use { it.readBytes() }
        val data = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, data.getInt(24))
        data.position(44)
        val samples = FloatArray((wav.size - 44) / 2) { data.short.toFloat() / 32768f }
        val result = PicoPadding.trim(samples, rate)
        assertTrue("The real Pico clip should lose its long decoder pre-roll", result.removedStart >= rate * 8 / 10)
        assertTrue(result.removedStart <= rate * 12 / 10)
        assertTrue(result.removedEnd in (rate / 10)..(rate * 3 / 10))
        assertArrayEquals(samples.copyOfRange(result.removedStart, samples.size - result.removedEnd), result.samples, 0f)
    }
}
