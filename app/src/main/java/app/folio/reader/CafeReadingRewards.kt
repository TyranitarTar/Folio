package app.folio.reader

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Conservative union of simultaneous reading/listening; no offline or clock-change catch-up. */
internal class CafeRewardClock {
    private var last: Long? = null
    private var wasEligible = false
    fun tick(now: Long, eligible: Boolean): Long {
        val elapsed = last?.let { now - it } ?: 0
        val earned = if (wasEligible && eligible && elapsed in 1..2000) elapsed else 0
        last = now; wasEligible = eligible
        return earned
    }
}

/** No synthesis/preview rewards. Local speech supplies evidence of advancing presented PCM. */
internal class CafeReadingRewards(private val store: CafeStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var readerUntil = 0L
    private var spokenUntil = 0L
    private var systemUntil = 0L
    private var job: Job? = null
    private var clock = CafeRewardClock()
    private var session = UUID.randomUUID().toString()
    private var sequence = 1L
    private var pending = 0L
    @Volatile var savingError: Boolean = false
        private set
    fun reader(eligible: Boolean) {
        readerUntil = if (eligible) SystemClock.elapsedRealtime() + 1500 else 0
        if (eligible) start()
    }
    fun spoken() { spokenUntil = SystemClock.elapsedRealtime() + 750; start() }
    fun systemSpeech(playing: Boolean) { systemUntil = if(playing) SystemClock.elapsedRealtime() + 30_000 else 0; if (playing) start() }
    fun stopSpeech() { systemUntil = 0; spokenUntil = 0 }
    private fun start() {
        if(store.profiles.value.ready && !store.profiles.value.normal.enabled) return
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching { store.load() }
            var idle = 0
            while (isActive) {
                mutex.withLock {
                    val now = SystemClock.elapsedRealtime()
                    val enabled = store.profiles.value.normal.enabled
                    val eligible = enabled && (readerUntil > now || spokenUntil > now || systemUntil > now)
                    pending += clock.tick(now, eligible)
                    if (pending >= 14_000 || !eligible) flush()
                    idle = if (eligible) 0 else idle + 1
                }
                if (idle >= 3) break
                delay(1000)
            }
            clock = CafeRewardClock()
        }
    }
    private suspend fun flush() {
        if (pending == 0L) return
        val amount = pending.coerceAtMost(15_000)
        try { store.reading(session, sequence, amount); sequence++; pending -= amount; savingError = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { savingError = true; pending = pending.coerceAtMost(15_000) }
    }
    suspend fun export(): String = mutex.withLock { flush(); store.export() }
    suspend fun restore(data: String) = mutex.withLock {
        store.restore(data)
        pending = 0; clock = CafeRewardClock(); session = UUID.randomUUID().toString(); sequence = 1
    }
}
