package app.folio.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Includes current PCM, completed lookahead and a worst-case reservation for in-flight synthesis. */
internal class SpeechPcmBudget(
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxSeconds: Double = 180.0,
    private val reserveBytes: Long = SpeechPcm.MAX_CLIP_BYTES,
    private val reserveSeconds: Double = SpeechPcm.MAX_CLIP_SECONDS.toDouble()
) {
    private data class Allocation(val bytes: Long, val seconds: Double)
    private val lock = Any()
    private val slots = mutableMapOf<Any, Allocation>()
    private val active = Any()
    private val changes = MutableStateFlow(0L)
    suspend fun acquire(owner: Any) {
        while (true) {
            val revision = synchronized(lock) {
                if (slots.containsKey(owner)) return
                if (slots.values.sumOf { it.bytes } + reserveBytes <= maxBytes &&
                    slots.values.sumOf { it.seconds } + reserveSeconds <= maxSeconds) {
                    slots[owner] = Allocation(reserveBytes, reserveSeconds); changed(); return
                }
                changes.value
            }
            changes.first { it != revision }
        }
    }
    fun commit(owner: Any, bytes: Long, seconds: Double) = synchronized(lock) {
        require(bytes in 1..reserveBytes && seconds > 0 && seconds <= reserveSeconds)
        check(slots.containsKey(owner)) { "Missing PCM budget reservation." }
        slots[owner] = Allocation(bytes, seconds); changed()
    }
    fun activate(clip: AiAudioClip, offset: Int, previousOwner: Any? = null) = synchronized(lock) {
        previousOwner?.let { slots.remove(it) }
        slots[active] = Allocation(clip.samples.size.toLong() * 4,
            (clip.samples.size - offset.coerceIn(0, clip.samples.size)).toDouble() / clip.sampleRate)
        check(slots.values.sumOf { it.bytes } <= maxBytes && slots.values.sumOf { it.seconds } <= maxSeconds)
        changed()
    }
    fun updateActive(clip: AiAudioClip, offset: Int) = activate(clip, offset)
    fun release(owner: Any) = synchronized(lock) { slots.remove(owner); changed() }
    fun clearActive() = release(active)
    internal fun retainedBytes(): Long = synchronized(lock) { slots.values.sumOf { it.bytes } }
    private fun changed() { changes.value++ }
}
