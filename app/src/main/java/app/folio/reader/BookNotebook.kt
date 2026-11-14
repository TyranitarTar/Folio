package app.folio.reader

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class NotebookDocument(val text:String="",val revision:Long=0,val migratedIds:List<String> = emptyList())
internal data class NotebookSave(val document:NotebookDocument,val recovered:Boolean=false)

/** User text is independent of the audio timeline and the large audiobook catalog. */
internal class BookNotebookStore(private val root:File,private val journal:AudioJournalStore) {
    private val lock=Any()
    private val orders=mutableMapOf<String,Long>()
    private fun folder(id:String)=File(root,id).also{require(id.matches(Regex("[a-f0-9]{64}")))}
    private fun readFile(file:File):NotebookDocument? {
        if(!file.exists() && !File(file.path+".bak").exists())return null
        val json=BoundedJson.objectFrom(readBytes(file),MAX_BYTES,8,5000)
        require(json.getInt("version")==1)
        val text=json.getString("text");require(text.length<=MAX_CHARS)
        val ids=json.optJSONArray("migratedIds") ?: JSONArray();require(ids.length()<=1000)
        return NotebookDocument(text,json.getLong("revision").also{require(it>=0)},(0 until ids.length()).map{ids.getString(it).also{v->require(v.length in 1..100)}})
    }
    private fun read(id:String):NotebookDocument {
        val base=readFile(File(folder(id),"notebook.json"))
        val draft=readFile(File(folder(id),"notebook-draft.json"))
        return listOfNotNull(base,draft).maxByOrNull{it.revision} ?: NotebookDocument()
    }
    suspend fun open(book:Audiobook):NotebookDocument=withContext(Dispatchers.IO){synchronized(lock) {
        journal.retireNotes(book.id){notes->
            val old=read(book.id);val missing=notes.filter{it.id !in old.migratedIds}.sortedBy{it.createdAt}
            if(missing.isNotEmpty()) {
                val chapters=book.listeningChapters()
                val added=missing.joinToString("\n"){note->
                    val chapter=chapters[chapters.currentChapter(note.track,note.positionMs)]
                    "- [${chapter.title} · ${audioTime((note.positionMs-chapter.startMs).coerceAtLeast(0))}] ${note.text.replace("\n","\n  ")}"
                }
                val updated=NotebookDocument(listOf(old.text,added).filter{it.isNotEmpty()}.joinToString("\n"),old.revision+1,(old.migratedIds+missing.map{it.id}).distinct())
                write(File(folder(book.id),"notebook.json"),updated)
            }
        }
        read(book.id).also{orders[book.id]=maxOf(orders[book.id] ?: 0,it.revision)}
    }}
    fun nextOrder(id:String):Long=synchronized(lock){val next=(orders[id] ?: error("Open the notebook before editing."))+1;orders[id]=next;next}
    suspend fun save(id:String,text:String,order:Long):NotebookSave=withContext(Dispatchers.IO){synchronized(lock) {
        require(text.length<=MAX_CHARS){"This notebook is full. Export it before adding more text."}
        check(folder(id).isDirectory){"This audiobook is no longer available."}
        val old=read(id)
        if(order<old.revision)return@synchronized NotebookSave(old)
        val updated=old.copy(text=text,revision=order)
        try {
            write(File(folder(id),"notebook.json"),updated)
        } catch(error:Exception) {
            try {write(File(folder(id),"notebook-draft.json"),updated);return@synchronized NotebookSave(updated,true)}
            catch(_:Exception){throw error}
        }
        val draft=File(folder(id),"notebook-draft.json")
        if(readFile(draft)?.revision?.let{it<=order}==true)DurableJson.delete(AtomicFile(draft))
        NotebookSave(updated)
    }}
    /** Hold through the directory transaction so a queued editor save cannot race its swap. */
    fun <T> withSnapshot(id:String,stage:File,commit:()->T):T=synchronized(lock) {
        val current=read(id)
        if(current.revision>0 || current.text.isNotEmpty())write(File(stage,"notebook.json"),current)
        val archive=File(folder(id),"notes-legacy.json")
        if(archive.exists() || File(archive.path+".bak").exists())DurableJson.write(AtomicFile(File(stage,"notes-legacy.json")),readBytes(archive),MAX_BYTES)
        commit()
    }
    fun copyToSplit(id:String,child:String)=synchronized(lock) {
        val current=read(id)
        if(current.revision>0 || current.text.isNotEmpty()) {
            val prior=read(child)
            val text=listOf(prior.text,current.text).filter{it.isNotEmpty()}.distinct().joinToString("\n\n")
            val merged=NotebookDocument(text,maxOf(prior.revision,current.revision)+1,(prior.migratedIds+current.migratedIds).distinct())
            write(File(folder(child),"notebook.json"),merged)
        }
        val archive=File(folder(id),"notes-legacy.json")
        if(archive.exists())DurableJson.write(AtomicFile(File(folder(child),"notes-legacy.json")),readBytes(archive),MAX_BYTES)
    }
    companion object {
        const val MAX_CHARS=250_000
        const val MAX_BYTES=2*1024*1024
        private fun write(target:File,document:NotebookDocument) {
            require(document.text.length<=MAX_CHARS)
            val bytes=JSONObject().put("version",1).put("text",document.text).put("revision",document.revision)
                .put("migratedIds",JSONArray(document.migratedIds)).toString().toByteArray(Charsets.UTF_8)
            DurableJson.write(AtomicFile(target),bytes,MAX_BYTES)
        }
        private fun readBytes(file:File):ByteArray=AtomicFile(file).openRead().use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(out.size()<=MAX_BYTES){val n=input.read(buffer,0,minOf(buffer.size,MAX_BYTES+1-out.size()));if(n<0)break;out.write(buffer,0,n)}
            out.toByteArray().also{require(it.size<=MAX_BYTES)}
        }
    }
}
