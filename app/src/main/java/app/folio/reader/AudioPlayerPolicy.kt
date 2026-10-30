package app.folio.reader

internal class PlayerPassageState : androidx.lifecycle.ViewModel() {
    val passage=SpokenPassage()
}

/** Retain only text whose cue has actually started, scoped to one book. */
internal class SpokenPassage {
    private var book: String? = null
    var cue: AudioCue? = null
        private set
    var track: Int = 0
        private set
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
