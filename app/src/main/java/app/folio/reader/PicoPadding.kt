package app.folio.reader

import kotlin.math.sqrt

/** Edge-only cleanup for Pico's low-level decoder padding. Never changes interior pauses. */
internal object PicoPadding {
    const val IDENTITY = "edge-padding-v1"
    data class Result(val samples: FloatArray, val removedStart: Int, val removedEnd: Int)

    fun trim(samples: FloatArray, rate: Int): Result {
        require(rate > 0 && samples.isNotEmpty())
        val window = maxOf(1, rate / 100) // 10 ms; decisions retain whole windows.
        val levels = DoubleArray((samples.size + window - 1) / window)
        for (frame in levels.indices) {
            val start = frame * window
            val end = minOf(samples.size, start + window)
            var energy = 0.0
            for (i in start until end) energy += samples[i].toDouble() * samples[i]
            levels[frame] = sqrt(energy / (end - start))
        }
        // -48 dBFS, lowered for quiet recordings so global volume does not erase speech.
        val threshold = minOf(0.003981071706, (levels.maxOrNull() ?: 0.0) * 0.025118864315)
        val first = levels.indexOfFirst { it > threshold }
        val last = levels.indexOfLast { it > threshold }
        if (first < 0) return Result(samples, 0, 0)
        // Preserve soft attacks, unvoiced consonants, breaths and natural release.
        var start = maxOf(0, first * window - rate / 5) // 200 ms before detected activity.
        var end = minOf(samples.size, (last + 1) * window + rate * 120 / 1000)
        val minimumCut = rate * 60 / 1000
        if (start < minimumCut) start = 0
        if (samples.size - end < minimumCut) end = samples.size
        if (end - start < rate / 5 || (start == 0 && end == samples.size)) return Result(samples, 0, 0)
        return Result(samples.copyOfRange(start, end), start, samples.size - end)
    }
}
