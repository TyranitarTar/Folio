package app.folio.reader

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Hold from before model preparation until native release has actually completed. */
internal object VoiceModelUse {
    private val lock=Any()
    private val users=mutableMapOf<String,MutableMap<String,Int>>()
    private val reservations=mutableMapOf<String,MutableMap<Any,Set<String>>>()
    private fun root(context:Context)=context.noBackupFilesDir.canonicalPath
    internal fun validVersion(version:String)=version.matches(Regex("[A-Za-z0-9._-]{1,160}"))&&version !in setOf(".","..")
    fun acquire(context:Context,version:String,components:Set<String> = setOf(version)):Closeable {
        val keys=components+version;require(keys.all(::validVersion));val root=root(context)
        synchronized(lock) {
            check(reservations[root].orEmpty().values.none { paths->paths.any { it in keys } }) { "Voice storage maintenance is in progress. Try this voice again shortly." }
            val active=users.getOrPut(root){mutableMapOf()};keys.forEach { active[it]=(active[it] ?: 0)+1 }
        }
        val closed=AtomicBoolean()
        return Closeable { if(closed.compareAndSet(false,true))synchronized(lock) {
            users[root]?.let { active->keys.forEach { key->val remaining=(active[key] ?: 1)-1;if(remaining<=0)active.remove(key)else active[key]=remaining };if(active.isEmpty())users.remove(root) }
        } }
    }
    fun active(context:Context):Set<String> = synchronized(lock) { users[root(context)].orEmpty().keys.toSet() }
    /** Atomically closes the acquisition race while selected model paths are removed. */
    fun reserve(context:Context,versions:Set<String>):Closeable {
        require(versions.all(::validVersion));val root=root(context);val token=Any()
        synchronized(lock) {
            check(users[root].orEmpty().keys.none { it in versions }) { "A selected voice is still loaded. Stop narration, previews and benchmarks first." }
            check(reservations[root].orEmpty().values.none { keys->keys.any { it in versions } }) { "Voice storage maintenance is already running." }
            reservations.getOrPut(root){mutableMapOf()}[token]=versions
        }
        return Closeable { synchronized(lock) { reservations[root]?.let { it.remove(token);if(it.isEmpty())reservations.remove(root) } } }
    }
}
