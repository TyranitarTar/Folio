package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer
import org.json.JSONObject

data class AiListeningPoint(val section: Int, val word: Int, val key: String, val playedSamples: Int,
    val audioDigest: String? = null)

/** Progress locks are independent of PCM IO; locks are shared across every Service/store instance. */
class AiListeningStore(context: Context) {
    private val positions = File(context.filesDir, "reader-state").apply { mkdirs() }
    private val audio = File(context.cacheDir, "ai-audio").apply { mkdirs() }
    private val cacheRoot = audio.canonicalPath
    private fun position(book: String): AtomicFile {
        require(book.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(positions, "$book-ai.json"))
    }
    private fun clipFile(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(audio, "$key.pcm")
    }
    fun read(book: String): AiListeningPoint? = synchronized(progressLock) {
        val file = position(book)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            require(json.getInt("version") == 1)
            val digest = json.optString("audioDigest", "").takeIf { it.isNotEmpty() }
            require(digest == null || digest.matches(Regex("[a-f0-9]{64}")))
            AiListeningPoint(json.getInt("section"), json.getInt("word"), json.getString("key"), json.getInt("played"), digest)
                .also { require(it.section >= 0 && it.word >= 0 && it.playedSamples >= 0); clipFile(it.key) }
        } catch (error: Exception) {
            throw IllegalStateException("The saved AI listening position could not be read. It has been kept unchanged.", error)
        }
    }
    fun recover(book: String): AiListeningPoint? = synchronized(progressLock) {
        try { return@synchronized read(book) } catch (_: Exception) {
            val file = position(book).baseFile
            val suffix = System.currentTimeMillis().toString() + "-" + System.nanoTime()
            for (candidate in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) {
                if (candidate.exists()) check(candidate.renameTo(File(positions, "$book-ai-corrupt-$suffix-" + candidate.name))) {
                    "The damaged audio checkpoint could not be preserved. Check available storage."
                }
            }
            null
        }
    }
    fun write(book: String, point: AiListeningPoint) = synchronized(progressLock) {
        require(point.section >= 0 && point.word >= 0 && point.playedSamples >= 0)
        require(point.audioDigest == null || point.audioDigest.matches(Regex("[a-f0-9]{64}")))
        clipFile(point.key)
        val json = JSONObject().put("version", 1).put("section", point.section).put("word", point.word)
            .put("key", point.key).put("played", point.playedSamples)
        point.audioDigest?.let { json.put("audioDigest", it) }
        DurableJson.write(position(book), json.toString().toByteArray(Charsets.UTF_8))
    }
    fun forget(book: String) = synchronized(progressLock) {
        val atomic = position(book)
        val variants = listOf(atomic.baseFile, File(atomic.baseFile.path + ".bak"), File(atomic.baseFile.path + ".new"))
        atomic.delete()
        variants.forEach { candidate ->
            check(!candidate.exists() || candidate.delete()) { "The saved audio checkpoint could not be removed. Check storage access." }
        }
        check(variants.none { it.exists() }) { "The saved audio checkpoint could not be removed. Check storage access." }
    }
    fun loadClip(key: String): AiAudioClip? = synchronized(cacheLock) {
        val atomic = AtomicFile(clipFile(key))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            var legacy = false
            val clip = DataInputStream(atomic.openRead().buffered()).use { input ->
                val magic = input.readInt()
                require(magic == MAGIC || magic == LEGACY_MAGIC)
                legacy = magic == LEGACY_MAGIC
                val rate = input.readInt(); val size = input.readInt()
                SpeechPcm.checkSize(rate, size)
                val headerBytes = if (legacy) 12L else 76L
                require(atomic.baseFile.length() == headerBytes + 4L * size)
                val expected = if (legacy) null else {
                    val storedKey = ByteArray(32).also { input.readFully(it) }
                    require(SpeechPcm.hex(storedKey) == key)
                    SpeechPcm.hex(ByteArray(32).also { input.readFully(it) })
                }
                val samples = FloatArray(size) { input.readFloat() }
                SpeechPcm.validate(samples, rate, normalize = false)
                val digest = SpeechPcm.digest(samples, rate)
                require(expected == null || expected == digest)
                atomic.baseFile.setLastModified(System.currentTimeMillis())
                AiAudioClip(samples, rate, 0, digest)
            }
            // Old caches are migrated only after passing the same checks as new synthesis.
            if (legacy) saveClip(key, clip)
            clip
        } catch (_: Exception) { atomic.delete(); null } // The text/audio checkpoint remains intact.
    }
    fun ensurePinned(key: String, clip: AiAudioClip, active: () -> Boolean = { true }) = synchronized(cacheLock) {
        if (!active()) return@synchronized
        synchronized(pinLock) { pinned.getOrPut(cacheRoot) { mutableSetOf() }.apply { clear(); add(key) } }
        if (!clipFile(key).isFile) saveClip(key, clip)
    }
    fun unpin(key: String? = null) = synchronized(pinLock) { if (key == null) pinned[cacheRoot]?.clear() else pinned[cacheRoot]?.remove(key); Unit }
    fun saveClip(key: String, clip: AiAudioClip) = synchronized(cacheLock) {
        SpeechPcm.validate(clip.samples, clip.sampleRate, normalize = false)
        val digest = SpeechPcm.digest(clip.samples, clip.sampleRate)
        require(clip.digest == null || clip.digest == digest)
        val atomic = AtomicFile(clipFile(key)); val out = atomic.startWrite()
        try {
            val stream = DataOutputStream(out.buffered())
            stream.writeInt(MAGIC); stream.writeInt(clip.sampleRate); stream.writeInt(clip.samples.size)
            stream.write(SpeechPcm.unhex(key)); stream.write(SpeechPcm.unhex(digest))
            clip.samples.forEach { stream.writeFloat(it) }; stream.flush()
            atomic.finishWrite(out)
        } catch (error: Throwable) { atomic.failWrite(out); throw error }
        verifyClipCommit(atomic.baseFile, key, clip.sampleRate, clip.samples.size, digest)
        val clips = audio.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.pcm")) }
        var bytes = clips.sumOf { it.length() }
        val livePins = synchronized(pinLock) { pinned[cacheRoot].orEmpty().toSet() }
        clips.filter { it != atomic.baseFile && it.name.removeSuffix(".pcm") !in livePins }.sortedBy { it.lastModified() }.forEach {
            if (bytes > CACHE_BYTES) { val length = it.length(); if (it.delete()) bytes -= length }
        }
    }
    /** Read committed base directly: openRead could discard an uncommitted .new before reporting it. */
    private fun verifyClipCommit(file: File, key: String, rate: Int, size: Int, expected: String) {
        check(file.isFile && file.length() == 76L + size.toLong() * 4) { "Generated speech audio could not be saved." }
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == MAGIC && input.readInt() == rate && input.readInt() == size) {
                "Generated speech audio could not be saved."
            }
            val storedKey = ByteArray(32).also { input.readFully(it) }
            val storedDigest = ByteArray(32).also { input.readFully(it) }
            check(SpeechPcm.hex(storedKey) == key && SpeechPcm.hex(storedDigest) == expected) {
                "Generated speech audio could not be saved."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(8).putInt(rate).putInt(size).array())
            val bytes = ByteArray(64 * 1024)
            var remaining = size.toLong() * 4
            while (remaining > 0) {
                val count = minOf(remaining, bytes.size.toLong()).toInt()
                input.readFully(bytes, 0, count); digest.update(bytes, 0, count); remaining -= count
            }
            check(input.read() == -1 && SpeechPcm.hex(digest.digest()) == expected) {
                "Generated speech audio could not be saved."
            }
        }
    }
    companion object {
        private val progressLock = Any()
        private val cacheLock = Any()
        // Main-thread unpin never waits for PCM disk IO. Lock order, when both are needed, is cache then pin.
        private val pinLock = Any()
        private val pinned = mutableMapOf<String, MutableSet<String>>()
        private const val LEGACY_MAGIC = 0x46414931
        private const val MAGIC = 0x46414932
        private const val CACHE_BYTES = 64L * 1024 * 1024
        /** Storage UI waits for any already-running cache operation before clearing generated PCM. */
        fun clearCache(context: Context) = synchronized(cacheLock) {
            val directory = File(context.cacheDir, "ai-audio")
            directory.listFiles().orEmpty().forEach { file ->
                check(!file.isFile || file.delete()) { "A generated audio cache file could not be removed." }
            }
            synchronized(pinLock) { pinned.remove(directory.canonicalPath) }
            Unit
        }
        // Keep existing text keys; legacy PCM is health-checked and migrated rather than invalidating positions.
        fun key(text: String, voice: Int, rate: Float, backend: String = "kokoro", language: String = "en", picoThreads: Int = 0): String {
            val identity = when (backend) {
                "pico" -> "${PicoEngine.VERSION}/normalization-v1/native-ort1.28.2/sampling-t07-p095-k50-seed42/${PicoPadding.IDENTITY}/${if(picoThreads==0) "" else "threads-$picoThreads/"}$language"
                "kokoro" -> "kokoro-v1.0-fp32/sherpa-v1.13.8"
                "supertonic" -> "supertonic-3-int8/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4/sherpa-v1.13.8/steps8/seed42/$language"
                else -> throw IllegalArgumentException("Only bundled speech has cached audio.")
            }
            return MessageDigest.getInstance("SHA-256").digest("$identity|$voice|$rate|$text".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
