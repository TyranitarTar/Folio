package app.folio.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** SAF access stays scoped to the directory the user chooses; audio is copied for offline use. */
internal suspend fun audiobookFolder(context: Context, tree: Uri): List<Uri> = withContext(Dispatchers.IO) {
    val result = mutableListOf<Pair<String, Uri>>()
    val seen = mutableSetOf<String>()
    var scanned = 0
    suspend fun visit(id: String, prefix: String, depth: Int) {
        require(depth <= 8 && seen.add(id)) { "This audiobook folder is nested too deeply or contains a cycle." }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val pending = mutableListOf<Triple<String, String, String>>()
        context.contentResolver.query(children, columns, null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                require(++scanned <= 5000) { "This folder contains too many files. Choose the audiobook’s own folder." }
                pending += Triple(rows.getString(0), rows.getString(1).orEmpty(), rows.getString(2).orEmpty())
            }
        } ?: throw IllegalArgumentException("The audiobook folder could not be read.")
        for ((child, type, name) in pending) {
            currentCoroutineContext().ensureActive()
            val relative = "$prefix$name"
            if (type == DocumentsContract.Document.MIME_TYPE_DIR) visit(child, "$relative/", depth + 1)
            else if (type.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4b", "m4a", "ogg", "opus", "wav", "flac", "aac")) {
                result += relative to DocumentsContract.buildDocumentUriUsingTree(tree, child)
                require(result.size <= 256) { "Choose an audiobook with at most 256 tracks." }
            }
        }
    }
    visit(DocumentsContract.getTreeDocumentId(tree), "", 0)
    require(result.isNotEmpty()) { "This folder contains no supported audio files." }
    fun sortKey(value: String) = Regex("\\d+").replace(value.lowercase(java.util.Locale.ROOT)) { it.value.padStart(20, '0') }
    result.sortedBy { sortKey(it.first) }.map { it.second }
}
