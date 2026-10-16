package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SpeechModelFile(val name: String, val bytes: Long, val sha256: String)

/** Rehash installed files once per engine initialization; repaired copies must match signed APK identities. */
internal object VerifiedSpeechAssets {
    private val mutex = Mutex()
    suspend fun prepare(context: Context, assets: String, version: String, specifications: List<SpeechModelFile>,
        stopped: () -> Boolean, validate: (File) -> Unit = {}): File = withContext(Dispatchers.IO) {
        VoicePackStore.operations.withLock { mutex.withLock {
            check(specifications.isNotEmpty())
            val directory = File(context.noBackupFilesDir, "local-voices/$version")
            check(directory.isDirectory || directory.mkdirs()) { "Not enough storage to prepare the local voice." }
            val ready = AtomicFile(File(directory, ".ready"))
            ready.delete()
            val buffer = ByteArray(64 * 1024)
            for (spec in specifications) {
                currentCoroutineContext().ensureActive()
                check(!stopped()) { "The local voice engine has been closed." }
                require(spec.name.isNotEmpty() && !spec.name.startsWith("/") && spec.name.split('/').none { it == ".." })
                val target = AtomicFile(File(directory, spec.name))
                suspend fun valid(): Boolean {
                    return try {
                        target.openRead().use { input ->
                            val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(!stopped()) { "The local voice engine has been closed." }
                                val bytes = input.read(buffer)
                                if (bytes < 0) break
                                count += bytes
                                if (count > spec.bytes) return@use false
                                digest.update(buffer, 0, bytes)
                            }
                            count == spec.bytes && SpeechPcm.hex(digest.digest()) == spec.sha256
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: java.io.IOException) { false }
                }
                if (!valid()) {
                    target.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Not enough storage to prepare the local voice." } }
                    val output = target.startWrite()
                    try {
                        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                        val bundled=try { context.assets.open("$assets/" + spec.name) } catch(e: java.io.FileNotFoundException) {
                            throw IllegalStateException("Download the ${when(assets){"pico" -> "Plapre Pico"; "supertonic" -> "Supertonic"; else -> "Kokoro"}} voice pack in More → Voice packs.",e)
                        }
                        bundled.use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(!stopped()) { "The local voice engine has been closed." }
                                val bytes = input.read(buffer)
                                if (bytes < 0) break
                                output.write(buffer, 0, bytes); digest.update(buffer, 0, bytes); count += bytes
                            }
                        }
                        check(count == spec.bytes && SpeechPcm.hex(digest.digest()) == spec.sha256) {
                            "The bundled voice failed its integrity check. Reinstall this APK."
                        }
                        target.finishWrite(output)
                    } catch (error: Throwable) { target.failWrite(output); throw error }
                    check(valid()) { "The local voice copy failed its integrity check. Check available storage." }
                }
            }
            validate(directory)
            val output = ready.startWrite()
            try { output.write(version.toByteArray()); ready.finishWrite(output) }
            catch (error: Throwable) { ready.failWrite(output); throw error }
            (context.applicationContext as? FolioApp)?.voicePacks?.catalog?.firstOrNull { it.version==version }?.let { pack ->
                (context.applicationContext as FolioApp).voicePacks.update(VoicePackState(pack.id,"Ready",pack.bytes,"Available offline"))
            }
            directory
        } }
    }
}
