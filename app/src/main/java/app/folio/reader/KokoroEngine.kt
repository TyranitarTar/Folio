package app.folio.reader

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class KokoroVoice(val id: Int, val name: String, val label: String, val language: String)

/**
 * One native FP32 model, owned exclusively by a serial worker. Cancelling an utterance suppresses
 * its result and stops between native batches; it never frees a model still executing inference.
 */
class KokoroEngine(context: Context, private val assetDirectory: String = "kokoro", private val cpuThreads: Int = DEFAULT_CPU_THREADS) {
    init { require(cpuThreads in 1..4) { "Choose between one and four CPU threads for the local voice." } }
    private val app = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile private var files: File? = null
    @Volatile private var prepared = false
    val isPrepared: Boolean get() = prepared && !closed.get()
    // Accessed only on the worker. JNI finalize/release must not race generate().
    private var native: OfflineTts? = null

    suspend fun prepare() {
        check(!closed.get()) { "The local voice engine has been closed." }
        val directory = prepareFiles()
        onWorker {
            if (native == null) {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(directory, "model.onnx").absolutePath,
                            voices = File(directory, "voices.bin").absolutePath,
                            tokens = File(directory, "tokens.txt").absolutePath,
                            dataDir = File(directory, "espeak-ng-data").absolutePath,
                            // Per-utterance eSpeak language keeps British/American pronunciation
                            // distinct without colliding dictionary entries or two loaded models.
                            lexicon = "", lang = "en"
                        ), numThreads = cpuThreads, debug = false, provider = "cpu"
                    ), maxNumSentences = 1
                )
                val created = OfflineTts(config = config)
                try {
                    check(created.sampleRate() == SAMPLE_RATE) { "The bundled local voice has an unexpected sample rate." }
                    check(created.numSpeakers() > voices.maxOf { it.id }) { "The bundled local voice pack is incomplete." }
                    native = created; prepared = true
                } catch (error: Throwable) { created.release(); throw error }
            }
        }
    }

    suspend fun generate(text: String, voiceId: Int, speed: Float): AiAudioClip {
        require(text.isNotBlank()) { "There is no text to read aloud." }
        require(text.length <= MAX_TEXT_CHARACTERS) { "This passage is too long for one local speech chunk." }
        require(speed.isFinite() && speed in .5f..3f) { "Choose a speech speed between 0.5× and 3×." }
        val voice = voices.firstOrNull { it.id == voiceId } ?: throw IllegalArgumentException("Choose Emma or Heart for local English speech.")
        val requested = generation.get()
        prepare()
        currentCoroutineContext().ensureActive()
        if (requested != generation.get()) throw CancellationException("Local speech generation was stopped.")
        return suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            continuation.invokeOnCancellation { cancelled.set(true) }
            try { worker.execute {
                if (!continuation.isActive) return@execute
                try {
                    if (closed.get() || requested != generation.get()) throw CancellationException("Local speech generation was stopped.")
                    val started = System.nanoTime()
                    var receivedSamples = 0L
                    var exceededLimit = false
                    val audio = requireNotNull(native).generateWithConfigAndCallback(text,
                        GenerationConfig(sid = voice.id, speed = speed, extra = mapOf("lang" to voice.language))) { part ->
                        receivedSamples += part.size
                        exceededLimit = receivedSamples > MAX_AUDIO_SAMPLES
                        if (cancelled.get() || closed.get() || requested != generation.get() || exceededLimit) 0 else 1
                    }
                    if (cancelled.get() || closed.get() || requested != generation.get()) throw CancellationException("Local speech generation was stopped.")
                    check(!exceededLimit && audio.samples.size <= MAX_AUDIO_SAMPLES) { "This passage produced too much audio. Read it in shorter sentences." }
                    check(audio.sampleRate == SAMPLE_RATE) { "The local voice returned an unsupported sample rate." }
                    validateAudio(audio.samples)
                    val clip = AiAudioClip(audio.samples, audio.sampleRate, (System.nanoTime() - started) / 1_000_000, SpeechPcm.digest(audio.samples, audio.sampleRate))
                    continuation.resumeWith(Result.success(clip))
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            } } catch (error: RejectedExecutionException) {
                continuation.resumeWithException(IllegalStateException("The local voice engine has been closed.", error))
            }
        }
    }

    fun cancelGeneration() { generation.incrementAndGet() }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelGeneration(); prepared = false
        // Release is queued after every preceding inference. Never destroy an in-flight JNI pointer.
        worker.execute { native?.release(); native = null }
    }

    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        try { worker.execute {
            if (!continuation.isActive) return@execute
            try {
                check(!closed.get()) { "The local voice engine has been closed." }
                continuation.resumeWith(Result.success(block()))
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        } } catch (error: RejectedExecutionException) {
            continuation.resumeWithException(IllegalStateException("The local voice engine has been closed.", error))
        }
    }

    private suspend fun prepareFiles(): File {
        files?.let { return it }
        return VerifiedSpeechAssets.prepare(app, assetDirectory, VERSION, KokoroAssetIdentity.files, closed::get)
            .also { files = it }
    }
    companion object {
        val DEFAULT_CPU_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        // Shared serialization prevents a quick stop/restart loading a second large model while
        // the previous inference is still finishing. Individual close releases its native model;
        // this tiny daemon worker remains available for the next service instance.
        private val worker = LocalVoiceWorker.executor
        private val assetMutex = Mutex()
        const val VERSION = "kokoro-v1_0-fp32-sherpa1.13.8"
        const val SAMPLE_RATE = 24000
        const val MAX_TEXT_CHARACTERS = 2800
        const val MAX_AUDIO_SAMPLES = SAMPLE_RATE * SpeechPcm.MAX_CLIP_SECONDS
        val voices = listOf(
            // Piper calls SetVoiceByName, which resolves voice names/file identifiers, not
            // BCP-47 language aliases. British English is file gmw/en (language en-gb).
            // American English is file gmw/en-US; lookup is case-insensitive.
            KokoroVoice(21, "bf_emma", "Emma · British English", "en"),
            KokoroVoice(3, "af_heart", "Heart · American English", "en-us")
        )

        internal fun validateAudio(samples: FloatArray) = SpeechPcm.validate(samples, SAMPLE_RATE)
    }
}
