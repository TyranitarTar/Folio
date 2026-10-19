package app.folio.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** Source locators address the unchanged EPUB, never estimated words-per-minute time. */
internal object RecordedNarration {
    fun locator(cue: AudioCue): Locator? = runCatching {
        val locations = JSONObject()
        cue.fragment?.takeIf { it.isNotBlank() }?.let { locations.put("fragments", org.json.JSONArray().put(it)) }
        cue.cssSelector?.takeIf { it.isNotBlank() }?.let { locations.put("cssSelector", it) }
        cue.progression?.let { locations.put("progression", it.coerceIn(0.0, 1.0)) }
        Locator.fromJSON(JSONObject().put("href", cue.href).put("type", "application/xhtml+xml")
            .put("locations", locations).put("text", JSONObject().put("highlight", cue.text)
                .put("before", cue.before).put("after", cue.after)))
    }.getOrNull()

    fun preferred(books: List<Audiobook>, bookId: String, activeId: String? = null): Audiobook? {
        val matches = books.filter { it.bookId == bookId }
        return matches.firstOrNull { it.id == activeId }
            ?: matches.sortedWith(compareBy<Audiobook> { it.preview }.thenByDescending { it.lastPlayed }).firstOrNull()
    }

    data class Target(val track: Int, val positionMs: Long)
    private fun normalized(value: String?) = value.orEmpty().replace(Regex("\\s+"), " ").trim()
    private fun path(value: String) = android.net.Uri.decode(value.substringBefore('#').removePrefix("./"))
    fun find(book: Audiobook, source: Locator?): Target? {
        if (source == null) return Target(book.positionTrack, book.positionMs)
        val href = path(source.href.toString())
        val selector = source.locations["cssSelector"] as? String
        val quote = normalized(source.text.highlight)
        val before = normalized(source.text.before).takeLast(60)
        val fragment = source.locations.fragments.firstOrNull()
            ?: selector?.takeIf { it.matches(Regex("#[A-Za-z_][A-Za-z0-9_:-]*")) }?.removePrefix("#")
        val progression = source.locations.progression
        data class Match(val target: Target, val score: Int, val distance: Double, val quoteOffset: Int)
        val available = book.tracks.flatMap { it.cues }.filter { path(it.href) == href }
        if (fragment != null && available.any { !it.fragment.isNullOrBlank() } && available.none { it.fragment == fragment }) return null
        val after = normalized(source.text.after).take(60)
        fun commonSuffix(left: String, right: String): Int {
            var count = 0
            while (count < minOf(left.length, right.length, 60) && left[left.lastIndex - count] == right[right.lastIndex - count]) count++
            return count
        }
        fun commonPrefix(left: String, right: String): Int {
            var count = 0
            while (count < minOf(left.length, right.length, 60) && left[count] == right[count]) count++
            return count
        }
        val matches = book.tracks.flatMapIndexed { index, track -> track.cues.filter { path(it.href) == href }.map { cue ->
            var score = 0
            if (!selector.isNullOrBlank() && cue.cssSelector == selector) score += 12
            if (!fragment.isNullOrBlank() && cue.fragment == fragment) score += 12
            val text = normalized(cue.text)
            val within = if (quote.isNotBlank()) text.indexOf(quote) else -1
            val containing = if (text.isNotBlank() && quote.isNotBlank()) quote.indexOf(text) else -1
            if (within >= 0 || containing >= 0) score += 8
            if (within >= 0) {
                val prefix = (normalized(cue.before) + " " + text.take(within)).trimEnd()
                val suffix = (text.drop(within + quote.length) + " " + normalized(cue.after)).trimStart()
                score += commonSuffix(prefix, before) + commonPrefix(suffix, after)
            } else {
                score += commonSuffix(normalized(cue.before), before) + commonPrefix(normalized(cue.after), after)
            }
            Match(Target(index, cue.startMs), score,
                if (progression != null && cue.progression != null) kotlin.math.abs(progression - cue.progression) else 0.0,
                containing.coerceAtLeast(0))
        } }
        if (matches.isEmpty()) return null
        if (quote.isBlank() && selector == null && fragment == null && progression == null) return matches.first().target
        val ordered = matches.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.distance }.thenBy { it.quoteOffset })
        val best = ordered.first()
        if (best.score == 0 && (quote.isNotBlank() || selector != null || fragment != null)) return null
        if (best.score == 0 && progression != null && available.mapNotNull { it.progression }.maxOrNull()?.let { progression > it + .02 } == true) return null
        if (quote.isNotBlank() && ordered.drop(1).any { it.score == best.score && kotlin.math.abs(it.distance - best.distance) < .000001 && it.quoteOffset == best.quoteOffset && it.target != best.target }) return null
        return best.target
    }
}
