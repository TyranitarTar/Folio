package app.folio.reader

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Limits include cached PCM, so a valid header cannot request an unbounded allocation. */
internal object SpeechPcm {
    const val MAX_CLIP_SECONDS = 90
    const val MAX_CLIP_BYTES = 16L * 1024 * 1024
    fun checkSize(sampleRate: Int, samples: Int) {
        check(sampleRate in 8000..48000 && samples > 0 &&
            samples.toLong() <= sampleRate.toLong() * MAX_CLIP_SECONDS &&
            samples.toLong() * 4 <= MAX_CLIP_BYTES) { "This voice passage is too large. Use shorter passages." }
    }
    fun validate(samples: FloatArray, sampleRate: Int, normalize: Boolean = true) {
        checkSize(sampleRate, samples.size)
        var peak = 0f; var sum = 0.0; var clipped = 0
        for (sample in samples) {
            check(sample.isFinite()) { "The local voice produced invalid audio." }
            val level = abs(sample); peak = maxOf(peak, level); sum += sample
            if (level >= .98f) clipped++
        }
        check(peak >= .00001f) { "The local voice produced silence for this passage." }
        check(peak <= 2f && clipped.toDouble() / samples.size < .10 && abs(sum / samples.size) < .2) {
            "The local voice produced distorted audio."
        }
        if (normalize) for (index in samples.indices) samples[index] = samples[index].coerceIn(-1f, 1f)
        else check(samples.all { it in -1f..1f }) { "Cached speech is outside the playable PCM range." }
    }
    fun digest(samples: FloatArray, sampleRate: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(64 * 1024)
        bytes.putInt(sampleRate).putInt(samples.size)
        for (sample in samples) {
            if (bytes.remaining() < 4) { digest.update(bytes.array(), 0, bytes.position()); bytes.clear() }
            bytes.putInt(sample.toRawBits())
        }
        digest.update(bytes.array(), 0, bytes.position())
        return hex(digest.digest())
    }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(value: String): ByteArray {
        require(value.matches(Regex("[a-f0-9]{64}")))
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
