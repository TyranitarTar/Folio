package app.folio.reader

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.Build
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One synthesized passage. Samples are mono PCM and must not change during playback. */
data class AiAudioClip(val samples: FloatArray, val sampleRate: Int = 24000, val synthesisMillis: Long = 0, val digest: String? = null)
enum class AiPlaybackPhase { STOPPED, BUFFERING, PLAYING, PAUSED, COMPLETE, ERROR }
data class AiPlaybackState(
    val utteranceId: String? = null, val phase: AiPlaybackPhase = AiPlaybackPhase.STOPPED,
    val playedSamples: Int = 0, val totalSamples: Int = 0, val sampleRate: Int = 24000, val error: String? = null, val playbackRate: Float = 1f
)

internal data class AiPcmTimestamp(val frames: Long, val nanoTime: Long)
internal interface AiPcmTrack {
    fun play()
    fun setPlaybackRate(rate: Float) { require(rate == 1f) { "This output does not support pitch-preserving speed." } }
    fun pause()
    fun write(samples: FloatArray, offset: Int, count: Int): Int
    /** Render counter; it is not proof that all downstream output has been heard. */
    fun playedFrames(): Long
    fun timestamp(): AiPcmTimestamp? = null
    fun fallbackSafetyFrames(): Int = 0
    fun discontinuity(): Long = 0
    fun release()
}

private class AndroidAiPcmTrack(private val sampleRate: Int) : AiPcmTrack {
    private val track: AudioTrack
    private val audioTimestamp = AudioTimestamp()
    private var playbackRate = 1f
    private var lastHead = 0L
    private var headWrap = 0L
    private val routeEpoch = AtomicLong()
    private val routeListener = AudioRouting.OnRoutingChangedListener { routeEpoch.incrementAndGet() }
    init {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minimum > 0) { "This device cannot play local voice audio." }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 3, sampleRate * 2)) // Larger than 2x minimum; 500ms source PCM.
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("The device's audio output could not be initialized.")
        }
        track.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
    }
    override fun discontinuity(): Long = (routeEpoch.get() shl 32) xor track.underrunCount.toLong()
    override fun setPlaybackRate(rate: Float) {
        track.playbackParams = PlaybackParams().allowDefaults().setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
            .setPitch(1f).setSpeed(rate)
        check(kotlin.math.abs(track.playbackParams.pitch - 1f) < .001f && kotlin.math.abs(track.playbackParams.speed - rate) < .001f) {
            "This output could not preserve pitch at this speed. Try 1.0x."
        }
        playbackRate = rate
    }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun write(samples: FloatArray, offset: Int, count: Int) = track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun playedFrames(): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (raw < lastHead && lastHead - raw > 0x80000000L) headWrap += 0x100000000L
        lastHead = raw
        return headWrap + raw
    }
    override fun timestamp(): AiPcmTimestamp? {
        if (!track.getTimestamp(audioTimestamp)) return null
        // AudioTrack timestamps expose the low 32 frame-counter bits. Match the render epoch.
        val head = headWrap + lastHead
        var frame = headWrap + (audioTimestamp.framePosition and 0xffffffffL)
        if (frame - head > 0x80000000L) frame -= 0x100000000L
        else if (head - frame > 0x80000000L) frame += 0x100000000L
        return AiPcmTimestamp(frame, audioTimestamp.nanoTime)
    }
    override fun fallbackSafetyFrames(): Int {
        val type = track.routedDevice?.type
        val wireless = type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) ||
            (Build.VERSION.SDK_INT >= 31 && type in setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER))
        // Without timestamps Android provides only an approximate render position. Delay that
        // estimate by at least one buffer (1s on wireless); this is a guard, not measured latency.
        return maxOf((track.bufferSizeInFrames / playbackRate).toInt(), if (wireless) sampleRate else sampleRate / 4)
    }
    override fun release() { track.removeOnRoutingChangedListener(routeListener); track.release() }
}

