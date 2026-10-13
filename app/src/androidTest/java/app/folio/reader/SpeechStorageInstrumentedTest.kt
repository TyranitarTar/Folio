package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real POSIX AtomicFile replacement; no model initialization or artificial voice timings. */
@RunWith(AndroidJUnit4::class)
class SpeechStorageInstrumentedTest {
    @Test fun actualAtomicFileRepairsSameLengthModelAndMigratesRecoveredPcm() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "speech-storage-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        }
        try {
            val spec = KokoroAssetIdentity.files.first { it.name == "tokens.txt" }
            val directory = VerifiedSpeechAssets.prepare(context, "kokoro", "device-fixture", listOf(spec), { false })
            val token = File(directory, spec.name); val expected = token.readBytes()
            RandomAccessFile(token, "rw").use { val first = it.read(); it.seek(0); it.write(first xor 1) }
            VerifiedSpeechAssets.prepare(context, "kokoro", "device-fixture", listOf(spec), { false })
            assertArrayEquals(expected, token.readBytes())
            token.writeBytes(expected.copyOf(expected.size / 2))
            VerifiedSpeechAssets.prepare(context, "kokoro", "device-fixture", listOf(spec), { false })
            assertArrayEquals(expected, token.readBytes())
            val store = AiListeningStore(context); val key = AiListeningStore.key("Device cache repair.", 21, 1f)
            val file = File(context.cacheDir, "ai-audio/$key.pcm")
            DataOutputStream(File(file.path + ".bak").outputStream()).use { out ->
                out.writeInt(0x46414931); out.writeInt(24000); out.writeInt(3)
                out.writeFloat(.1f); out.writeFloat(-.1f); out.writeFloat(.2f)
            }
            val recovered = store.loadClip(key)!!
            assertEquals(SpeechPcm.digest(recovered.samples, 24000), recovered.digest)
            DataInputStream(file.inputStream()).use { assertEquals(0x46414932, it.readInt()) }
            assertFalse(File(file.path + ".bak").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedRenameCannotPretendToCommitCheckpointOrPcmAndForgetIsVerified() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = File(app.cacheDir, "speech-false-commit-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try {
            val store = AiListeningStore(context)
            val book = "e".repeat(64); val key = AiListeningStore.key("False commit.", 21, 1f)
            val base = File(context.filesDir, "reader-state/$book-ai.json")
            check(base.mkdir()); File(base, "obstruction").writeText("preserve me")
            assertTrue("finishWrite may only log rename failure; the store must throw", runCatching {
                store.write(book, AiListeningPoint(0, 2, key, 8))
            }.isFailure)
            assertEquals("preserve me", File(base, "obstruction").readText())
            assertTrue("Reset must not silently claim the directory was removed", runCatching { store.forget(book) }.isFailure)
            val pcm = File(context.cacheDir, "ai-audio/$key.pcm")
            check(pcm.mkdir()); File(pcm, "obstruction").writeText("preserve PCM obstruction")
            assertTrue("Generated audio must also verify its committed base", runCatching {
                store.saveClip(key, AiAudioClip(floatArrayOf(.1f, -.1f, .2f)))
            }.isFailure)
            assertEquals("preserve PCM obstruction", File(pcm, "obstruction").readText())
            // A normal forget removes all three AtomicFile variants, including leftover .new files.
            val normal = "f".repeat(64)
            val normalFile = File(context.filesDir, "reader-state/$normal-ai.json")
            for (suffix in listOf("", ".bak", ".new")) File(normalFile.path + suffix).writeText("fixture")
            store.forget(normal)
            for (suffix in listOf("", ".bak", ".new")) assertFalse(File(normalFile.path + suffix).exists())
        } finally { root.deleteRecursively() }
    }

}
