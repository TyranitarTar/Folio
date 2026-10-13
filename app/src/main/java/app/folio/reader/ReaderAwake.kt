package app.folio.reader

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity policy only; it neither changes the system timeout nor wakes/unlocks the device. */
data class ReaderAwakePolicy(val mode: String="timed",val minutes: Int=10) {
    fun normalized()=copy(mode=mode.takeIf { it in modes } ?: "timed",minutes=minutes.takeIf { it in presets } ?: 10)
    companion object {
        val modes=listOf("device","timed","always")
        val presets=listOf(2,5,10,15,30)
    }
}

/** Save the last real input, not a fresh deadline on every layout or Activity recreation. */
internal class ReaderAwakeSession(lastInput: Long,now: Long) {
    var lastInput: Long=lastInput.takeIf { it in 0..now } ?: now
        private set
    fun interacted(now: Long) { if(now>=lastInput) lastInput=now }
    fun remaining(policy: ReaderAwakePolicy,now: Long): Long =
        (lastInput+policy.normalized().minutes*60_000L-now).coerceAtLeast(0)
    fun protected(policy: ReaderAwakePolicy,now: Long,foreground: Boolean,reading: Boolean,guided: Boolean): Boolean {
        if(!foreground || !reading) return false
        if(guided) return true
        return when(policy.normalized().mode) { "always" -> true; "timed" -> remaining(policy,now)>0; else -> false }
    }
}

internal class ReaderAwakeController(private val scope: CoroutineScope,lastInput: Long,
    private val clock: ()->Long = SystemClock::elapsedRealtime,private val applyFlag: (Boolean)->Unit) {
    val session=ReaderAwakeSession(lastInput,clock())
    private var policy=ReaderAwakePolicy()
    private var foreground=false
    private var reading=false
    private var guided=false
    private var applied=false
    private var timer: Job?=null
    private var closed=false
    fun policy(value: ReaderAwakePolicy) { val next=value.normalized(); if(next==policy) return; policy=next; timer?.cancel();timer=null;refresh() }
    fun foreground(value: Boolean) { foreground=value; refresh() }
    fun reading(value: Boolean) { reading=value; refresh() }
    fun guided(value: Boolean) { guided=value; refresh() }
    fun interact() { session.interacted(clock());refresh() }
    private fun refresh() {
        val enabled=!closed && session.protected(policy,clock(),foreground,reading,guided)
        if(enabled!=applied) { applied=enabled;applyFlag(enabled) }
        val timed=enabled && !guided && policy.mode=="timed"
        if(!timed) { timer?.cancel();timer=null;return }
        if(timer?.isActive==true) return
        timer=scope.launch {
            // Input updates the deadline without cancelling/recreating a Job for every drag event.
            while(true) {
                val remaining=session.remaining(policy,clock())
                if(remaining<=0) break
                delay(remaining)
            }
            timer=null;refresh()
        }
    }
    fun close() { closed=true;timer?.cancel();timer=null;refresh() }
}
