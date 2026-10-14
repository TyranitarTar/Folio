package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK 35 AtomicFile uses POSIX rename-over-base; Windows host rename semantics differ.
// Real Android migration/repair is covered by SpeechStorageInstrumentedTest.
@Config(sdk = [28])
class SpeechRecoveryTest {
    private fun isolated(): Context {
        val root = Files.createTempDirectory("folio-speech-test").toFile()
        val original = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(original) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
    }
    @Test fun validLengthSilenceAndBitCorruptionAreRejectedWithoutChangingBookmark() {
        val context = isolated(); val store = AiListeningStore(context)
        val book = "a".repeat(64); val key = AiListeningStore.key("Healthy audio.", 21, 1f)
        store.write(book, AiListeningPoint(2, 14, key, 8))
        val file = File(context.cacheDir, "ai-audio/$key.pcm")
        DataOutputStream(file.outputStream()).use {
            it.writeInt(0x46414931); it.writeInt(24000); it.writeInt(24000)
            repeat(24000) { _ -> it.writeFloat(0f) }
        }
        assertNull(store.loadClip(key)); assertFalse(file.exists())
        assertEquals(8, store.read(book)!!.playedSamples)
        val clip = AiAudioClip(FloatArray(240) { if (it % 2 == 0) .1f else -.1f })
        store.saveClip(key, clip)
        RandomAccessFile(file, "rw").use { it.seek(80); it.writeFloat(.11f) }
        assertNull("Healthy-looking PCM with a bad digest must be discarded", store.loadClip(key))
        assertEquals(8, store.read(book)!!.playedSamples)
    }
    @Test fun atomicBackupIsRecoveredAndOldHealthyPcmIsMigrated() {
        val context = isolated(); val store = AiListeningStore(context)
        val key = AiListeningStore.key("Backup audio.", 21, 1f)
        val file = File(context.cacheDir, "ai-audio/$key.pcm")
        val samples = floatArrayOf(.1f, -.1f, .2f)
        DataOutputStream(File(file.path + ".bak").outputStream()).use {
            it.writeInt(0x46414931); it.writeInt(24000); it.writeInt(samples.size)
            samples.forEach(it::writeFloat)
        }
        val clip = store.loadClip(key)!!
        assertArrayEquals(samples, clip.samples, 0f)
        assertEquals(SpeechPcm.digest(samples, 24000), clip.digest)
        java.io.DataInputStream(file.inputStream()).use { assertEquals(0x46414932, it.readInt()) }
        assertFalse(File(file.path + ".bak").exists())
    }
    @Test fun androidResumeIgnoresDamagedOptionalAiAndAiRecoveryQuarantinesIt() = runBlocking {
        val context = isolated(); val id = "b".repeat(64)
        val book = File(context.filesDir, "book.epub").apply { writeText("fixture") }
        val damaged = File(context.filesDir, "reader-state/$id-ai.json").apply { parentFile!!.mkdirs(); writeText("broken") }
        val android = SpeechCheckpointCoordinator.begin(context, id, book, restore = true, includeAi = false)
        assertNull(android.ai); assertNull(android.recoveryNote); assertEquals("broken", damaged.readText())
        SpeechCheckpointCoordinator.end(android.session)
        val ai = SpeechCheckpointCoordinator.begin(context, id, book, restore = true, includeAi = true)
        assertNull(ai.ai); assertNotNull(ai.recoveryNote); assertFalse(damaged.exists())
        assertTrue(damaged.parentFile!!.listFiles()!!.any { it.name.startsWith("$id-ai-corrupt-") && it.readText() == "broken" })
        SpeechCheckpointCoordinator.end(ai.session)
    }
    @Test fun installedModelFileIsRehashedAndSameLengthCorruptionIsRepairedBeforeJni() = runBlocking {
        val context = isolated()
        // Small pinned metadata fixture exercises the same AtomicFile/SHA repair path in a core APK.
        val bytes=context.assets.open("kokoro/LICENSE").use { it.readBytes() }
        val spec = SpeechModelFile("LICENSE",bytes.size.toLong(),SpeechPcm.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
        val directory = VerifiedSpeechAssets.prepare(context, "kokoro", "integrity-fixture", listOf(spec), { false })
        val file = File(directory, spec.name); val expected = file.readBytes()
        RandomAccessFile(file, "rw").use { val first = it.read(); it.seek(0); it.write(first xor 1) }
        assertEquals(spec.bytes, file.length())
        VerifiedSpeechAssets.prepare(context, "kokoro", "integrity-fixture", listOf(spec), { false })
        assertArrayEquals(expected, file.readBytes())
        file.writeBytes(expected.copyOf(expected.size / 2))
        VerifiedSpeechAssets.prepare(context, "kokoro", "integrity-fixture", listOf(spec), { false })
        assertArrayEquals(expected, file.readBytes())
    }
    @Test fun thinApkMissingModelRequestsPackDownloadWithoutPublishingReadyMarker()=runBlocking {
        val context=isolated()
        val result=runCatching { VerifiedSpeechAssets.prepare(context,"kokoro","missing-model-fixture",
            listOf(SpeechModelFile("missing-model.onnx",1,"a".repeat(64))),{false}) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Download the Kokoro voice pack"))
        assertFalse(File(context.noBackupFilesDir,"local-voices/missing-model-fixture/.ready").exists())
    }
}
