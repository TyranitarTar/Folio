package app.folio.reader

import android.content.Context
import androidx.work.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class VoicePackState(val id: String, val phase: String="Not downloaded", val bytes: Long=0, val message: String="", val retryAt: Long=0) {
    val busy get()=phase in setOf("Queued","Downloading","Verifying","Installing","Checking","Removing")
    fun json()=JSONObject().put("id",id).put("phase",phase).put("bytes",bytes).put("message",message).put("retryAt",retryAt)
}

internal class VoicePackStore(private val context: Context) {
    companion object { internal val operations=Mutex() }
    val catalog=VoicePackCatalog.load(context)
    private val prefs=context.getSharedPreferences("voice-packs",0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(catalog.associate { p ->
        val old=runCatching { JSONObject(prefs.getString(p.id,null) ?: "{}") }.getOrNull()
        val saved=old?.optString("phase","Not downloaded") ?: "Not downloaded"
        val phase=if(saved in setOf("Checking","Removing") || (saved=="Ready" && !directory(p).isDirectory))"Not downloaded" else saved
        p.id to VoicePackState(p.id,phase,old?.optLong("bytes") ?: 0,
            old?.optString("message").orEmpty(),old?.optLong("retryAt") ?: 0)
    })
    val states=mutable.asStateFlow()
    internal val existingCheck = scope.launch { catalog.forEach { pack ->
        operations.withLock {
            try {
                val previous=File(transferFolder(pack),"previous")
                if(!directory(pack).exists() && previous.isDirectory && verify(previous,pack)) {
                    directory(pack).parentFile!!.mkdirs()
                    check(previous.renameTo(directory(pack))) { "Previous voice files could not be recovered." }
                }
                if(directory(pack).isDirectory && !state(pack.id).busy) {
                update(VoicePackState(pack.id,"Checking",message="Checking existing voice files"))
                if(verify(directory(pack),pack)) {
                    File(directory(pack),".ready").writeText(pack.version)
                    update(VoicePackState(pack.id,"Ready",message="Available offline"))
                    deleteTree(previous)
                } else update(VoicePackState(pack.id,"Not downloaded",message="Download or repair this pack"))
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { update(VoicePackState(pack.id,"Failed",message=e.message ?: "Voice files could not be checked")) }
        }
    } }
    fun pack(id: String)=catalog.first { it.id==id }
    fun forBackend(backend: String)=catalog.firstOrNull { it.backend==backend }
    fun state(id: String)=mutable.value.getValue(id)
    fun directory(pack: VoicePack)=File(context.noBackupFilesDir,"local-voices/${pack.version}")
    fun transferFolder(pack: VoicePack)=File(context.noBackupFilesDir,"voice-transfers/${pack.id}").apply { mkdirs() }
    @Synchronized fun update(value: VoicePackState, force: Boolean=false) {
        require(value.id in mutable.value)
        if(!force && mutable.value[value.id]?.phase=="Cancelled" && value.busy)return
        check(prefs.edit().putString(value.id,value.json().toString()).commit()) { "Voice download state could not be saved." }
        mutable.value=mutable.value+(value.id to value)
    }
    fun enqueue(id: String) {
        val pack=pack(id);val old=state(id)
        if(old.busy || old.phase=="Ready")return
        update(old.copy(phase="Queued",message="Waiting for a connection"),force=true)
        val request=OneTimeWorkRequestBuilder<VoicePackWorker>().setInputData(workDataOf("pack" to id,"version" to pack.version))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("folio-voice-pack").build()
        WorkManager.getInstance(context).enqueueUniqueWork("folio-voice-$id",ExistingWorkPolicy.REPLACE,request)
    }
    fun cancel(id: String) {
        val old=state(id)
        if(old.phase in setOf("Ready","Removing"))return
        update(old.copy(phase="Cancelled",message="Partial download kept for resume"))
        WorkManager.getInstance(context).cancelUniqueWork("folio-voice-$id")
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val pack=pack(id)
        val speech=SpeechController.state.value
        require(!(speech.backend==pack.backend && (speech.bookId!=null || speech.previewing || speech.preparing))) { "Stop narration and local previews before removing this pack." }
        cancel(id)
        operations.withLock {
            // SpeechService closes its engine before clearing bookId; drain the JNI release queue.
            val drained=CompletableDeferred<Unit>()
            LocalVoiceWorker.executor.execute { drained.complete(Unit) };drained.await()
            val current=SpeechController.state.value
            require(!(current.backend==pack.backend && (current.bookId!=null || current.previewing || current.preparing))) { "This voice is still in use." }
            update(VoicePackState(id,"Removing",message="Removing downloaded voice files"))
            deleteTree(directory(pack));deleteTree(transferFolder(pack))
            update(VoicePackState(id,message="Your books and listening positions are kept"))
        }
    }
    private fun deleteTree(folder: File) {
        val root=context.noBackupFilesDir.canonicalFile
        require(folder.canonicalPath.startsWith(root.path+File.separator))
        folder.walkBottomUp().forEach { check(!it.exists() || it.delete()) { "Voice files could not be removed." } }
    }
    suspend fun install(pack: VoicePack, archive: File, progress: (Int,Int)->Unit) = operations.withLock {
        val folder=transferFolder(pack);val staging=File(folder,"installing")
        deleteTree(staging);check(staging.mkdirs())
        try {
            extract(pack,archive,staging,progress)
            currentCoroutineContext().ensureActive()
            check(state(pack.id).phase!="Cancelled") { "Voice download cancelled" }
            val target=directory(pack);target.parentFile!!.mkdirs()
            // No active engine may be using this version. A ready pack is never overwritten.
            val current=SpeechController.state.value
            check(!(current.backend==pack.backend && (current.bookId!=null || current.preparing || current.previewing))) { "Stop this voice before installing its files." }
            val drained=CompletableDeferred<Unit>();LocalVoiceWorker.executor.execute { drained.complete(Unit) };drained.await()
            val backup=File(folder,"previous");deleteTree(backup)
            File(staging,".ready").writeText(pack.version)
            withContext(NonCancellable) { synchronized(this@VoicePackStore) {
                check(state(pack.id).phase!="Cancelled") { "Voice download cancelled" }
                if(target.exists())check(target.renameTo(backup)) { "Previous voice files could not be preserved." }
                if(!staging.renameTo(target)) {
                    if(backup.exists())check(backup.renameTo(target))
                    error("The verified voice pack could not be activated.")
                }
                update(VoicePackState(pack.id,"Ready",pack.bytes,"Available offline"))
            } }
            deleteTree(backup)
        } finally { deleteTree(staging) }
    }
    internal suspend fun verify(folder: File, pack: VoicePack): Boolean {
        for(spec in pack.files.filter { it.name in pack.required }) {
            currentCoroutineContext().ensureActive()
            val file=File(folder,spec.name)
            if(!file.isFile || file.length()!=spec.bytes)return false
            try { if(hash(file)!=spec.sha256)return false } catch(e: java.io.IOException) { return false }
        }
        return true
    }
    internal suspend fun extract(pack: VoicePack, archive: File, destination: File, progress: (Int,Int)->Unit = { _,_-> }) {
        require(archive.length()==pack.bytes && hash(archive)==pack.sha256) { "The voice archive failed its checksum check. Retry the download." }
        val expected=pack.files.associateBy { it.name };val seen=mutableSetOf<String>();var expanded=0L
        ZipFile(archive).use { zip ->
            require(zip.size()==expected.size) { "The voice archive has unexpected files." }
            val entries=zip.entries();val buffer=ByteArray(65536)
            while(entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry=entries.nextElement();VoicePackCatalog.safeName(entry.name)
                val spec=expected[entry.name] ?: error("Unexpected voice file")
                require(seen.add(entry.name) && !entry.isDirectory && entry.size==spec.bytes)
                val target=File(destination,entry.name);require(target.canonicalPath.startsWith(destination.canonicalPath+File.separator))
                target.parentFile!!.mkdirs();val digest=MessageDigest.getInstance("SHA-256");var count=0L
                zip.getInputStream(entry).use { input -> target.outputStream().use { output -> while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer);if(n<0)break
                    count+=n;expanded+=n;require(count<=spec.bytes && expanded<=pack.expandedBytes)
                    digest.update(buffer,0,n);output.write(buffer,0,n)
                } } }
                require(count==spec.bytes && SpeechPcm.hex(digest.digest())==spec.sha256) { "A voice file failed verification." }
                progress(seen.size,expected.size)
            }
        }
        require(seen==expected.keys && expanded==pack.expandedBytes)
    }
    internal suspend fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        file.inputStream().use { input -> while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
        return SpeechPcm.hex(digest.digest())
    }
}
