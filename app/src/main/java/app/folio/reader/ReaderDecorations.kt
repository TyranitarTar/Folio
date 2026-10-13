@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.util.WeakHashMap

private val decorationCommits = WeakHashMap<EpubNavigatorFragment, Mutex>()

/** Readium caches the target before its cancellable diff. Complete cache and JS dispatch together. */
internal suspend fun EpubNavigatorFragment.commitDecorations(decorations: List<Decoration>, group: String) {
    val lock = synchronized(decorationCommits) { decorationCommits.getOrPut(this) { Mutex() } }
    withContext(NonCancellable + Dispatchers.Main.immediate) {
        lock.withLock { applyDecorations(decorations, group) }
    }
}
