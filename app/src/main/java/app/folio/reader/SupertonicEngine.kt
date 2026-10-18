package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SupertonicVoice(val id: Int, val label: String)

/**
 * Native operations share Kokoro's worker. The service closes its old backend before preparing a
 * replacement, so its queued release finishes before the next large model is loaded.
 * Supertonic's pinned native implementation does not honor callback cancellation: cancelled
 * inference finishes on the worker, but its result is discarded and never moves a listening cursor.
 */
class SupertonicEngine(context: Context, private val assetDirectory: String = "supertonic") {
    private val app = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val useLock = Any()
    private var modelUse: java.io.Closeable? = null
    private fun acquireModelUse() = synchronized(useLock) {
        check(!closed.get()) { "The local voice engine has been closed." }
        if(modelUse==null)modelUse=VoiceModelUse.acquire(app,VERSION)
    }

    private val generation = AtomicLong(0)
    @Volatile private var files: File? = null
    @Volatile private var prepared = false
    val isPrepared: Boolean get() = prepared && !closed.get()
    // Exclusively accessed by LocalVoiceWorker, including destruction.
    private var native: OfflineTts? = null
    private var sampleRate = 0

    suspend fun prepare() {
        check(!closed.get()) { "The Supertonic voice engine has been closed." }
        acquireModelUse()
        val directory = prepareFiles()
        onWorker {
            if (native == null) {
                val engine = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = File(directory, "duration_predictor.int8.onnx").absolutePath,
                        textEncoder = File(directory, "text_encoder.int8.onnx").absolutePath,
                        vectorEstimator = File(directory, "vector_estimator.int8.onnx").absolutePath,
                        vocoder = File(directory, "vocoder.int8.onnx").absolutePath,
                        ttsJson = File(directory, "tts.json").absolutePath,
                        unicodeIndexer = File(directory, "unicode_indexer.bin").absolutePath,
                        voiceStyle = File(directory, "voice.bin").absolutePath
                    ), numThreads = 2, debug = false, provider = "cpu"
                ), maxNumSentences = 1))
                try {
                    val rate = engine.sampleRate()
                    check(rate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "The bundled Supertonic model has an unsupported audio sample rate." }
                    check(engine.numSpeakers() == voices.size) { "The bundled Supertonic voice pack is incomplete." }
                    sampleRate = rate
                    native = engine; prepared = true
                } catch (error: Throwable) {
                    engine.release()
                    throw error
                }
            }
        }
    }

    suspend fun generate(text: String, voiceId: Int, speed: Float, language: String): AiAudioClip {
        require(text.isNotBlank()) { "There is no text to read aloud." }
        require(text.length <= MAX_TEXT_CHARACTERS) { "This passage is too long for one Supertonic speech chunk." }
        require(voiceId in voices.indices) { "Choose one of the ten Supertonic voices." }
        require(speed.isFinite() && speed in .5f..2f) { "Choose a speech speed between 0.5× and 2×." }
        val lang = normalizeLanguage(language)
        val requested = generation.get()
        prepare()
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            continuation.invokeOnCancellation { cancelled.set(true) }
            LocalVoiceWorker.executor.execute {
                if (!continuation.isActive) return@execute
                try {
                    fun stopped() = cancelled.get() || closed.get() || requested != generation.get()
                    if (stopped()) throw CancellationException("Supertonic speech generation was stopped.")
                    val started = System.nanoTime()
                    var received = 0L
                    var exceeded = false
                    val audio = requireNotNull(native).generateWithConfigAndCallback(text,
                        GenerationConfig(sid = voiceId, speed = speed, numSteps = GENERATION_STEPS,
                            extra = mapOf("lang" to lang, "seed" to RANDOM_SEED.toString()))) { part ->
                        received += part.size
                        exceeded = received > sampleRate.toLong() * MAX_AUDIO_SECONDS
                        // Retain the cancellation signal if a future runtime honors its return.
                        if (stopped() || exceeded) 0 else 1
                    }
                    if (stopped()) throw CancellationException("Supertonic speech generation was stopped.")
                    check(!exceeded) { "This passage produced too much audio. Read it in shorter sentences." }
                    check(audio.sampleRate == sampleRate) { "Supertonic returned audio with an unexpected sample rate." }
                    validateAudio(audio.samples, audio.sampleRate)
                    continuation.resumeWith(Result.success(AiAudioClip(audio.samples, audio.sampleRate,
                        (System.nanoTime() - started) / 1_000_000, SpeechPcm.digest(audio.samples, audio.sampleRate))))
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    fun cancelGeneration() { generation.incrementAndGet() }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelGeneration(); prepared = false
        // Native release must wait for every previous inference, including cancelled calls.
        LocalVoiceWorker.executor.execute {
            val engine = native
            native = null
            sampleRate = 0
            try { engine?.release() } finally { synchronized(useLock) { modelUse?.close();modelUse=null } }
        }
    }

    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        LocalVoiceWorker.executor.execute {
            if (!continuation.isActive) return@execute
            try {
                check(!closed.get()) { "The Supertonic voice engine has been closed." }
                continuation.resumeWith(Result.success(block()))
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }

    private suspend fun prepareFiles(): File {
        files?.let { return it }
        return withContext(Dispatchers.IO) {
            val manifest = app.assets.open("$assetDirectory/artifacts.json").bufferedReader().use { JSONObject(it.readText()) }
            check(manifest.getString("revision") == MODEL_REVISION) { "The bundled Supertonic revision does not match this app." }
            val list = manifest.getJSONArray("files")
            val specs = (0 until list.length()).associate { index ->
                val item = list.getJSONObject(index)
                item.getString("name") to AssetSpec(item.getLong("bytes"), item.getString("sha256"))
            }
            check(REQUIRED_FILES.all { name -> specs[name]?.let { it.bytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")) } == true }) {
                "The bundled Supertonic integrity manifest is incomplete."
            }
            VerifiedSpeechAssets.prepare(app, assetDirectory, VERSION,
                REQUIRED_FILES.map { name -> specs.getValue(name).let { SpeechModelFile(name, it.bytes, it.sha256) } }, closed::get
            ) { directory -> validateFiles(directory, specs) }.also { files = it }
        }
    }
    companion object {
        // Includes inference settings because they affect PCM and persisted sample checkpoints.
        const val VERSION = "supertonic3-int8-20260511-cca5a0e6-sherpa1.13.8-steps8-seed42"
        const val MODEL_REVISION = "cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
        const val MAX_TEXT_CHARACTERS = 1200
        const val MAX_AUDIO_SECONDS = SpeechPcm.MAX_CLIP_SECONDS
        const val GENERATION_STEPS = 8
        const val RANDOM_SEED = 42
        private const val MIN_SAMPLE_RATE = 8000
        private const val MAX_SAMPLE_RATE = 48000
        private val installationMutex = Mutex()
        val voices = List(10) { SupertonicVoice(it, "Voice ${it + 1}") }
        private val REQUIRED_FILES = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")
        private data class AssetSpec(val bytes: Long, val sha256: String)

        internal fun normalizeLanguage(language: String): String {
            val code = language.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
            require(code == "da" || code == "en") { "Choose Danish or English for Supertonic speech." }
            return code
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        internal fun verifyCopiedAsset(bytes: Long, digest: ByteArray, expectedBytes: Long, expectedSha256: String) {
            check(bytes == expectedBytes && hex(digest) == expectedSha256) {
                "The bundled Supertonic files failed their integrity check. Reinstall this APK."
            }
        }

        private fun validateFiles(directory: File, specifications: Map<String, AssetSpec>) {
            check(REQUIRED_FILES.all { File(directory, it).length() == specifications.getValue(it).bytes }) {
                "The bundled Supertonic files are incomplete. Reinstall this APK."
            }
            check(REQUIRED_FILES.filter { it.endsWith(".onnx") }.all { File(directory, it).length() > 1_000_000 }) {
                "The bundled Supertonic models are incomplete. Reinstall this APK."
            }
            val rate = JSONObject(File(directory, "tts.json").readText()).getJSONObject("ae").getInt("sample_rate")
            check(rate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "The bundled Supertonic configuration has an unsupported sample rate." }
            check(File(directory, "unicode_indexer.bin").length() == 65536L * 4) { "The bundled Supertonic character index is incomplete." }
            val style = File(directory, "voice.bin")
            val header = ByteArray(48)
            style.inputStream().use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    check(count > 0) { "The bundled Supertonic voice styles are incomplete." }
                    read += count
                }
            }
            validateVoiceHeader(header, style.length())
        }

        /** Mirrors the native format checks so malformed voice assets fail before JNI exits. */
        internal fun validateVoiceHeader(header: ByteArray, length: Long) {
            check(header.size == 48) { "The bundled Supertonic voice header is incomplete." }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val dimensions = LongArray(6) { buffer.long }
            check(dimensions[0] == voices.size.toLong() && dimensions[3] == dimensions[0]) { "The bundled Supertonic voice pack is incomplete." }
            fun elements(start: Int): Long {
                var count = 1L
                for (index in start until start + 3) {
                    val dimension = dimensions[index]
                    check(dimension > 0 && dimension <= 16_777_216 / count) { "The bundled Supertonic voice dimensions are invalid." }
                    count *= dimension
                }
                return count
            }
            val payload = 4 * (elements(0) + elements(3))
            check(payload <= 64L * 1024 * 1024 && length == 48 + payload) { "The bundled Supertonic voice styles are incomplete." }
        }

        internal fun validateAudio(samples: FloatArray, sampleRate: Int) = SpeechPcm.validate(samples, sampleRate)
    }
}
