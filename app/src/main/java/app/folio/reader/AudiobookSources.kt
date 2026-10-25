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
    private data class Entry(val id: String,val name: String,val type: String,val bytes: Long,val modified: Long)
    private fun children(tree: Uri,id: String): List<Entry> {
        val columns=arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE,D.Document.COLUMN_SIZE,D.Document.COLUMN_LAST_MODIFIED)
        return context.contentResolver.query(D.buildChildDocumentsUriUsingTree(tree,id),columns,null,null,null)?.use { rows ->
            buildList { while(rows.moveToNext()) {
                require(size<5000) { "This folder has too many entries." }
                add(Entry(rows.getString(0),rows.getString(1).orEmpty(),rows.getString(2).orEmpty(),rows.getLong(3),rows.getLong(4)))
            } }
        } ?: error("Folder access is unavailable. Relink the source folder.")
    }
    suspend fun refresh(tree: Uri, previousRoot: Uri?=null,progress: (String)->Unit): String = withContext(Dispatchers.IO) { lock.withLock {
        store.load()
        val saved=JSONObject(preferences.getString("root:$tree","{}") ?: "{}")
        var visited=0
        fun entries(id:String)=children(tree,id).also { visited+=it.size;require(visited<=50000) { "This library contains too many files; choose a smaller root folder." } }
        val top=entries(D.getTreeDocumentId(tree))
        val directories=top.filter { it.type==D.Document.MIME_TYPE_DIR }
        val audioExtensions=setOf("mp3","m4b","m4a","ogg","opus","wav","flac","aac")
        fun audio(e: Entry)=e.type.startsWith("audio/") || e.name.substringAfterLast('.').lowercase() in audioExtensions
        val groups=mutableListOf<Triple<String,String,List<Entry>>>()
        suspend fun collect(id: String,depth: Int,seen: MutableSet<String>,prefix: String=""): List<Entry> {
            require(depth<=8 && seen.add(id)) { "Folder nesting is too deep or contains a cycle." }
            val result=mutableListOf<Entry>()
            for(e in entries(id)) {
                currentCoroutineContext().ensureActive()
                if(e.type==D.Document.MIME_TYPE_DIR) result+=collect(e.id,depth+1,seen,"$prefix${e.name}/") else if(audio(e) || e.name.endsWith(".foliobook",true)) result+=e.copy(name="$prefix${e.name}")
                require(result.size<=256) { "A book has more than 256 tracks; choose a smaller folder." }
            }
            return result
        }
        fun group(key:String,name:String,entries:List<Entry>) {
            val bundles=entries.filter { it.name.endsWith(".foliobook",true) }
            if(bundles.isNotEmpty())bundles.forEach { groups+=Triple(it.id,it.name,listOf(it)) }
            else if(entries.isNotEmpty())groups+=Triple(key,name,entries)
        }
        if(directories.isNotEmpty() && directories.all { it.name.matches(Regex("(?i)(cd|disc|disk)[ _-]*\\d+.*")) }) {
            group("@root",D.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':'),collect(D.getTreeDocumentId(tree),0,mutableSetOf()))
        } else {
            require(directories.size<=200) { "Choose a library root with at most 200 book folders." }
            for(directory in directories) {
                withContext(Dispatchers.Main) { progress("Scanning ${directory.name}") }
                val tracks=collect(directory.id,0,mutableSetOf())
                group(directory.id,directory.name,tracks)
            }
        }
        val groupedIds=groups.flatMap{it.third}.mapTo(mutableSetOf()){it.id}
        for(entry in top.filter { it.name.endsWith(".foliobook",true) || audio(it) })
            if(groupedIds.add(entry.id))group(entry.id,entry.name.substringBeforeLast('.'),listOf(entry))
        require(groups.isNotEmpty()) { "No supported audiobooks were found. Choose a root containing book folders or .foliobook files." }
        var added=0;var unchanged=0;var linked=0;var deferred=0
        val presentIds=store.books.value.mapTo(mutableSetOf()){it.id}
        for((index,group) in groups.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (sourceKey,name,tracks)=group
            withContext(Dispatchers.Main) { progress("${index+1}/${groups.size} · $name") }
            val fingerprint=MessageDigest.getInstance("SHA-256").digest(tracks.sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.name}|${it.bytes}|${it.modified}" }.toByteArray()).joinToString("") { "%02x".format(it) }
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
            val book=if(bundle)store.importBundle(uris.single()) else store.linkAudio(tree,sourceKey,fingerprint,name,
                tracks.sortedBy{natural(it.name)}.map{it.name to D.buildDocumentUriUsingTree(tree,it.id)},
                previousId=previousRoot?.let{oldRoot -> store.books.value.filter{it.sourceRoot==oldRoot.toString() && it.title==name}.singleOrNull()?.id},knownId=existing?.id)
            saved.put(sourceKey,JSONObject().put("fingerprint",fingerprint).put("book",book.id).put("mode",if(bundle)"copied" else "linked-v1"))
            presentIds+=book.id
            check(preferences.edit().putString("root:$tree",saved.toString()).commit()) { "Source progress could not be saved." }
            if(bundle)added++ else linked++
        }
        "$added imported · $unchanged unchanged"+(if(linked>0)" · $linked linked" else "")+
            (if(deferred>0)" · $deferred title(s) kept unchanged; stop playback or relink changed tracks" else "")+". Existing audio and listening positions were retained."
    } }
}
