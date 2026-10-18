package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import androidx.work.WorkManager
import androidx.work.await
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal enum class VoiceCleanup { OLD_VERSIONS, DOWNLOADS }
internal data class VoiceStorageEntry(val key:String,val title:String,val bytes:Long,val category:String,
    val reason:String,val cleanup:VoiceCleanup?=null,val packId:String?=null,val version:String?=null)
internal data class VoiceStorageInventory(val entries:List<VoiceStorageEntry>) {
    val modelBytes get()=entries.filter { it.category in setOf("Current model","Older model","Unknown model") }.sumOf { it.bytes }
    val transferBytes get()=entries.filter { it.category in setOf("Download","Rollback","Transfer metadata","Unknown transfer") }.sumOf { it.bytes }
    val reclaimableBytes get()=entries.filter { it.cleanup!=null }.sumOf { it.bytes }
    fun reclaimable(kind:VoiceCleanup)=entries.filter { it.cleanup==kind }
}
internal data class VoiceCleanupResult(val bytes:Long,val removed:Int,val skipped:Int)
internal data class OwnedVoiceVersion(val id:String,val backend:String,val version:String,val url:String,val sha256:String,
    val files:List<SpeechModelFile>,val components:Set<String>) {
    fun json()=JSONObject().put("id",id).put("backend",backend).put("version",version).put("url",url).put("sha256",sha256)
        .put("files",JSONArray(files.map { JSONObject().put("name",it.name).put("bytes",it.bytes).put("sha256",it.sha256) }))
        .put("components",JSONArray(components.sorted()))
    companion object {
        fun from(j:JSONObject):OwnedVoiceVersion {
            val version=j.getString("version");require(VoiceModelUse.validVersion(version))
            val id=j.getString("id");require(id.matches(Regex("[a-z0-9-]{1,48}")))
            val backend=j.getString("backend");require(backend in setOf("kokoro","supertonic","pico","plapre3"))
            val url=VoicePackCatalog.url(j.getString("url"));val hash=j.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}")))
            val fs=j.getJSONArray("files");require(fs.length() in 1..5000)
            val files=(0 until fs.length()).map { val f=fs.getJSONObject(it);val name=f.getString("name");VoicePackCatalog.safeName(name)
                val bytes=f.getLong("bytes");val sha=f.getString("sha256");require(bytes in 0..(if(backend=="plapre3")2L*1024*1024*1024 else 768L*1024*1024)&&sha.matches(Regex("[a-f0-9]{64}")));SpeechModelFile(name,bytes,sha) }
            require(files.map { it.name }.distinct().size==files.size)
            val cs=j.getJSONArray("components");require(cs.length() in 1..32)
            val components=(0 until cs.length()).map { cs.getString(it).also { v->require(VoiceModelUse.validVersion(v)) } }.toSet()+version
            return OwnedVoiceVersion(id,backend,version,url,hash,files,components)
        }
        fun pack(pack:VoicePack,components:Set<String> = setOf(pack.version))=OwnedVoiceVersion(pack.id,pack.backend,pack.version,pack.url,pack.sha256,pack.files,components+pack.version)
    }
}

