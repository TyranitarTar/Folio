package app.folio.reader

import android.content.Context

data class GuidedOptions(val mode: String = "Window", val words: Int = 4,
    val punctuation: Boolean = false, val longWords: Boolean = false, val countdown: Int = 0,
    val practiceMinutes: Int = 0, val gradualIncrease: Boolean = false, val navigationUnit: String = "sentence") {
    fun normalized() = copy(mode = mode.takeIf { it in listOf("Window", "Groups", "Line") } ?: "Window",
        navigationUnit = navigationUnit.takeIf { it in listOf("sentence", "paragraph") } ?: "sentence",
        words = words.coerceIn(1, 8), countdown = countdown.coerceIn(0, 5), practiceMinutes = practiceMinutes.coerceIn(0, 30))
    fun save(context: Context) { val o = normalized(); context.getSharedPreferences("pace", 0).edit()
        .putString("mode", o.mode).putInt("focusWords", o.words).putBoolean("punctuation", o.punctuation)
        .putBoolean("longWords", o.longWords).putInt("countdown", o.countdown)
        .putString("navigationUnit", o.navigationUnit).putInt("practiceMinutes", o.practiceMinutes).putBoolean("gradualIncrease", o.gradualIncrease).apply() }
    companion object { fun load(context: Context): GuidedOptions {
        val p = context.getSharedPreferences("pace", 0)
        return GuidedOptions(p.getString("mode", "Window") ?: "Window", p.getInt("focusWords", 4),
            p.getBoolean("punctuation", false), p.getBoolean("longWords", false), p.getInt("countdown", 0),
            p.getInt("practiceMinutes", 0), p.getBoolean("gradualIncrease", false), p.getString("navigationUnit", "sentence") ?: "sentence").normalized()
    } }
}

data class GuidedSession(val words: Int = 0, val elapsedMillis: Long = 0) {
    val effectiveWpm: Int get() = if (elapsedMillis > 0) (words * 60000L / elapsedMillis).toInt() else 0
}

/** Consumption is distinct from display size: overlapping windows consume exactly one word. */
internal object GuidedTiming {
    fun consumed(mode: String, size: Int, available: Int): Int =
        (if (mode == "Window") 1 else size).coerceIn(1, available.coerceAtLeast(1))
    fun milliseconds(unit: String, speed: Double, words: List<Pair<String, Int>>, punctuation: Boolean,
        longWords: Boolean, paragraphEnd: Boolean): Double {
        val safe = speed.takeIf { it.isFinite() && it > 0 } ?: 200.0
        val base = words.sumOf { (text, count) ->
            val ms = if (unit == "CPS") 1000.0 * count.coerceAtLeast(1) / safe else 60000.0 / safe
            ms * if (longWords && unit == "WPM") (text.codePointCount(0, text.length) / 6.0).coerceIn(1.0, 2.0) else 1.0
        }
        val last = words.lastOrNull()?.first.orEmpty().trimEnd('"', '\'', '”', '’', ')', ']')
        val pause = if (!punctuation) 0.0 else when {
            paragraphEnd -> 300.0
            last.endsWith(".") || last.endsWith("!") || last.endsWith("?") -> 200.0
            last.endsWith(",") || last.endsWith(";") || last.endsWith(":") -> 100.0
            else -> 0.0
        }
        return (base + pause).coerceAtLeast(20.0)
    }
}
