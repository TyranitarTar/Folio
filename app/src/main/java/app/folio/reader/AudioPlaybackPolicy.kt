package app.folio.reader

internal fun remainingListeningMs(total:Long,elapsed:Long,speed:Float):Long? {
    if(total<=0)return null
    val rate=speed.takeIf{it.isFinite() && it>0} ?: 1f
    return ((total-elapsed.coerceAtLeast(0)).coerceAtLeast(0).toDouble()/rate).toLong()
}

/** Only the interrupted edition may resume; manual pause/stop/disconnect cancels it. */
internal class AudioInterruptionGate {
    private var generation:Long?=null
    fun interrupt(ticket:Long,wasPlaying:Boolean,enabled:Boolean){generation=if(wasPlaying && enabled)ticket else null}
    fun regain(ticket:Long):Boolean {val allowed=generation==ticket;generation=null;return allowed}
    fun cancel(){generation=null}
}
