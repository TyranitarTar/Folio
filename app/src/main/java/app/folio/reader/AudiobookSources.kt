package app.folio.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as D
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

/** Persisted SAF roots: index ordinary audio in place; FolioBook bundles are extracted. */
internal class AudiobookSources(private val context: Context, private val store: AudiobookStore) {
    private val preferences=context.getSharedPreferences("audiobook-sources",0)
    private val lock=Mutex()
    fun roots(): List<Uri> = preferences.all.keys.filter { it.startsWith("root:") }.map { Uri.parse(it.removePrefix("root:")) }
    fun forget(uri: Uri) { preferences.edit().remove("root:$uri").apply() }
    suspend fun add(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if(!preferences.contains("root:$uri")) check(preferences.edit().putString("root:$uri","{}").commit())
    }
    private fun children(tree: Uri,id: String): List<AudioSourceEntry> {
        val columns=arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_SIZE,D.Document.COLUMN_LAST_MODIFIED)
        return context.contentResolver.query(D.buildChildDocumentsUriUsingTree(tree,id),columns,null,null,null)?.use { rows ->
            buildList { while(rows.moveToNext()) {
                require(size<5000) { "This folder has too many entries." }
                add(AudioSourceEntry(rows.getString(0),rows.getString(1).orEmpty(),rows.getString(2).orEmpty(),rows.getLong(3),rows.getLong(4)))
            } }
        } ?: error("Folder access is unavailable. Relink the source folder.")
    }
    suspend fun refresh(tree: Uri, previousRoot: Uri?=null,progress: (String)->Unit): String = withContext(Dispatchers.IO) { lock.withLock {
        store.load()
        val saved=JSONObject(preferences.getString("root:$tree","{}") ?: "{}")
        var visited=0
        fun entries(id:String)=children(tree,id).also { visited+=it.size;require(visited<=50000) { "This library contains too many files; choose a smaller root folder." } }
        var unsupported=0
        val groups=discoverAudioBooks(D.getTreeDocumentId(tree),D.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':'),{unsupported++}) { id ->
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main){progress("Scanning ${id.substringAfterLast('/').take(80)}")}
            entries(id)
        }
        fun identity(uri:Uri)=uri.authority+"/"+D.getDocumentId(uri)
        val recordings=store.books.value.filter{it.sourceRoot!=null && it.tracks.all{track->track.sourceUri!=null}}
            .associateBy{book->book.tracks.map{identity(Uri.parse(it.sourceUri))}}
        require(groups.isNotEmpty()) { "No supported audiobooks were found. Choose a root containing book folders or .foliobook files." }
        var added=0;var unchanged=0;var linked=0;var deferred=0
        val failures=mutableListOf<String>()
        val presentIds=store.books.value.mapTo(mutableSetOf()){it.id}
        for((index,group) in groups.withIndex()) {
            currentCoroutineContext().ensureActive()
            val sourceKey=group.key;val name=group.title;val tracks=group.tracks
            withContext(Dispatchers.Main) { progress("${index+1}/${groups.size} · $name") }
            val fingerprint=MessageDigest.getInstance("SHA-256").digest((tracks+listOfNotNull(group.cover)).sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.name}|${it.bytes}|${it.modified}" }.toByteArray()).joinToString("") { "%02x".format(it) }
            val old=saved.optJSONObject(sourceKey)
            if(old?.optString("fingerprint")==fingerprint && (old.optString("mode")=="linked-v1" || tracks.all { it.bytes>0 && it.modified>0 }) && old.optString("book") in presentIds) { unchanged++;continue }
            fun natural(name: String)=Regex("\\d+").replace(name.lowercase(java.util.Locale.ROOT)) { it.value.padStart(20,'0') }
            val uris=tracks.sortedBy { natural(it.name) }.map { D.buildDocumentUriUsingTree(tree,it.id) }
            val bundle=tracks.size==1 && tracks[0].name.endsWith(".foliobook",true)
            val existing=store.books.value.firstOrNull{it.id==old?.optString("book") && it.sourceRoot==tree.toString()}
            val incomingUris=uris.mapTo(mutableSetOf()){it.toString()}
            if(!bundle && existing!=null && (existing.tracks.any{it.sourceUri !in incomingUris} || AudiobookController.state.value.id==existing.id)) {
                deferred++
                withContext(Dispatchers.Main) { progress("Keeping $name unchanged: stop its player to update, or relink missing/renamed tracks.") }
                continue
            }
            withContext(Dispatchers.Main) { progress(if(bundle)"Extracting FolioBook ${index+1}/${groups.size} · $name" else "Linking ${index+1}/${groups.size} · $name · no audio copy") }
            val duplicate=recordings[uris.map{identity(it)}]?.takeIf{it.sourceRoot!=tree.toString()}
            val book=try { if(bundle)store.importBundle(uris.single()) else if(duplicate!=null)duplicate else store.linkAudio(tree,sourceKey,fingerprint,name,
                tracks.sortedBy{natural(it.name)}.map{it.name to D.buildDocumentUriUsingTree(tree,it.id)},
                previousId=previousRoot?.let{oldRoot -> store.books.value.filter{it.sourceRoot==oldRoot.toString() && it.title==name}.singleOrNull()?.id},knownId=existing?.id,sourceCover=group.cover?.let{D.buildDocumentUriUsingTree(tree,it.id)})
            } catch(cancelled:CancellationException){throw cancelled}
              catch(error:Exception){failures+="$name: ${error.message?.take(120) ?: "Unable to add"}";continue}
            saved.put(sourceKey,JSONObject().put("fingerprint",fingerprint).put("book",book.id).put("mode",if(bundle)"copied" else "linked-v1"))
            presentIds+=book.id
            check(preferences.edit().putString("root:$tree",saved.toString()).commit()) { "Source progress could not be saved." }
            if(bundle)added++ else linked++
        }
        "$added imported · $unchanged unchanged"+(if(linked>0)" · $linked linked" else "")+
            (if(deferred>0)" · $deferred title(s) kept unchanged; stop playback or relink changed tracks" else "")+
            (if(unsupported>0)" · $unsupported unsupported audio file(s) skipped" else "")+
            (if(failures.isNotEmpty())" · ${failures.size} failed: ${failures.take(3).joinToString("; ")}" else "")+". Existing audio and listening positions were retained."
    } }
}