/** Maps committed frames to presentation time; never advances beyond submitted/rendered PCM. */
internal class AiPresentationClock(private val sampleRate: Int) {
    private var playbackRate = 1.0
    private var anchor: AiPcmTimestamp? = null
    private val renderHistory = ArrayDeque<Pair<Long, Long>>()
    private var presented = 0L
    private var rendered = 0L
    private var priorWritten = 0L
    private var minimumTimestampTime = 0L
    private var requiredFrame = -1L
    fun setPlaybackRate(rate: Float, now: Long) {
        require(rate.isFinite() && rate in .5f..2f)
        playbackRate = rate.toDouble()
        resetTimestamp(now)
    }
    fun resetTimestamp(now: Long = System.nanoTime()) {
        anchor = null; renderHistory.clear()
        minimumTimestampTime = now; requiredFrame = presented
    }
    fun position(rawHead: Long, written: Long, timestamp: AiPcmTimestamp?, now: Long, safetyFrames: Int): Long {
        // The old queue drained before new PCM arrived. Idle time is not elapsed speech time.
        if (priorWritten > 0 && presented >= priorWritten && written > priorWritten) resetTimestamp(now)
        priorWritten = written
        rendered = maxOf(rendered, rawHead.coerceIn(0, written))
        renderHistory.addLast(now to rendered)
        val prior = anchor
        if (timestamp != null && timestamp.frames in 0..written && timestamp.frames > requiredFrame &&
            timestamp.nanoTime >= minimumTimestampTime && timestamp.nanoTime > 0 &&
            timestamp.nanoTime - now <= 5_000_000_000L &&
            (prior == null || (timestamp.frames >= prior.frames && timestamp.nanoTime >= prior.nanoTime))) {
            anchor = timestamp
        }
        val stamp = anchor
        val valid = stamp != null && now - stamp.nanoTime <= 500_000_000L
        val estimate = if (valid) {
            val elapsed = (now - stamp!!.nanoTime).coerceIn(-5_000_000_000L, 500_000_000L)
            stamp.frames + kotlin.math.floor(elapsed.toDouble() * sampleRate * playbackRate / 1_000_000_000.0).toLong()
        } else {
            val cutoff = now - safetyFrames.coerceAtLeast(0).toLong() * 1_000_000_000L / sampleRate
            var delayed = presented
            while (renderHistory.isNotEmpty() && renderHistory.first.first <= cutoff) delayed = renderHistory.removeFirst().second
            delayed
        }
        val oldest = now - maxOf(2_000_000_000L, safetyFrames.toLong() * 1_000_000_000L / sampleRate)
        while (renderHistory.size > 1 && renderHistory.first.first < oldest) renderHistory.removeFirst()
        presented = maxOf(presented, estimate.coerceIn(0, minOf(written, rendered)))
        return presented
    }
}


/**
 * Natural passages append to one AudioTrack FIFO. Only play/stop/seek replaces that stream, and
 * replacement waits for its old worker to release the old track before creating another.
 * Checkpoints use the timestamp presentation estimate; unsupported routes use a guarded render
 * estimate. Neither completion nor a new passage releases a track with output still queued.
 */
