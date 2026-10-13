package app.folio.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafePublicationContainerTest {
    private val href = Url("chapter.xhtml")!!
    private fun container(resource: Resource, onClose: () -> Unit = {}): Container<Resource> = object : Container<Resource> {
        override val entries = setOf(href)
        override fun get(url: Url): Resource? = resource.takeIf { url == href }
        override fun close() = onClose()
    }
    private abstract class TestResource : Resource {
        override val sourceUrl = null
        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())
        override suspend fun length(): Try<Long, ReadError> = Try.success(3L)
        override fun close() {}
    }
    @OptIn(org.readium.r2.shared.InternalReadiumApi::class)
    @Test fun realBookEngineOpensWithGuardedRootContainer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FolioApp>()
        val fixture = java.io.File.createTempFile("guarded-publication-", ".epub", context.cacheDir)
        context.assets.open("sample.epub").use { source -> fixture.outputStream().use { source.copyTo(it) } }
        val publication = BookEngine(context).open(fixture)
        lateinit var held: Resource
        try {
            assertTrue("PublicationOpener must apply the resource guard to the actual published container",
                publication.container is SafePublicationContainer)
            held = requireNotNull(publication.get(publication.readingOrder.first()))
        } finally { publication.close(); fixture.delete() }
        var failed = false
        held.read().getOrElse { failed = true; byteArrayOf() }
        assertTrue("A real EPUB resource held by a native request must report a typed error after closing", failed)
    }
    @Test fun resourceObtainedBeforeShutdownRejectsLaterNativeRead() = runBlocking {
        var reads = 0
        var closes = 0
        val raw = object : TestResource() {
            override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> { reads++; return Try.success(byteArrayOf(1, 2, 3)) }
        }
        val safe = SafePublicationContainer(container(raw) { closes++ })
        val resource = safe[href]!!
        assertEquals(3, resource.read().getOrElse { throw AssertionError(it.message) }.size)
        safe.close(); safe.close()
        assertNull(safe[href])
        var failed = false
        resource.read().getOrElse { failed = true; byteArrayOf() }
        assertTrue(failed)
        assertEquals(1, reads)
        assertEquals(1, closes)
    }
    @Test fun nativeReadAlreadyInFlightGetsTypedErrorWhenZipCloses() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closeOccurred = CompletableDeferred<Unit>()
        val raw = object : TestResource() {
            override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
                started.complete(Unit)
                closeOccurred.await()
                throw IllegalStateException("zip file closed")
            }
        }
        val safe = SafePublicationContainer(container(raw) { closeOccurred.complete(Unit) })
        val resource = safe[href]!!
        val pending = async { resource.read() }
        started.await()
        safe.close()
        var failed = false
        pending.await().getOrElse { failed = true; byteArrayOf() }
        assertTrue(failed)
    }
    @Test fun unrelatedReadBugIsNotHiddenByTheShutdownGuard() = runBlocking {
        val raw = object : TestResource() {
            override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = throw IllegalStateException("unrelated bug")
        }
        val safe = SafePublicationContainer(container(raw))
        val error = runCatching { safe[href]!!.read() }.exceptionOrNull()
        assertEquals("unrelated bug", error?.message)
        safe.close()
    }
}