/** No name-prefix guesses. Only verified installed identities persist as deletion ownership. */
internal class VoiceStorage(private val context:Context,private val store:VoicePackStore,
    private val cancelTransfers:suspend (Set<String>)->Unit = { ids ->
        withTimeout(15000) { ids.forEach { WorkManager.getInstance(context).cancelUniqueWork("folio-voice-$it").await() } }
    }) {
    private val root=context.noBackupFilesDir.canonicalFile
    private val registry=AtomicFile(File(root,"voice-storage-owned.json"))
    private data class Tree(val files:List<File>,val directories:List<File>,val bytes:Long)
    companion object { private val registryLock=Any() }
    private fun checkRegistryPath() {
        for(name in listOf("voice-storage-owned.json","voice-storage-owned.json.bak","voice-storage-owned.json.new")) {
            val file=File(root,name);require(!Files.isSymbolicLink(file.toPath())&&file.canonicalPath.startsWith(root.path+File.separator)){ "Unsafe voice ownership registry; kept unchanged." }
        }
    }
    private fun readOwned():List<OwnedVoiceVersion> = synchronized(registryLock) {
        runCatching {
            checkRegistryPath()
            val raw=registry.openRead().use { it.boundedText(8_000_000) };BoundedJson.check(raw,12,100000)
            val j=JSONObject(raw);require(j.getInt("schema")==1);val a=j.getJSONArray("versions");require(a.length()<=64)
            (0 until a.length()).map { OwnedVoiceVersion.from(a.getJSONObject(it)) }.also { require(it.map { v->v.version }.distinct().size==it.size) }
        }.getOrDefault(emptyList())
    }
    /** Call only after verified adoption or verified archive activation, while operations is held. */
    internal fun recordVerified(pack:VoicePack,components:Set<String> = setOf(pack.version)) = synchronized(registryLock) {
        require(VoiceModelUse.validVersion(pack.version)&&components.all(VoiceModelUse::validVersion))
        checkRegistryPath()
        val records=(readOwned().filter { it.version!=pack.version }+OwnedVoiceVersion.pack(pack,components)).takeLast(64)
        val bytes=JSONObject().put("schema",1).put("versions",JSONArray(records.map { it.json() })).toString().toByteArray()
        require(bytes.size<=8_000_000)
        registry.baseFile.parentFile?.mkdirs();val out=registry.startWrite()
        try { out.write(bytes);registry.finishWrite(out);check(registry.openRead().use { it.readBytes().contentEquals(bytes) }) { "Voice ownership could not be saved." } }
        catch(e:Throwable){registry.failWrite(out);throw e}
    }
    internal fun markTransfer(pack:VoicePack,folder:File,newFolder:Boolean) {
        val marker=File(folder,".folio-transfer.json")
        if(marker.exists())return
        val matching=readTransfer(File(folder,"transfer.json"))?.let { it.first==pack.url&&it.second==pack.sha256 }==true
        if(!newFolder&&!matching&&folder.listFiles()?.isNotEmpty()==true)return
        runCatching { marker.writeText(JSONObject().put("id",pack.id).put("version",pack.version).put("url",pack.url).put("sha256",pack.sha256).toString()) }
    }
    private fun readTransfer(file:File):Pair<String,String>?=runCatching {
        require(file.isFile&&!Files.isSymbolicLink(file.toPath()));val j=JSONObject(file.inputStream().use { it.boundedText(16384) });j.getString("url") to j.getString("sha256")
    }.getOrNull()
    private fun exact(relative:String):File {
        VoicePackCatalog.safeName(relative)
        require(relative.startsWith("local-voices/")||relative.startsWith("voice-transfers/"))
        val file=File(root,relative);require(file.canonicalPath.startsWith(root.path+File.separator))
        var item:File?=file
        while(item!=null&&item.absolutePath!=root.absolutePath){require(!Files.isSymbolicLink(item.toPath()));item=item.parentFile}
        return file
    }
    private fun tree(file:File):Tree?=runCatching {
        if(!file.exists())return@runCatching Tree(emptyList(),emptyList(),0)
        val files=mutableListOf<File>();val directories=mutableListOf<File>();val pending=java.util.ArrayDeque<Pair<File,Int>>();pending.add(file to 0);var bytes=0L
        while(pending.isNotEmpty()) {
            val (next,depth)=pending.removeLast();require(depth<=128&&files.size+directories.size<20000)
            require(!Files.isSymbolicLink(next.toPath())&&next.canonicalPath.startsWith(root.path+File.separator))
            val attrs=Files.readAttributes(next.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            when { attrs.isRegularFile->{files+=next;bytes=Math.addExact(bytes,attrs.size())}
                attrs.isDirectory->{directories+=next;val children=next.listFiles() ?: error("Voice folder cannot be read");require(children.size<=20000-files.size-directories.size);children.forEach { pending.add(it to depth+1) }}
                else->error("Unknown voice storage entry") }
        };Tree(files,directories,bytes)
    }.getOrNull()
    private fun compatibleTree(folder:File,identity:OwnedVoiceVersion):Boolean {
        val found=tree(folder) ?: return false
        val allowed=identity.files.flatMap { listOf(it.name,it.name+".new",it.name+".bak") }.toSet()+setOf(".ready",".ready.new",".ready.bak")
        return found.files.all { it.relativeTo(folder).invariantSeparatorsPath in allowed }&&found.directories.all { d->d==folder||allowed.any { it.startsWith(d.relativeTo(folder).invariantSeparatorsPath+"/") } }
    }
    private fun referencedVersions(initial:Set<String>,records:Map<String,OwnedVoiceVersion>):Set<String> {
        val found=initial.toMutableSet();val pending=java.util.ArrayDeque<String>();initial.forEach(pending::add)
        while(pending.isNotEmpty()){val version=pending.removeLast();records[version]?.components.orEmpty().forEach { if(found.add(it))pending.add(it) }}
        return found
    }
    private fun children(name:String):List<File> {
        val folder=File(root,name)
        if(Files.isSymbolicLink(folder.toPath())||!folder.canonicalPath.startsWith(root.path+File.separator))return emptyList()
        return folder.listFiles()?.toList().orEmpty()
    }
    private fun blockedSpeech()=SpeechController.state.value.let { it.bookId!=null||it.previewing||it.preparing }||SpeechController.serviceRunning
    suspend fun inventory():VoiceStorageInventory=withContext(Dispatchers.IO) { VoicePackStore.operations.withLock { inventoryLocked() } }
    private fun inventoryLocked():VoiceStorageInventory {
        val owned=readOwned();val byVersion=owned.associateBy { it.version };val current=store.catalog.map { it.version }.toSet()
        val active=VoiceModelUse.active(context)
        val rollbackVersions=children("voice-transfers").mapNotNull { folder->runCatching {
            val previous=exact("voice-transfers/${folder.name}/previous");val marker=File(previous,".ready");require(!Files.isSymbolicLink(marker.toPath()));marker.inputStream().use { it.boundedText(1024).trim() }.takeIf(VoiceModelUse::validVersion)
        }.getOrNull() }.toSet()
        val protected=referencedVersions(current+active+rollbackVersions,byVersion)
        val rows=mutableListOf<VoiceStorageEntry>()
        for(name in listOf("local-voices","voice-transfers")) {
            val folder=File(root,name)
            if(Files.isSymbolicLink(folder.toPath())||!folder.canonicalPath.startsWith(root.path+File.separator))rows+=VoiceStorageEntry(name,name,0,if(name=="local-voices")"Unknown model" else "Unknown transfer","Linked or unsafe storage root; size unavailable; kept")
        }
        children("local-voices").forEach { folder ->
            val key="local-voices/${folder.name}";val shape=runCatching { tree(exact(key)) }.getOrNull();val bytes=shape?.bytes ?: 0L
            val identity=byVersion[folder.name];val currentPack=store.catalog.firstOrNull { it.version==folder.name }
            val reason=when {
                shape==null->"Unsafe or unreadable path; kept"
                folder.name in current->"Current catalog version; kept"
                folder.name in active->"Loaded by a voice engine; kept"
                folder.name in protected->"Rollback or shared component; kept"
                identity==null->"Unknown ownership; kept"
                !compatibleTree(folder,identity)->"Unrecognized contents; kept"
                blockedSpeech()->"Stop narration and previews before cleanup"
                else->"Verified ownership; superseded by the current catalog"
            }
            val removable=shape!=null&&folder.isDirectory&&identity!=null&&folder.name !in protected&&compatibleTree(folder,identity)&&!blockedSpeech()
            rows+=VoiceStorageEntry(key,currentPack?.label ?: identity?.id ?: folder.name,bytes,if(currentPack!=null)"Current model" else if(identity!=null)"Older model" else "Unknown model",reason,if(removable)VoiceCleanup.OLD_VERSIONS else null,currentPack?.id ?: identity?.id,folder.name)
        }
        children("voice-transfers").forEach { folder ->
            val packId=folder.name;val known=store.catalog.filter { it.id==packId }.map { OwnedVoiceVersion.pack(it) }+owned.filter { it.id==packId }
            val container=runCatching { exact("voice-transfers/$packId") }.getOrNull()
            val marker=container?.let { readTransfer(File(it,".folio-transfer.json")) };val meta=container?.let { readTransfer(File(it,"transfer.json")) }
            val identity=known.firstOrNull { it.url==(marker ?: meta)?.first&&it.sha256==(marker ?: meta)?.second }
            if(container==null||!folder.isDirectory||known.isEmpty()) {
                rows+=VoiceStorageEntry("voice-transfers/$packId",packId,container?.let(::tree)?.bytes ?: 0,"Unknown transfer","Unknown ownership or unsafe path; kept");return@forEach
            }
            folder.listFiles().orEmpty().forEach { entry ->
                val key="voice-transfers/$packId/${entry.name}";val shape=runCatching { tree(exact(key)) }.getOrNull();val bytes=shape?.bytes ?: 0L
                val rollback=entry.name=="previous"
                val recognized=identity!=null&&shape!=null&&when(entry.name) {
                    "archive.zip","archive.part","transfer.json"->entry.isFile
                    "installing"->entry.isDirectory&&compatibleTree(entry,identity)
                    else->false
                }
                rows+=VoiceStorageEntry(key,packId,bytes,when { rollback->"Rollback";entry.name==".folio-transfer.json"->"Transfer metadata";recognized->"Download";else->"Unknown transfer" },
                    when { rollback->"Retained for rollback; kept";recognized->"Discarding cancels this resumable download";else->"Metadata or unrecognized contents; kept" },
                    if(recognized)VoiceCleanup.DOWNLOADS else null,packId,identity?.version)
            }
        }
        for((folder,label) in listOf("voice-samples" to "Voice preview samples","ai-audio" to "Generated reading audio")) {
            val bytes=cacheBytes(File(context.cacheDir,folder));rows+=VoiceStorageEntry("cache/$folder",label,bytes,"Cache","Kept; generated audio can be cleared in Storage and backup")
        }
        return VoiceStorageInventory(rows)
    }
    private fun cacheBytes(folder:File):Long {
        val base=folder.canonicalFile;var bytes=0L;var seen=0;val pending=java.util.ArrayDeque<File>();pending.add(folder)
        while(pending.isNotEmpty()&&seen++<20000){val file=pending.removeLast();if(!file.exists()||Files.isSymbolicLink(file.toPath())||!file.canonicalPath.startsWith(base.path))continue
            if(file.isFile)bytes+=file.length()else file.listFiles().orEmpty().forEach(pending::add) };return bytes
    }
    private suspend fun drainNative() { val done=CompletableDeferred<Unit>();LocalVoiceWorker.executor.execute { done.complete(Unit) };withTimeout(15000){done.await()} }
    private suspend fun deleteOwned(relative:String,identity:OwnedVoiceVersion? = null):Long {
        val file=exact(relative);val scan=tree(file) ?: error("Voice storage changed or contains an unsafe path. Refresh and retry.")
        if(identity!=null)check(compatibleTree(file,identity)){ "Owned model contents changed. Unknown files have been kept." }
        var deleted=0L
        for(item in scan.files){currentCoroutineContext().ensureActive();val checked=exact(item.relativeTo(root).invariantSeparatorsPath);val size=checked.length();check(!checked.exists()||checked.delete()){ "A voice file could not be removed." };deleted+=size}
        for(item in scan.directories.asReversed()){currentCoroutineContext().ensureActive();val checked=exact(item.relativeTo(root).invariantSeparatorsPath);check(!checked.exists()||checked.delete()){ "A voice directory changed during cleanup. Refresh and retry." }}
        return deleted
    }
    private fun checkNoOtherTransfers(ids:Set<String>) {
        if(ids.isEmpty())return
        check(store.states.value.none { (id,state)->id !in ids&&state.phase in setOf("Queued","Downloading","Verifying","Installing") }) { "Wait for other voice downloads to finish before discarding this transfer." }
    }
    private suspend fun <T> withTransfersDrained(ids:Set<String>,action:suspend ()->T):T = if(ids.isEmpty())action() else withTimeout(45000){VoicePackWorker.exclusiveTransfers(action)}
    suspend fun clean(kind:VoiceCleanup,selected:Set<String>):VoiceCleanupResult=withContext(Dispatchers.IO) {
        val reviewed=inventory().reclaimable(kind).filter { it.key in selected };if(reviewed.isEmpty())return@withContext VoiceCleanupResult(0,0,selected.size)
        val ids=if(kind==VoiceCleanup.DOWNLOADS)reviewed.mapNotNull { it.packId }.toSet() else emptySet()
        checkNoOtherTransfers(ids)
        val previous=store.beginStorageCleanup(ids)
        try {
            if(ids.isNotEmpty())cancelTransfers(ids)
            withTransfersDrained(ids) { VoicePackStore.operations.withLock {
                drainNative();currentCoroutineContext().ensureActive()
                val fresh=inventoryLocked().reclaimable(kind).associateBy { it.key }
                val valid=reviewed.mapNotNull { before->fresh[before.key]?.takeIf { it.packId==before.packId&&it.version==before.version } }
                val versions=if(kind==VoiceCleanup.OLD_VERSIONS)valid.mapNotNull { it.version }.toSet() else emptySet()
                VoiceModelUse.reserve(context,versions).use {
                    if(kind==VoiceCleanup.OLD_VERSIONS)check(!blockedSpeech()){ "Stop narration and previews before cleanup." }
                    var bytes=0L;var count=0
                    for(entry in valid){currentCoroutineContext().ensureActive();bytes+=deleteOwned(entry.key,if(kind==VoiceCleanup.OLD_VERSIONS)readOwned().firstOrNull { it.version==entry.version } ?: error("Ownership changed; refresh storage") else null);count++}
                    VoiceCleanupResult(bytes,count,selected.size-count)
                }
            } }
        } finally { withContext(NonCancellable) { store.finishStorageCleanup(previous) } }
    }
    suspend fun removePack(id:String)=withContext(Dispatchers.IO) {
        val pack=store.pack(id);check(!blockedSpeech()){ "Stop narration and previews before removing voice files." }
        checkNoOtherTransfers(setOf(id))
        val previous=store.beginStorageCleanup(setOf(id));var removed=false
        try {
            // An already-ready pack without a transfer has no download to cancel. The worker
            // critical section is still drained below (also supports direct/offline installs).
            val transfer=File(root,"voice-transfers/$id")
            if(previous[id]?.phase!="Ready"||listOf("archive.part","archive.zip","transfer.json","installing").any { File(transfer,it).exists() })cancelTransfers(setOf(id))
            withTransfersDrained(setOf(id)) { VoicePackStore.operations.withLock {
                drainNative();check(!blockedSpeech()){ "This voice is still in use." }
                val owned=readOwned();val others=store.catalog.filter { it.id!=id }
                val protected=referencedVersions(others.map { it.version }.toSet(),owned.associateBy { it.version })
                check(pack.version !in protected){ "This model is shared with another current pack; it has been kept." }
                val old=inventoryLocked().reclaimable(VoiceCleanup.OLD_VERSIONS).filter { it.packId==id }
                val versions=(old.mapNotNull { it.version }+pack.version).toSet()
                VoiceModelUse.reserve(context,versions).use {
                    deleteOwned("local-voices/${pack.version}")
                    for(entry in old)deleteOwned(entry.key,owned.first { it.version==entry.version })
                    // Keep rollback and unknown entries even during an explicit pack removal.
                    val downloads=inventoryLocked().reclaimable(VoiceCleanup.DOWNLOADS).filter { it.packId==id }
                    for(entry in downloads)deleteOwned(entry.key)
                }
                store.update(VoicePackState(id,message="Your books and listening positions are kept"),force=true);removed=true
            } }
        } finally { withContext(NonCancellable) { store.finishStorageCleanup(previous,removed=if(removed)setOf(id)else emptySet()) } }
    }
    private fun java.io.InputStream.boundedText(limit:Int):String {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);if(n>0)out.write(buffer,0,n)}
        return out.toString("UTF-8")
    }
}
