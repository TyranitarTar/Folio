package app.folio.reader

import java.util.concurrent.atomic.AtomicBoolean
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource

/**
 * A native WebView resource request may finish after its navigator has been detached.
 * Readium's ZIP reader reports IO errors normally, but ZipFile.getInputStream can also
 * throw IllegalStateException during close. Return a regular ReadError in that case.
 * The navigator must still be detached before closing its publication.
 */
class SafePublicationContainer(private val delegate: Container<Resource>) : Container<Resource> by delegate {
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    override fun get(url: Url): Resource? = synchronized(lock) {
        if (closed.get()) null else delegate[url]?.let { ClosingResource(it, url) }
    }

    override fun close() = synchronized(lock) {
        if (closed.compareAndSet(false, true)) {
            delegate.close()
        }
    }

    private inner class ClosingResource(private val resource: Resource, url: Url) : Resource by resource {
        private val budget = if (url.toString().substringBefore('?').substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm", "xml", "svg", "opf", "ncx")) 8L * 1024 * 1024 else 32L * 1024 * 1024
        private val resourceClosed = AtomicBoolean(false)
        private fun isClosed() = closed.get() || resourceClosed.get()
        private fun <T> closedError(): Try<T, ReadError> =
            Try.failure(ReadError.UnsupportedOperation("The publication resource has been closed."))

        private suspend fun <T> safely(operation: suspend () -> Try<T, ReadError>): Try<T, ReadError> {
            if (isClosed()) return closedError()
            return try { operation() }
            catch (error: IllegalStateException) {
                // Only normalize an expected shutdown race; preserve unrelated programmer errors.
                if (isClosed()) closedError() else throw error
            }
        }
        override suspend fun properties(): Try<Resource.Properties, ReadError> = safely { resource.properties() }
        override suspend fun length(): Try<Long, ReadError> = safely { resource.length() }
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = safely {
            resource.length().flatMap { length ->
                if (length > budget) Try.failure(ReadError.UnsupportedOperation("This resource is too large to read safely."))
                else resource.read(range).flatMap { bytes ->
                    if (bytes.size > budget) Try.failure(ReadError.UnsupportedOperation("This resource exceeds the safe reading limit.")) else Try.success(bytes)
                }
            }
        }
        override fun close() { if (resourceClosed.compareAndSet(false, true)) resource.close() }
    }
}