class AiAudioPlayer internal constructor(
    private val listener: Listener,
    private val trackFactory: (Int) -> AiPcmTrack,
    private val deliver: (() -> Unit) -> Unit,
    private val worker: ExecutorService,
    private val nanoTime: () -> Long = System::nanoTime
) {
    interface Listener {
        fun onStarted(utteranceId: String) {}
        fun onPosition(utteranceId: String, playedSamples: Int, sampleRate: Int, totalSamples: Int) {}
        fun onCompleted(utteranceId: String) {}
        fun onError(utteranceId: String, message: String) {}
    }
    constructor(listener: Listener) : this(listener, ::AndroidAiPcmTrack,
        { block -> Handler(Looper.getMainLooper()).post(block) },
        Executors.newSingleThreadExecutor { task -> Thread(task, "Folio audio playback").apply { isDaemon = true } })

    private class Passage(clip: AiAudioClip, val startSample: Int, val baseFrame: Long,
        var id: String, var epoch: Long) {
        val totalSamples = clip.samples.size
        val sampleRate = clip.sampleRate
        var samples: FloatArray? = clip.samples
        var written = 0
        var presented = 0
        var started = false
        var startAfterFrame = 0
        var completed = false
        var lastPositionNanos = 0L
    }
    private class Queued(val clip: AiAudioClip, val passage: Passage)
    private class Stream(val sampleRate: Int, var passage: Passage) {
        val queued = ArrayDeque<Queued>()
        var track: AiPcmTrack? = null
        var paused = false
        var written = 0L
        val clock = AiPresentationClock(sampleRate)
        var discontinuity = -1L
    }
    private val lock = Object()
    private var stream: Stream? = null
    private var callbackEpoch = 0L
    private var closed = false
    private var playbackRate = 1f
    private val mutableState = MutableStateFlow(AiPlaybackState())
    val state = mutableState.asStateFlow()

    private fun validate(clip: AiAudioClip, startSample: Int) {
        SpeechPcm.checkSize(clip.sampleRate, clip.samples.size)
        require(startSample in 0..clip.samples.size) { "Invalid listening audio position." }
    }

    /** Explicit replacement: discard old queued PCM before starting the new audio stream. */
    fun play(clip: AiAudioClip, utteranceId: String, startSample: Int = 0) {
        validate(clip, startSample)
        val created = synchronized(lock) {
            check(!closed) { "The audio player has been closed." }
            stopLocked()
            Stream(clip.sampleRate, Passage(clip, startSample, 0, utteranceId, ++callbackEpoch)).also {
                stream = it
                mutableState.value = snapshot(it.passage, AiPlaybackPhase.BUFFERING)
            }
        }
        worker.execute { run(created) }
    }

    /** At most 32 already-budgeted passages can enter the FIFO before presentation completion.
     * Its callbacks remain disabled until append activates it after the previous COMPLETE callback.
     * The service retains its existing lookahead reservation until that activation. */
    fun queueNext(clip: AiAudioClip): Boolean {
        validate(clip, 0)
        return synchronized(lock) {
            val current = stream ?: return@synchronized false
            if (closed || current.paused || current.sampleRate != clip.sampleRate) return@synchronized false
            if (current.queued.any { it.clip.samples === clip.samples }) return@synchronized true
            if (current.queued.size >= MAX_QUEUED_PASSAGES) return@synchronized false
            val previous = current.queued.peekLast()?.passage ?: current.passage
            val base = previous.baseFrame + previous.totalSamples - previous.startSample
            current.queued.addLast(Queued(clip, Passage(clip, 0, base, "", 0)))
            lock.notifyAll()
            true
        }
    }

    /** Natural continuation only. False requires an explicit play (e.g. sample rate changed). */
    fun append(clip: AiAudioClip, utteranceId: String, startSample: Int = 0): Boolean {
        validate(clip, startSample)
        return synchronized(lock) {
            val current = stream ?: return@synchronized false
            if (closed || current.paused || !current.passage.completed || current.track == null ||
                current.sampleRate != clip.sampleRate) return@synchronized false
            val queued = current.queued.peekFirst()
            if (queued != null) {
                if (startSample != 0 || queued.clip.samples !== clip.samples) return@synchronized false
                current.passage = queued.passage.apply { id = utteranceId; epoch = ++callbackEpoch }
                current.queued.removeFirst()
                // PCM already shares a continuous frame timeline. Keep its presentation anchor.
            } else {
                current.clock.resetTimestamp(nanoTime())
                current.passage = Passage(clip, startSample, current.written, utteranceId, ++callbackEpoch)
            }
            mutableState.value = snapshot(current.passage, AiPlaybackPhase.BUFFERING)
            lock.notifyAll()
            true
        }
    }

    fun pause(): Int = synchronized(lock) {
        val current = stream ?: return@synchronized mutableState.value.playedSamples
        if (!current.paused) runCatching { updatePresented(current) }
        if (current.queued.isNotEmpty()) {
            // Future PCM may be in the hardware FIFO. Rebuild only the current passage from the
            // conservative presented offset, so resume cannot play a cancelled future passage.
            val passage = current.passage
            val samples = passage.samples
            val id = passage.id
            val position = stopLocked()
            if (samples != null && position < passage.totalSamples) {
                val held = Stream(current.sampleRate, Passage(AiAudioClip(samples, current.sampleRate),
                    position, 0, id, ++callbackEpoch)).also { it.paused = true }
                stream = held
                mutableState.value = snapshot(held.passage, AiPlaybackPhase.PAUSED)
                worker.execute { run(held) }
            } else mutableState.value = mutableState.value.copy(phase = AiPlaybackPhase.PAUSED)
            return@synchronized position
        }
        current.paused = true
        current.track?.let { runCatching { it.pause() } }
        current.clock.resetTimestamp(nanoTime())
        current.passage.epoch = ++callbackEpoch
        mutableState.value = snapshot(current.passage, AiPlaybackPhase.PAUSED)
        current.passage.startSample + current.passage.presented
    }

    fun resume(utteranceId: String? = null): Boolean = synchronized(lock) {
        val current = stream ?: return@synchronized false
        if (current.passage.completed) return@synchronized false
        if (!current.paused) return@synchronized true
        val passage = current.passage
        if (utteranceId != null) passage.id = utteranceId
        passage.epoch = ++callbackEpoch
        passage.startAfterFrame = passage.presented
        passage.started = false
        current.clock.resetTimestamp(nanoTime())
        current.paused = false
        try { current.track?.play() } catch (error: Exception) {
            failLocked(current, error)
            return@synchronized false
        }
        mutableState.value = snapshot(passage, AiPlaybackPhase.BUFFERING)
        lock.notifyAll()
        true
    }

    /** Speed changes retain source PCM/sample positions and queued passage bases. */
    fun setPlaybackRate(rate: Float): Boolean = synchronized(lock) {
        require(rate.isFinite() && rate in .5f..2f)
        check(!closed)
        if (rate==playbackRate) return@synchronized true
        val current = stream
        if (current != null && !current.paused) runCatching { updatePresented(current) }
        try {
            current?.track?.setPlaybackRate(rate)
            if (current?.paused == true) current.track?.pause()
            playbackRate = rate
            current?.clock?.setPlaybackRate(rate, nanoTime())
            mutableState.value = mutableState.value.copy(playbackRate = rate)
            true
        } catch (error: Exception) {
            if (current != null) failLocked(current, IllegalStateException("Pitch-preserving playback at this speed is unavailable. Try 1.0x.", error))
            false
        }
    }
    /** Conservative wall-clock guard for final output/timestretch tail before natural EOF stop. */
    fun tailGuardMillis(): Long = synchronized(lock) {
        val current = stream ?: return@synchronized 250L
        ((current.track?.fallbackSafetyFrames() ?: (current.sampleRate / 4)).toLong() * 1000 / current.sampleRate).coerceIn(250L, 1500L)
    }

    fun stop(): Int = synchronized(lock) { stopLocked() }
    private fun stopLocked(): Int {
        val current = stream
        if (current != null) {
            if (!current.paused) runCatching { updatePresented(current) }
            current.paused = true
            current.track?.let { runCatching { it.pause() } }
            current.queued.clear()
            current.passage.samples = null
        }
        val position = current?.passage?.let { it.startSample + it.presented } ?: mutableState.value.playedSamples
        ++callbackEpoch
        stream = null
        mutableState.value = mutableState.value.copy(phase = AiPlaybackPhase.STOPPED, playedSamples = position)
        lock.notifyAll()
        return position
    }

    fun close() {
        synchronized(lock) { if (closed) return; stopLocked(); closed = true }
        worker.shutdown()
    }

    private fun updatePresented(current: Stream) {
        val track = current.track ?: return
        val change = track.discontinuity()
        if (change != current.discontinuity) {
            current.discontinuity = change; current.clock.resetTimestamp(nanoTime())
        }
        val absolute = current.clock.position(track.playedFrames(), current.written,
            runCatching { track.timestamp() }.getOrNull(), nanoTime(), track.fallbackSafetyFrames())
        fun update(passage: Passage) {
            passage.presented = maxOf(passage.presented, (absolute - passage.baseFrame).coerceIn(0, passage.written.toLong()).toInt())
        }
        update(current.passage)
        current.queued.forEach { update(it.passage) }
    }
    private fun snapshot(passage: Passage, phase: AiPlaybackPhase) = AiPlaybackState(passage.id, phase,
        passage.startSample + passage.presented, passage.totalSamples, passage.sampleRate, playbackRate = playbackRate)

    private fun post(epoch: Long, action: () -> Unit) = deliver {
        if (synchronized(lock) { !closed && callbackEpoch == epoch }) action()
    }
    private fun failLocked(current: Stream, error: Exception) {
        if (stream !== current || closed) return
        runCatching { current.track?.pause() }
        current.queued.clear(); current.passage.samples=null
        stream = null
        val passage = current.passage
        val id = passage.id
        val message = error.message ?: "Local speech audio could not be played."
        mutableState.value = snapshot(passage, AiPlaybackPhase.ERROR).copy(error = message)
        post(passage.epoch) { listener.onError(id, message) }
        lock.notifyAll()
    }

    companion object { internal const val MAX_QUEUED_PASSAGES = 32 }
    private fun run(current: Stream) {
        var track: AiPcmTrack? = null
        try {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            if (!synchronized(lock) { stream === current && !closed }) return
            track = trackFactory(current.sampleRate)
            synchronized(lock) {
                if (stream !== current || closed) return
                current.track = track
                track.setPlaybackRate(playbackRate)
                current.clock.setPlaybackRate(playbackRate, nanoTime())
                if (!current.paused) track.play() else track.pause()
            }
            while (true) {
                synchronized(lock) {
                    if (stream !== current || closed) return
                    val passage = current.passage
                    if (current.paused) {
                        lock.wait(50)
                        return@synchronized
                    }
                    val remaining = passage.totalSamples - passage.startSample
                    fun submit(target: Passage) {
                        val unsent = target.totalSamples - target.startSample - target.written
                        if (unsent <= 0) return
                        val count = minOf(4096, unsent)
                        val accepted = track.write(requireNotNull(target.samples), target.startSample + target.written, count)
                        check(accepted in 0..count) { "The device stopped accepting voice audio (error $accepted)." }
                        target.written += accepted; current.written += accepted
                    }
                    submit(passage)
                    if (passage.written == remaining) for (queued in current.queued) {
                        submit(queued.passage)
                        if (queued.passage.written < queued.passage.totalSamples) break
                    }
                    updatePresented(current)
                    if (passage.completed) {
                        lock.wait(20)
                        return@synchronized
                    }
                    val id = passage.id
                    val epoch = passage.epoch
                    if (!passage.started && passage.presented > passage.startAfterFrame) {
                        passage.started = true
                        post(epoch) { listener.onStarted(id) }
                    }
                    val now = nanoTime()
                    if (passage.started && (now - passage.lastPositionNanos >= 100_000_000L || passage.presented == remaining)) {
                        passage.lastPositionNanos = now
                        val played = passage.startSample + passage.presented
                        val rate = passage.sampleRate
                        val total = passage.totalSamples
                        mutableState.value = snapshot(passage, AiPlaybackPhase.PLAYING)
                        post(epoch) { listener.onPosition(id, played, rate, total) }
                    }
                    if (passage.written == remaining && passage.presented == remaining) {
                        passage.completed = true
                        passage.samples = null
                        mutableState.value = snapshot(passage, AiPlaybackPhase.COMPLETE)
                        // Keep the same live track. Its FIFO timeline is reused by append().
                        post(epoch) { listener.onCompleted(id) }
                    }
                }
                Thread.sleep(20)
            }
        } catch (error: Exception) {
            synchronized(lock) { failLocked(current, error) }
        } finally {
            synchronized(lock) { current.track = null }
            track?.let { runCatching { it.pause() }; runCatching { it.release() } }
        }
    }
}
