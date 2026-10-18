package app.folio.reader

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal object PicoNative {
    init { System.loadLibrary("onnxruntime"); System.loadLibrary("folio_pico") }
    external fun open(folder: String, threads: Int): Long
    external fun generate(handle: Long, prompt: IntArray, prefix: FloatArray, speaker: FloatArray, eos: Int, audioStart: Int, seed: Int, cancellation: AtomicLong, expected: Long): FloatArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
    external fun profile(handle: Long): LongArray
}

internal class PicoEngine(context: Context, requestedThreads: Int = 0) : LocalSpeechEngine {
    val inferenceThreads = if (requestedThreads == 0) Runtime.getRuntime().availableProcessors().coerceIn(1,4) else requestedThreads.also { require(it in 1..8) }
    @Volatile var lastProfile: LongArray = LongArray(7); private set
    private var modelLease: java.io.Closeable? = null
    override val backend = "pico"
    private val app = context.applicationContext
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    @Volatile private var native = 0L
    @Volatile private var ready = false
    override val prepared get() = ready && !closed.get()
    private var tokenizer: PicoTokenizer? = null
    private var voices: List<Pair<FloatArray, FloatArray>> = emptyList()

    override suspend fun prepare() {
        if (prepared) return
        synchronized(this) { check(!closed.get()) { "Pico engine is closed." }; if (modelLease == null) modelLease = VoiceModelUse.acquire(app, VERSION) }
        val files = VerifiedSpeechAssets.prepare(app, "pico", VERSION, PicoPackIdentity.files, closed::get)
        onWorker {
            if (native == 0L) {
                val tokens = PicoTokenizer(File(files, "tokenizer.json").readText())
                val metadata = JSONArray(File(files, "voices.json").readText())
                val loaded = (0 until metadata.length()).map { i ->
                    val voice = metadata.getJSONObject(i)
                    require(voice.getInt("id") == i)
                    fun array(name: String, count: Int): FloatArray = voice.getJSONArray(name).let { values ->
                        require(values.length() == count)
                        FloatArray(count) { n -> values.getDouble(n).toFloat().also { require(it.isFinite()) } }
                    }
                    array("prefix", 576) to array("embedding", 128)
                }
                require(loaded.size == voiceNames.size)
                val handle = PicoNative.open(files.absolutePath, inferenceThreads)
                if (closed.get()) { PicoNative.close(handle); throw CancellationException("Pico preparation cancelled") }
                tokenizer = tokens; voices = loaded; native = handle; ready = true
            }
        }
    }
    override suspend fun generate(text: String, settings: SpeechSettings): AiAudioClip {
        require(text.isNotBlank() && text.length <= 400) { "Choose a shorter passage for Pico." }
        require(settings.picoVoice in voiceNames.indices) { "Choose an installed Danish Pico voice." }
        val request = generation.get()
        prepare(); currentCoroutineContext().ensureActive()
        val normalized = normalize(text)
        return onWorker {
            if (request != generation.get()) throw CancellationException("Pico generation cancelled")
            val tokens = requireNotNull(tokenizer)
            val voice = voices[settings.picoVoice]
            val started = System.nanoTime()
            SpeechDiagnostics.record("pico.synthesis.start")
            val raw = try { PicoNative.generate(native, tokens.prompt(normalized), voice.first, voice.second, tokens.eos, tokens.audioStart, 42, generation, request) }
                catch (error: Exception) { if (request != generation.get() || closed.get()) throw CancellationException("Pico generation cancelled"); throw error }
            lastProfile = PicoNative.profile(native)
            if (request != generation.get()) throw CancellationException("Pico generation cancelled")
            SpeechPcm.validate(raw, 24000, normalize = false)
            val samples = PicoPadding.trim(raw, 24000).samples
            SpeechPcm.validate(samples, 24000, normalize = false)
            val clip = AiAudioClip(samples, 24000, (System.nanoTime() - started) / 1_000_000, SpeechPcm.digest(samples, 24000))
            SpeechDiagnostics.record("pico.synthesis.end", clip.digest.orEmpty(), samples.size, 24000, raw.size)
            clip
        }
    }
    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancelGeneration() }
        LocalVoiceWorker.executor.execute {
            if (!continuation.isActive) return@execute
            try {
                check(!closed.get()) { "Pico engine is closed." }
                val result = block()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
    override fun cancelGeneration() { generation.incrementAndGet(); native.takeIf { it != 0L }?.let(PicoNative::cancel) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false; cancelGeneration()
        LocalVoiceWorker.executor.execute {
            try { val old = native; native = 0; if (old != 0L) PicoNative.close(old); voices = emptyList(); tokenizer = null }
            finally { synchronized(this) { modelLease?.close(); modelLease = null } }
        }
    }
    companion object {
        const val VERSION = "pico-int8-r100k-40060c99-kanade961f20bf-hift-v1"
        val voiceNames = listOf("Ida", "Tor", "Liv", "Ask", "Kaj")
        private val numbers = Regex("(?<![\\p{L}\\p{N}])[+-]?\\d{1,12}(?:,\\d{1,6})?(?![\\p{L}\\p{N}])")
        private val small = listOf("nul", "en", "to", "tre", "fire", "fem", "seks", "syv", "otte", "ni", "ti", "elleve", "tolv", "tretten", "fjorten", "femten", "seksten", "sytten", "atten", "nitten")
        private val tens = listOf("", "", "tyve", "tredive", "fyrre", "halvtreds", "tres", "halvfjerds", "firs", "halvfems")
        private fun spell(value: Long): String {
            if (value < 20) return small[value.toInt()]
            if (value < 100) return (if (value % 10L == 0L) "" else small[(value % 10).toInt()] + "og") + tens[(value / 10).toInt()]
            if (value < 1000) return (if (value / 100 == 1L) "et" else spell(value / 100)) + " hundrede" + if (value % 100 == 0L) "" else " og " + spell(value % 100)
            val scale = when { value >= 1_000_000_000 -> 1_000_000_000L; value >= 1_000_000 -> 1_000_000L; else -> 1000L }
            val count = value / scale
            val label = when(scale) { 1000L -> "tusind"; 1_000_000L -> if(count==1L) "million" else "millioner"; else -> if(count==1L) "milliard" else "milliarder" }
            val prefix = if(count==1L) (if(scale==1000L) "et" else "en") else spell(count)
            val rest = value % scale
            return "$prefix $label" + if(rest==0L) "" else (if(rest<100) " og " else " ") + spell(rest)
        }
        internal fun normalize(text: String): String {
            val spoken = numbers.replace(text.trim().replace(Regex("\\s+"), " ")) { match ->
                val parts = match.value.trimStart('+', '-').split(',')
                (if(match.value.startsWith('-')) "minus " else if(match.value.startsWith('+')) "plus " else "") + spell(parts[0].toLong()) + if (parts.size == 2) " komma " + parts[1].map { small[it.digitToInt()] }.joinToString(" ") else ""
            }
            return if (spoken.lastOrNull() in listOf('.', '!', '?', '…', '”', '»', '"')) spoken else "$spoken."
        }
    }
}
