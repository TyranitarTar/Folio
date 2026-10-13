package app.folio.reader

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/** One ordered lane across Service instances. Control barriers freeze prior coalesced writes. */
class OrderedSpeechWriter(private val executor: ExecutorService = Executors.newSingleThreadExecutor {
    task -> Thread(task, "Folio listening state").apply { isDaemon = true }
}) {
    data class Session(val epoch: Long, val bookId: String, val namespace: String = bookId)
    private class Pending(val session: Session, var write: () -> Unit, val error: (Throwable) -> Unit)
    private val lock = Any()
    private val queue = ArrayDeque<() -> Unit>()
    private val pending = mutableMapOf<Long, Pending>()
    private val deleted = mutableSetOf<String>()
    private var nextEpoch = 0L
    private var accepted: Session? = null
    private var executed: Session? = null
    @Volatile private var failed: Long? = null
    private var draining = false

    suspend fun <T> begin(bookId: String, namespace: String = bookId, read: (Session) -> T): T = suspendCancellableCoroutine { continuation ->
        synchronized(lock) {
            val session = Session(++nextEpoch, bookId, namespace)
            accepted = session
            enqueueControl {
                executed = session; failed = null
                if (continuation.isActive) try {
                    val result = read(session)
                    synchronized(lock) { deleted.remove(namespace) }
                    continuation.resumeWith(Result.success(result))
                } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        }
    }
    fun submit(session: Session, error: (Throwable) -> Unit, write: () -> Unit) = synchronized(lock) {
        if (accepted != session || session.namespace in deleted || failed == session.epoch) return@synchronized
        pending[session.epoch]?.let { it.write = write; return@synchronized }
        val point = Pending(session, write, error)
        pending[session.epoch] = point
        enqueue {
            val action = synchronized(lock) { if (pending[session.epoch] === point) pending.remove(session.epoch); point.write }
            perform(session, point.error, action)
        }
    }
    fun mandatory(session: Session, error: (Throwable) -> Unit, write: () -> Unit) = synchronized(lock) {
        if (accepted == session && session.namespace !in deleted && failed != session.epoch)
            enqueueControl { perform(session, error, write) }
    }
    fun end(session: Session) = synchronized(lock) {
        if (accepted == session) accepted = null
        enqueueControl { if (executed == session) { executed = null; failed = null } }
    }
    suspend fun <T> exclusive(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        synchronized(lock) { enqueueControl {
            if (continuation.isActive) try { continuation.resumeWith(Result.success(action())) }
            catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
        } }
    }
    suspend fun delete(namespace: String, action: () -> Unit) {
        synchronized(lock) {
            deleted.add(namespace)
            if (accepted?.namespace == namespace) accepted = null
        }
        exclusive {
            if (executed?.namespace == namespace) { executed = null; failed = null }
            action()
        }
    }
    fun current(session: Session): Boolean = synchronized(lock) { accepted == session && session.namespace !in deleted }
    private fun perform(session: Session, error: (Throwable) -> Unit, action: () -> Unit) {
        if (executed != session || failed == session.epoch) return
        try { action() } catch (failure: Throwable) {
            failed = session.epoch
            if (current(session)) error(failure)
        }
    }
    private fun enqueueControl(action: () -> Unit) { pending.clear(); enqueue(action) }
    private fun enqueue(action: () -> Unit) {
        queue.addLast(action)
        if (!draining) { draining = true; executor.execute(::drain) }
    }
    private fun drain() {
        while (true) {
            val action = synchronized(lock) {
                if (queue.isEmpty()) { draining = false; return }
                queue.removeFirst()
            }
            action()
        }
    }
    internal fun shutdownForTest() { executor.shutdown() }
}
