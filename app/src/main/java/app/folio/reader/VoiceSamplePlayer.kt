package app.folio.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VoiceSampleState(val key: String?=null,val phase: String="Stopped",val error: String?=null)
internal class VoiceSamplePlayer(private val context: Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(VoiceSampleState());val state=mutable.asStateFlow()
    private var job: Job?=null;private var player: MediaPlayer?=null;private var generation=0L
    private val audio=context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest?=null
    fun play(pack: VoicePack,voice: PackVoice) {
        stop()
        val sample=voice.sample ?: run { mutable.value=VoiceSampleState(error="This voice sample is unavailable.");return }
        val key="${pack.id}:${voice.language}:${voice.id}";val ticket=++generation
        SpeechController.cancelPreview(context);SpeechController.pause(context)
        mutable.value=VoiceSampleState(key,"Loading")
        job=scope.launch {
            try {
                val file=withContext(Dispatchers.IO) { obtain(sample) }
                ensureActive();if(ticket!=generation)return@launch
                val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { change -> if(change!=AudioManager.AUDIOFOCUS_GAIN)stop() }.build()
                require(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio output is busy. Try the sample again." }
                focus=request
                val media=MediaPlayer();player=media
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener {
                    if(ticket==generation) { mutable.value=VoiceSampleState(key,"Playing");it.start() } else runCatching { it.release() }
                }
                media.setOnCompletionListener { if(ticket==generation)stop() }
                media.setOnErrorListener { _,_,_-> if(ticket==generation) { stop();mutable.value=VoiceSampleState(key,error="The voice sample could not play. Retry.") };true }
                media.prepareAsync()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(e: Exception) { if(ticket==generation) { stop();mutable.value=VoiceSampleState(key,error=e.message ?: "The sample could not load. Retry.") } }
        }
    }
    fun stop() {
        generation++;job?.cancel();job=null
        player?.let { runCatching { it.release() } };player=null
        focus?.let { audio.abandonAudioFocusRequest(it) };focus=null
        mutable.value=VoiceSampleState()
    }
    private suspend fun obtain(sample: VoiceSample): File {
        val folder=File(context.cacheDir,"voice-samples").apply { mkdirs() };val file=File(folder,sample.sha256+".m4a")
        suspend fun valid()=file.isFile && file.length()==sample.bytes && (context.applicationContext as FolioApp).voicePacks.hash(file)==sample.sha256
        if(valid())return file
        currentCoroutineContext().ensureActive()
        val temp=File(folder,sample.sha256+"-"+java.util.UUID.randomUUID()+".part");val c=VoicePackHttp.open(sample.url)
        try {
            VoicePackHttp.check(c)
            require(c.contentLengthLong<0 || c.contentLengthLong==sample.bytes)
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            c.inputStream.use { input -> temp.outputStream().use { out ->
                val buffer=ByteArray(16384)
                while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    count+=n;require(count<=sample.bytes && count<=2_000_000);digest.update(buffer,0,n);out.write(buffer,0,n)
                }
            } }
            require(count==sample.bytes && SpeechPcm.hex(digest.digest())==sample.sha256) { "The voice sample failed its checksum check. Retry." }
            currentCoroutineContext().ensureActive()
            if(valid())temp.delete() else { file.delete();require(temp.renameTo(file)) }
            return file
        } finally { c.disconnect();temp.delete() }
    }
}
