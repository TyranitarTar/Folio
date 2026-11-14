package app.folio.reader

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class AudioNote(val id:String,val track:Int,val positionMs:Long,val text:String,val createdAt:Long,val updatedAt:Long,val passage:String="")
internal data class AudioSpeedEvent(val at:Long,val speed:Float)
internal data class AudioListeningSession(val id:String,val startedAt:Long,val endedAt:Long,val startTrack:Int,val startMs:Long,
    val endTrack:Int,val endMs:Long,val listenedMs:Long,val speeds:List<AudioSpeedEvent>,val closed:Boolean=false,val originalPositions:Boolean=false)
internal data class AudioJournal(val notes:List<AudioNote> = emptyList(),val sessions:List<AudioListeningSession> = emptyList(),val clearedSessionIds:List<String> = emptyList())

/** Separate small per-book journal: never rewrites the full cue catalog on a playback tick. */
internal class AudioJournalStore(private val root:File) {
    private val lock=Any()
    private val mutable=MutableStateFlow<Map<String,AudioJournal>>(emptyMap())
    val state=mutable.asStateFlow()
    private fun file(id:String):File { require(id.matches(Regex("[a-f0-9]{64}")));return File(root,"$id/journal.json") }
    private fun java.io.InputStream.takeBounded(limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit) {val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0)break;out.write(buffer,0,n)}
        return out.toByteArray()
    }
    private fun read(id:String):AudioJournal {
        val target=file(id)
        if(!target.exists() && !File(target.path+".bak").exists())return AudioJournal()
        val bytes=AtomicFile(target).openRead().use{input->input.takeBounded(MAX_BYTES+1).also{require(it.size<=MAX_BYTES)}}
        return decode(BoundedJson.objectFrom(bytes,MAX_BYTES,12,100_000))
    }
    suspend fun load(id:String)=withContext(Dispatchers.IO) { synchronized(lock) {
        val value=read(id);mutable.value=mutable.value+(id to value);value
    } }
    private suspend fun change(id:String,update:(AudioJournal)->AudioJournal)=withContext(Dispatchers.IO) { synchronized(lock) {
        val value=update(read(id));val folder=file(id).parentFile!!
        check(folder.isDirectory){"This audiobook is no longer available."}
        write(File(folder,"journal.json"),value);mutable.value=mutable.value+(id to value)
    } }
    suspend fun saveNote(id:String,note:AudioNote) {
        require(note.track>=0 && note.positionMs>=0 && note.text.trim().length in 1..2000)
        change(id){old->require(old.notes.any{it.id==note.id} || old.notes.size<100){"This book has reached its 100-note limit."}
            old.copy(notes=(old.notes.filterNot{it.id==note.id}+note.copy(text=note.text.trim(),passage=note.passage.take(2000))).sortedByDescending{it.updatedAt})}
    }
    suspend fun removeNote(id:String,noteId:String)=change(id){it.copy(notes=it.notes.filterNot{n->n.id==noteId})}
    /** Retain the original records before retiring their old editor. Consumer must be idempotent. */
    fun retireNotes(id:String,consume:(List<AudioNote>)->Unit)=synchronized(lock) {
        val old=read(id)
        if(old.notes.isNotEmpty()) {
            val archive=File(file(id).parentFile,"notes-legacy.json")
            val previous=if(archive.exists() || File(archive.path+".bak").exists()) {
                val bytes=AtomicFile(archive).openRead().use{it.takeBounded(MAX_BYTES+1)}
                decode(BoundedJson.objectFrom(bytes,MAX_BYTES,12,100_000))
            } else AudioJournal()
            val notes=(previous.notes+old.notes).distinctBy{it.id}
            require(notes.size<=100){"The original notes archive is full."}
            write(archive,AudioJournal(notes=notes))
            consume(old.notes)
            val updated=old.copy(notes=emptyList())
            write(file(id),updated);mutable.value=mutable.value+(id to updated)
        }
    }
    suspend fun saveSession(id:String,session:AudioListeningSession)=change(id){old->
        val previous=old.sessions.firstOrNull{it.id==session.id}
        if(session.id in old.clearedSessionIds || (previous!=null && (previous.listenedMs>session.listenedMs || (previous.closed && !session.closed))))old
        else old.copy(sessions=(old.sessions.filterNot{it.id==session.id}+session.copy(speeds=session.speeds.takeLast(32))).sortedBy{it.startedAt}.takeLast(200))}
    suspend fun clearSessions(id:String)=change(id){it.copy(sessions=emptyList(),clearedSessionIds=(it.clearedSessionIds+it.sessions.map{session->session.id}).distinct().takeLast(200))}
    fun forget(id:String)=synchronized(lock){mutable.value=mutable.value-id}
    fun splitTrack(id:String,childId:String,track:Int)=synchronized(lock) {
        val old=read(id);val child=read(childId)
        if(old.notes.isNotEmpty() || old.sessions.isNotEmpty())write(File(root,"split-journal-$id.json"),old)
        val notes=old.notes.filter{it.track==track}.map{it.copy(track=0)}
        val sessions=old.sessions.filter{it.startTrack==track && it.endTrack==track}.map{it.copy(startTrack=0,endTrack=0)}
        val merged=child.copy(notes=(child.notes+notes).distinctBy{it.id}.takeLast(100),sessions=(child.sessions+sessions).distinctBy{it.id}.takeLast(200))
        write(file(childId),merged);mutable.value=mutable.value+(childId to merged)
    }

    /** Called inside the catalog swap transaction, before either live directory is changed. */
    fun stageMapped(id:String,stage:File,map:(Int,Long)->Long)=synchronized(lock) {
        val old=read(id)
        if(old.notes.isNotEmpty() || old.sessions.isNotEmpty() || old.clearedSessionIds.isNotEmpty())write(File(stage,"journal.json"),old.copy(
            notes=old.notes.map{it.copy(positionMs=map(it.track,it.positionMs))},
            sessions=old.sessions.map{session->
                if(session.originalPositions)session
                else try {session.copy(startMs=map(session.startTrack,session.startMs),endMs=map(session.endTrack,session.endMs))}
                catch(_:IllegalArgumentException) {session.copy(originalPositions=true)}
            }))
    }
    companion object {
        const val MAX_BYTES=1024*1024
        fun newNote(track:Int,at:Long,passage:String="",now:Long=System.currentTimeMillis())=
            AudioNote(UUID.randomUUID().toString(),track,at,"",now,now,passage.take(2000))
        private fun write(target:File,value:AudioJournal) = DurableJson.write(AtomicFile(target),encode(value).toString().toByteArray(Charsets.UTF_8),MAX_BYTES)
        internal fun encode(value:AudioJournal):JSONObject=JSONObject().put("version",1).put("clearedSessionIds",JSONArray(value.clearedSessionIds))
            .put("notes",JSONArray(value.notes.map{n->JSONObject().put("id",n.id).put("track",n.track).put("positionMs",n.positionMs)
                .put("text",n.text).put("createdAt",n.createdAt).put("updatedAt",n.updatedAt).put("passage",n.passage)}))
            .put("sessions",JSONArray(value.sessions.map{s->JSONObject().put("id",s.id).put("startedAt",s.startedAt).put("endedAt",s.endedAt)
                .put("startTrack",s.startTrack).put("startMs",s.startMs).put("endTrack",s.endTrack).put("endMs",s.endMs)
                .put("listenedMs",s.listenedMs).put("closed",s.closed).put("originalPositions",s.originalPositions).put("speeds",JSONArray(s.speeds.map{JSONObject().put("at",it.at).put("speed",it.speed.toDouble())}))}))
        internal fun decode(json:JSONObject):AudioJournal {
            require(json.getInt("version")==1)
            val notes=json.getJSONArray("notes");val sessions=json.getJSONArray("sessions")
            require(notes.length()<=100 && sessions.length()<=200)
            fun nonnegative(j:JSONObject,key:String)=j.getLong(key).also{require(it>=0)}
            fun index(j:JSONObject,key:String)=j.getInt(key).also{require(it in 0..4095)}
            fun identity(j:JSONObject)=j.getString("id").also{require(it.length in 1..100)}
            return AudioJournal((0 until notes.length()).map{i->val n=notes.getJSONObject(i)
                AudioNote(identity(n),index(n,"track"),nonnegative(n,"positionMs"),n.getString("text").also{require(it.length in 1..2000)},
                    nonnegative(n,"createdAt"),nonnegative(n,"updatedAt"),n.optString("passage").also{require(it.length<=2000)})},
                (0 until sessions.length()).map{i->val s=sessions.getJSONObject(i);val speeds=s.getJSONArray("speeds");require(speeds.length()<=32)
                    AudioListeningSession(identity(s),nonnegative(s,"startedAt"),nonnegative(s,"endedAt"),index(s,"startTrack"),nonnegative(s,"startMs"),
                        index(s,"endTrack"),nonnegative(s,"endMs"),nonnegative(s,"listenedMs"),(0 until speeds.length()).map{k->val v=speeds.getJSONObject(k)
                            AudioSpeedEvent(nonnegative(v,"at"),v.getDouble("speed").toFloat().also{require(it.isFinite() && it in .5f..3f)})},s.optBoolean("closed"),s.optBoolean("originalPositions"))},
                json.optJSONArray("clearedSessionIds")?.let{ids->require(ids.length()<=200);(0 until ids.length()).map{ids.getString(it).also{value->require(value.length in 1..100)}}}.orEmpty())
        }
    }
}

/** Counts confirmed playing wall time. Seeking, buffering and pauses are excluded. */
internal class AudioSessionClock {
    var session:AudioListeningSession?=null;private set
    private var last:Long?=null
    fun playing(track:Int,at:Long,speed:Float,wall:Long,elapsed:Long):AudioListeningSession {
        val current=session ?: AudioListeningSession(UUID.randomUUID().toString(),wall,wall,track,at,track,at,0,listOf(AudioSpeedEvent(wall,speed)))
        val delta=last?.let{(elapsed-it).coerceIn(0,2500)} ?: 0L
        val events=if(current.speeds.lastOrNull()?.speed!=speed)(current.speeds+AudioSpeedEvent(wall,speed)).takeLast(32) else current.speeds
        last=elapsed
        return current.copy(endedAt=wall,endTrack=track,endMs=at,listenedMs=current.listenedMs+delta,speeds=events).also{session=it}
    }
    fun suspendClock(){last=null}
    fun close():AudioListeningSession? { last=null;return session?.copy(closed=true).also{session=null} }
}
