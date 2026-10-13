package app.folio.reader

/** Monotonic wall-clock deadline; its expiry does not depend on synthesis/playback speed. */
internal class SpeechSleepTimer {
    private var deadline: Long? = null
    var chapter: Int? = null
        private set
    fun clear() { deadline=null; chapter=null }
    fun armMinutes(minutes: Int, now: Long) { require(minutes in setOf(5,15,30,60)); chapter=null; deadline=now+minutes*60_000L }
    fun armChapter(index: Int) { require(index>=0); deadline=null; chapter=index }
    fun remainingSeconds(now: Long): Long? = deadline?.let { ((it-now).coerceAtLeast(0)+999)/1000 }
    fun disarmExpired(now: Long): Boolean { if(!expired(now)) return false; clear(); return true }
    fun expired(now: Long) = deadline?.let { now>=it } ?: false
}
