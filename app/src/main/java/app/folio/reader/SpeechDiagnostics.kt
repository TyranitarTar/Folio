package app.folio.reader

/** Opt-in bounded timing trace for instrumentation. No text/audio or background disk logging. */
internal object SpeechDiagnostics {
    data class Event(val nanos: Long, val kind: String, val id: String, val frames: Int, val rate: Int, val originalFrames: Int)
    @Volatile private var enabled = false
    private val events = ArrayDeque<Event>()
    @Synchronized fun start() { events.clear(); enabled = true }
    @Synchronized fun stop(): List<Event> { enabled = false; return events.toList() }
    fun record(kind: String, id: String = "", frames: Int = 0, rate: Int = 0, originalFrames: Int = frames) {
        if (!enabled) return
        synchronized(this) {
            if (!enabled) return
            if (events.size == 4096) events.removeFirst()
            events.addLast(Event(System.nanoTime(), kind, id, frames, rate, originalFrames))
        }
    }
}
