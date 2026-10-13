package app.folio.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SupertonicEngineTest {
    private fun rejects(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Invalid model/audio data must fail before JNI or playback", error is IllegalStateException)
    }

    @Test fun native44100HzAudioRemainsPlayableAndDurationUsesTheReturnedRate() {
        val audio = FloatArray(4410) { (.25 * sin(it * .1)).toFloat() }
        val original = audio.copyOf()
        SupertonicEngine.validateAudio(audio, 44100)
        assertArrayEquals(original, audio, 0f)
        // At 8kHz this is over three minutes, though a hard-coded 24k limit would accept it.
        rejects { SupertonicEngine.validateAudio(FloatArray(8000 * 181) { .01f }, 8000) }
        rejects { SupertonicEngine.validateAudio(audio, 0) }
    }

    @Test fun invalidOrRailPinnedOutputFailsAndRareOvershootIsNormalized() {
        rejects { SupertonicEngine.validateAudio(floatArrayOf(Float.NaN), 44100) }
        rejects { SupertonicEngine.validateAudio(floatArrayOf(Float.NEGATIVE_INFINITY), 44100) }
        rejects { SupertonicEngine.validateAudio(FloatArray(1000), 44100) }
        rejects { SupertonicEngine.validateAudio(FloatArray(1000) { if (it % 2 == 0) 1f else -1f }, 44100) }
        val audio = FloatArray(1000) { (.25 * sin(it * .1)).toFloat() }
        audio[0] = 1.1f; audio[1] = -1.1f
        SupertonicEngine.validateAudio(audio, 44100)
        assertEquals(1f, audio[0], 0f)
        assertEquals(-1f, audio[1], 0f)
    }

    @Test fun truncatedOrChangedAssetCannotReceiveTheReadyStamp() {
        val data = "a verified model asset".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(data)
        val sha = expected.joinToString("") { "%02x".format(it.toInt() and 255) }
        SupertonicEngine.verifyCopiedAsset(data.size.toLong(), expected, data.size.toLong(), sha)
        rejects { SupertonicEngine.verifyCopiedAsset(data.size - 1L, expected, data.size.toLong(), sha) }
        val corrupted = data.copyOf().also { it[0] = 0 }
        rejects { SupertonicEngine.verifyCopiedAsset(corrupted.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(corrupted), data.size.toLong(), sha) }
    }

    @Test fun binaryVoiceHeaderRejectsWrongSpeakerCountTruncationAndDimensionOverflow() {
        fun header(vararg dimensions: Long) = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> dimensions.forEach { buffer.putLong(it) } }.array()
        val valid = header(10, 50, 256, 10, 8, 16)
        SupertonicEngine.validateVoiceHeader(valid, 517168)
        rejects { SupertonicEngine.validateVoiceHeader(valid, 517167) }
        rejects { SupertonicEngine.validateVoiceHeader(header(9, 50, 256, 9, 8, 16), 517168) }
        rejects { SupertonicEngine.validateVoiceHeader(header(10, Long.MAX_VALUE, 256, 10, 8, 16), 517168) }
        rejects { SupertonicEngine.validateVoiceHeader(header(10, 0, 256, 10, 8, 16), 517168) }
    }
}
