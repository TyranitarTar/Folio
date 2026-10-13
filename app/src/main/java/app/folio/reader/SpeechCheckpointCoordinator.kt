package app.folio.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

data class SpeechRestore(val session: OrderedSpeechWriter.Session, val cursor: ReadingCursor?,
    val ai: AiListeningPoint?, val recoveryNote: String?)

/** Backup/deletion and every speech generation use the same lane; no LibraryStore calls occur here. */
object SpeechCheckpointCoordinator {
    private val writer = OrderedSpeechWriter()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    suspend fun begin(context: Context, bookId: String, bookFile: File, restore: Boolean, includeAi: Boolean): SpeechRestore =
        writer.begin(bookId, context.filesDir.canonicalPath + "|" + bookId) { session ->
            check(bookFile.isFile) { "This book is no longer in your library." }
            val notes = mutableListOf<String>()
            val cursors = ReaderCursorStore(context)
            val speech = if (restore) try { cursors.read(bookId, "speech") } catch (_: Exception) {
                notes.add("The saved listening bookmark was damaged. A recovery copy was kept.")
                cursors.recover(bookId, "speech")
            } else null
            val aiStore = AiListeningStore(context)
            val ai = if (restore && includeAi) try { aiStore.read(bookId) } catch (_: Exception) {
                notes.add("The saved audio checkpoint was damaged. Resuming at the text bookmark; a recovery copy was kept.")
                aiStore.recover(bookId)
            } else null
            if (!restore) aiStore.forget(bookId)
            SpeechRestore(session, speech, ai, notes.takeIf { it.isNotEmpty() }?.joinToString(" "))
        }
    fun save(context: Context, session: OrderedSpeechWriter.Session, point: ReadingCursor,
        ai: AiListeningPoint? = null, mandatory: Boolean = false, onError: (Throwable) -> Unit) {
        val operation = {
            ReaderCursorStore(context).write(session.bookId, "speech", point)
            if (ai != null) AiListeningStore(context).write(session.bookId, ai)
        }
        val report: (Throwable) -> Unit = { error -> main.post { if (writer.current(session)) onError(error) } }
        if (mandatory) writer.mandatory(session, report, operation) else writer.submit(session, report, operation)
    }
    fun forgetAudio(context: Context, session: OrderedSpeechWriter.Session, onError: (Throwable) -> Unit) =
        writer.mandatory(session, { error -> main.post { if (writer.current(session)) onError(error) } }) {
            AiListeningStore(context).forget(session.bookId)
        }
    fun current(session: OrderedSpeechWriter.Session) = writer.current(session)
    fun end(session: OrderedSpeechWriter.Session) = writer.end(session)
    suspend fun invalidateBook(context: Context, bookId: String) = writer.delete(context.filesDir.canonicalPath + "|" + bookId) {}
    suspend fun deleteBook(context: Context, bookId: String, deleteAction: () -> Unit) = writer.delete(context.filesDir.canonicalPath + "|" + bookId) {
        ReaderCursorStore(context).forget(bookId, "speech")
        AiListeningStore(context).forget(bookId)
        deleteAction()
    }
    suspend fun <T> exclusive(context: Context, action: () -> T): T = writer.exclusive(action)
}
