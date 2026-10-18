package app.folio.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal object VoicePackHttp {
    fun checkedRedirect(raw: String): String {
        val uri=URI(raw)
        require(uri.scheme=="https" && uri.userInfo==null && (uri.port==-1 || uri.port==443) && uri.host in
            setOf("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) { "Unsupported voice download redirect." }
        return uri.toASCIIString()
    }
    fun open(url: String, headers: Map<String,String> = emptyMap()): HttpURLConnection {
        var current=VoicePackCatalog.url(url)
        repeat(6) {
            val connection=URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
            connection.setRequestProperty("User-Agent","Folio voice packs/0.9")
            connection.setRequestProperty("Accept-Encoding","identity")
            headers.forEach { (k,v)->connection.setRequestProperty(k,v) }
            try {
                if(connection.responseCode in setOf(301,302,303,307,308)) {
                    current=checkedRedirect(URI(current).resolve(connection.getHeaderField("Location") ?: error("Missing redirect")).toString())
                    connection.disconnect()
                } else return connection
            } catch(e: Throwable) { connection.disconnect();throw e }
        }
        throw IOException("Too many voice download redirects")
    }
    fun check(c: HttpURLConnection) {
        if(c.responseCode !in 200..299)throw SourceHttpError(c.responseCode,c.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0,86400) ?: 0)
    }
}

class VoicePackCancelReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val id=intent.getStringExtra("pack") ?: return
        val store=(context.applicationContext as FolioApp).voicePacks
        if(store.catalog.any { it.id==id })store.cancelDownload(id)
    }
}

class VoicePackWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    companion object {
        private val transfers=Mutex()
        // Cancellation marks WorkManager state before a blocked HTTP read necessarily closes.
        internal suspend fun <T> exclusiveTransfers(action:suspend ()->T):T=transfers.withLock { action() }
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { transfers.withLock {
        val store=(applicationContext as FolioApp).voicePacks
        val pack=store.catalog.firstOrNull { it.id==inputData.getString("pack") && it.version==inputData.getString("version") } ?: return@withContext Result.failure()
        if(store.state(pack.id).phase in setOf("Cancelled","Ready","Cleaning"))return@withContext Result.success()
        try {
            if(store.state(pack.id).retryAt>System.currentTimeMillis())return@withContext Result.retry()
            setForeground(notification(pack,"Downloading voice data"))
            val folder=store.transferFolder(pack)
            require(folder.usableSpace>pack.bytes+pack.expandedBytes+32L*1024*1024) { "Free at least ${(pack.bytes+pack.expandedBytes+32L*1024*1024)/1_000_000} MB to download and install this pack." }
            val archive=download(pack,folder) { count ->store.update(VoicePackState(pack.id,"Downloading",count,"Downloading voice data")) }
            currentCoroutineContext().ensureActive()
            if(store.state(pack.id).phase=="Cancelled")return@withContext Result.success()
            store.update(VoicePackState(pack.id,"Verifying",pack.bytes,"Checking voice archive"))
            require(store.hash(archive)==pack.sha256) { "The voice download failed its checksum check. Retry." }
            store.update(VoicePackState(pack.id,"Installing",pack.bytes,"Preparing offline voice files"))
            var lastReport=0L
            store.install(pack,archive) { current,total ->
                val now=android.os.SystemClock.elapsedRealtime()
                if(current==total || now-lastReport>300) {
                    store.update(VoicePackState(pack.id,"Installing",pack.bytes,"Preparing voice files: $current of $total"));lastReport=now
                }
            }
            archive.delete();File(folder,"transfer.json").delete()
            Result.success()
        } catch(cancelled: CancellationException) {
            withContext(NonCancellable) { val old=store.state(pack.id)
                if(old.phase !in setOf("Ready","Cancelled"))store.update(old.copy(phase="Queued",message="Waiting to resume")) }
            throw cancelled
        } catch(e: Exception) {
            val transient=e is IOException && (e !is SourceHttpError || e.status==429 || e.status>=500)
            val retry=(e as? SourceHttpError)?.retrySeconds ?: 0
            val old=store.state(pack.id)
            if(old.phase=="Ready")return@withLock Result.success()
            if(old.phase!="Cancelled")store.update(old.copy(phase=if(transient && runAttemptCount<3)"Queued" else "Failed",
                message=e.message ?: "The voice download failed",retryAt=System.currentTimeMillis()+retry*1000))
            if(!transient) { File(store.transferFolder(pack),"archive.zip").delete();File(store.transferFolder(pack),"archive.part").delete() }
            if(transient && runAttemptCount<3)Result.retry() else Result.failure()
        }
    } }
    private fun notification(pack: VoicePack,message: String): ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice-downloads","Voice downloads",NotificationManager.IMPORTANCE_LOW))
        val cancel=PendingIntent.getBroadcast(applicationContext,pack.id.hashCode(),Intent(applicationContext,VoicePackCancelReceiver::class.java).putExtra("pack",pack.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=NotificationCompat.Builder(applicationContext,"voice-downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Folio · ${pack.label}").setContentText(message).setOngoing(true)
            .addAction(android.R.drawable.ic_delete,"Cancel",cancel).build()
        val id=7000+(pack.id.hashCode() and 0xffff)
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(id,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id,note)
    }
    private suspend fun download(pack: VoicePack,folder: File,progress: (Long)->Unit): File {
        val done=File(folder,"archive.zip");if(done.exists() && done.length()==pack.bytes)return done
        done.delete()
        val partial=File(folder,"archive.part");val metadata=File(folder,"transfer.json")
        val saved=runCatching { JSONObject(metadata.readText()) }.getOrNull()
        var offset=partial.length().takeIf { saved?.optString("sha256")==pack.sha256 && saved.optString("url")==pack.url && it<=pack.bytes } ?: 0
        val validator=saved?.optString("etag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
        val headers=if(offset>0)buildMap { put("Range","bytes=$offset-");if(validator!=null)put("If-Range",validator) } else emptyMap()
        var c=VoicePackHttp.open(pack.url,headers)
        try {
            if(c.responseCode==416) { c.disconnect();offset=0;c=VoicePackHttp.open(pack.url) }
            VoicePackHttp.check(c)
            if(c.responseCode==206) {
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(c.getHeaderField("Content-Range").orEmpty())
                require(offset>0 && match?.groupValues?.get(1)?.toLongOrNull()==offset && match.groupValues[2].toLongOrNull()==pack.bytes-1 && match.groupValues[3].toLongOrNull()==pack.bytes) { "Invalid voice download resume range" }
            } else offset=0
            require(c.contentLengthLong<0 || c.contentLengthLong+offset==pack.bytes) { "Unexpected voice download size" }
            metadata.writeText(JSONObject().put("url",pack.url).put("sha256",pack.sha256).put("etag",c.getHeaderField("ETag")).toString())
            var count=offset;var reported=0L;progress(count)
            c.inputStream.use { input -> FileOutputStream(partial,offset>0).use { output ->
                val buffer=ByteArray(65536)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer);if(n<0)break;count+=n;require(count<=pack.bytes)
                    output.write(buffer,0,n)
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-reported>500) { progress(count);reported=now }
                }
            } }
            if(count!=pack.bytes)throw IOException("Voice download interrupted; the partial file will resume")
            currentCoroutineContext().ensureActive()
            require(partial.renameTo(done));progress(count);return done
        } finally { c.disconnect() }
    }
}
