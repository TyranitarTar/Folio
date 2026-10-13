package app.folio.reader

import android.content.Context
import java.util.concurrent.Executors
import java.io.File
import kotlinx.coroutines.CompletableDeferred

/** A single coalesced lane survives activity recreation and preserves pause-before-restore order. */
internal object GuidedProgress {
    private val lane = Executors.newSingleThreadExecutor { Thread(it, "Folio guided progress").apply { isDaemon = true } }
    private val pending = linkedMapOf<String, () -> Unit>()
    private val epochs = mutableMapOf<String, Long>()
    private var scheduled = false
    private fun key(context: Context, id: String) = context.filesDir.canonicalPath + "|" + id
    suspend fun begin(context: Context, id: String): Pair<Long, ReadingCursor?> {
        val key = key(context, id)
        val result = CompletableDeferred<Pair<Long, ReadingCursor?>>()
        lane.execute {
            drain()
            try {
                require(File(context.filesDir, "library/$id.epub").exists()) { "This book is no longer in the library." }
                val epoch = synchronized(pending) { (epochs[key] ?: 0L).plus(1).also { epochs[key] = it } }
                result.complete(epoch to ReaderCursorStore(context).recover(id, "guided"))
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result.await()
    }
    fun save(context: Context, id: String, epoch: Long, cursor: ReadingCursor, failed: (Throwable) -> Unit) {
        val key = key(context, id)
        synchronized(pending) {
            if (epochs[key] != epoch) return
            pending[key] = {
                if (synchronized(pending) { epochs[key] == epoch } && File(context.filesDir, "library/$id.epub").exists()) runCatching {
                    ReaderCursorStore(context).write(id, "guided", cursor)
                }.onFailure(failed)
            }
            if (!scheduled) { scheduled = true; lane.execute { drain() } }
        }
    }
    private fun drain() {
        while (true) {
            val work = synchronized(pending) {
                val key = pending.keys.firstOrNull()
                if (key == null) { scheduled = false; null } else pending.remove(key)
            } ?: return
            work()
        }
    }
    suspend fun <T> exclusive(action: () -> T): T {
        val result = CompletableDeferred<T>()
        lane.execute { drain(); try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        return result.await()
    }
    suspend fun delete(context: Context, id: String, action: () -> Unit) {
        val key = key(context, id)
        val result = CompletableDeferred<Unit>()
        synchronized(pending) { epochs[key] = (epochs[key] ?: 0L) + 1; pending.remove(key) }
        lane.execute { try { action(); result.complete(Unit) } catch (error: Throwable) { result.completeExceptionally(error) } }
        result.await()
    }
}
