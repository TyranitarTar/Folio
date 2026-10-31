package app.folio.reader

internal data class DisplayPassage(val track: Int, val positionMs: Long, val cue: AudioCue?)

/** Positive offsets show each passage later than its recorded cue. */
internal fun displayPassage(book: Audiobook, track: Int, positionMs: Long): DisplayPassage {
    val index = track.coerceIn(book.tracks.indices)
    var at = positionMs.coerceAtLeast(0L) - book.passageOffsetMs.coerceIn(-30_000L, 30_000L)
    var target = index
    while (at < 0 && target > 0 && book.tracks[target - 1].durationMs > 0) {
        target--; at += book.tracks[target].durationMs
    }
    if (at < 0) return DisplayPassage(target, at, null)
    while (target < book.tracks.lastIndex && book.tracks[target].durationMs > 0 && at >= book.tracks[target].durationMs) {
        at -= book.tracks[target].durationMs; target++
    }
    val duration = book.tracks[target].durationMs
    if (duration > 0) at = at.coerceAtMost(duration)
    return DisplayPassage(target, at, book.tracks[target].cues.firstOrNull { at >= it.startMs && at < it.endMs })
}

/** Resolve the last started passage in a gap, including across known file boundaries. */
internal fun displayedCue(book: Audiobook, display: DisplayPassage): AudioCue? {
    display.cue?.let { return it }
    if (display.positionMs < 0) return null
    for (i in display.track downTo 0) {
        val limit=if(i==display.track) display.positionMs else book.tracks[i].durationMs
        if(i != display.track && limit <= 0) break
        book.tracks[i].cues.lastOrNull { it.startMs <= limit && it.text.isNotBlank() }?.let { return it }
    }
    return null
}

internal class PlayerPassageState : androidx.lifecycle.ViewModel() {
    val passage=SpokenPassage()
}

/** Retain only text whose cue has actually started, scoped to one book. */
internal class SpokenPassage {
    private var book: String? = null
    private var revision: Long? = null
    private var offset: Long? = null
    var cue: AudioCue? = null
        private set
    var track: Int = 0
        private set
    fun resolve(source: Audiobook, display: DisplayPassage, seekRevision: Long): AudioCue? {
        if (book != source.id || revision != seekRevision || offset != source.passageOffsetMs) {
            book=source.id; revision=seekRevision; offset=source.passageOffsetMs; cue=null
            if (display.positionMs >= 0) {
                for (i in display.track downTo 0) {
                    val limit=if(i==display.track) display.positionMs else source.tracks[i].durationMs
                    if(i != display.track && limit <= 0) break
                    val previous=source.tracks[i].cues.lastOrNull { it.startMs <= limit && it.text.isNotBlank() }
                    if(previous!=null) { cue=previous;track=i;break }
                }
            }
        }
        return update(source.id, display.track, display.cue, display.positionMs)
    }
    fun update(id: String, index: Int, current: AudioCue?, position: Long): AudioCue? {
        if (id != book) { book = id; cue = null }
        if (current != null && current.text.isNotBlank() && position >= current.startMs) {
            cue = current; track = index
        }
        return cue
    }
}

internal data class AudioSleepBoundary(val track: Int, val positionMs: Long, val label: String) {
    fun reached(index: Int, position: Long) = index > track || (index == track && position >= positionMs)
}

/** Timed mode protects active read-along and grants a fresh interaction window on pause. */
internal fun audioPlayerAwake(policy: ReaderAwakePolicy, foreground: Boolean, readAlong: Boolean,
    active: Boolean, now: Long, lastInteraction: Long): Boolean {
    if (!foreground || !readAlong) return false
    val normalized = policy.normalized()
    return when (normalized.mode) {
        "always" -> true
        "timed" -> active || now - lastInteraction < normalized.minutes * 60_000L
        else -> false
    }
}
