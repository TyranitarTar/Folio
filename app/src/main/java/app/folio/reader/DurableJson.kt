package app.folio.reader

import android.util.AtomicFile

/** AtomicFile logs certain rename failures without throwing; a checkpoint must prove its commit. */
internal object DurableJson {
    fun write(atomic: AtomicFile, bytes: ByteArray) {
        require(bytes.size <= 65536) { "This saved position exceeds its safe size limit." }
        val out = atomic.startWrite()
        try {
            out.write(bytes); atomic.finishWrite(out)
            check(atomic.baseFile.isFile && atomic.baseFile.length() == bytes.size.toLong() &&
                atomic.baseFile.inputStream().use { it.readBytes() }.contentEquals(bytes)) {
                "The saved position could not be committed. Check storage permissions and free space."
            }
        } catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    fun delete(atomic: AtomicFile) {
        atomic.delete()
        check(listOf(atomic.baseFile, java.io.File(atomic.baseFile.path + ".bak"), java.io.File(atomic.baseFile.path + ".new")).none { it.exists() }) {
            "The saved position could not be removed. Check storage permissions."
        }
    }
}
