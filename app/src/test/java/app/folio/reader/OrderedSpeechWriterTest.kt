package app.folio.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrderedSpeechWriterTest {
    @Test fun oldGenerationDrainsBeforeRestoreAndRangeWritesAreCoalesced() = runBlocking {
        val writer = OrderedSpeechWriter()
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val writes = AtomicInteger(); var stored = 0
        try {
            val old = writer.begin("book") { it }
            writer.mandatory(old, { throw AssertionError(it) }) {
                entered.countDown(); check(unblock.await(3, TimeUnit.SECONDS)); stored = 1
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            for (value in 2..10_000) writer.submit(old, { throw AssertionError(it) }) { stored = value; writes.incrementAndGet() }
            val fresh = async(start = CoroutineStart.UNDISPATCHED) { writer.begin("book") { it to stored } }
            writer.submit(old, { throw AssertionError(it) }) { stored = -1 }
            unblock.countDown()
            val (session, restored) = fresh.await()
            assertEquals(10_000, restored)
            assertEquals("Ten thousand range updates occupy one queued write", 1, writes.get())
            writer.mandatory(session, { throw AssertionError(it) }) { stored = 20_000 }
            assertEquals(20_000, writer.exclusive { stored })
        } finally { unblock.countDown(); writer.shutdownForTest() }
    }
    @Test fun deleteIsABarrierAndTombstonesAreIsolatedByStorageRoot() = runBlocking {
        val writer = OrderedSpeechWriter(); var stored = 0
        try {
            val first = writer.begin("book", "root-a|book") { it }
            writer.submit(first, { throw AssertionError(it) }) { stored = 1 }
            writer.delete("root-a|book") { stored = 0 }
            writer.submit(first, { throw AssertionError(it) }) { stored = 99 }
            assertEquals(0, writer.exclusive { stored })
            val other = writer.begin("book", "root-b|book") { it }
            writer.delete("root-a|book") { }
            assertTrue(writer.current(other))
            writer.submit(other, { throw AssertionError(it) }) { stored = 2 }
            assertEquals(2, writer.exclusive { stored })
        } finally { writer.shutdownForTest() }
    }
    @Test fun failureStopsLaterWritesUntilAnExplicitNewSession() = runBlocking {
        val writer = OrderedSpeechWriter(); val failures = AtomicInteger(); var writes = 0
        try {
            val old = writer.begin("book") { it }
            writer.mandatory(old, { failures.incrementAndGet() }) { error("disk full") }
            writer.mandatory(old, { failures.incrementAndGet() }) { writes++ }
            writer.exclusive { Unit }
            assertEquals(1, failures.get()); assertEquals(0, writes)
            val fresh = writer.begin("book") { it }
            writer.mandatory(fresh, { throw AssertionError(it) }) { writes++ }
            assertEquals(1, writer.exclusive { writes })
        } finally { writer.shutdownForTest() }
    }
}
