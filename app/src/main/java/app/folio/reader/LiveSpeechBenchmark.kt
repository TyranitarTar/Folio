package app.folio.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/** Bounded producer and real AudioTrack consumer; uses authored text and no book cursor/cache. */
internal object LiveSpeechBenchmark {
    suspend fun run(preload:Int, total:Int, rate:Float,
        progress:(String)->Unit={}, generate:suspend (Int)->AiAudioClip):JSONObject = coroutineScope {
        require(preload in 1..8 && total in preload..32)
        val ready=List(total){CompletableDeferred<AiAudioClip>()}
        val permits=Semaphore(preload)
        val completed=Channel<String>(Channel.UNLIMITED)
        val starts=ConcurrentHashMap<String,Long>()
        val intervals=Collections.synchronizedList(mutableListOf<Pair<Long,Long>>())
        val rows=JSONArray()
        val player=AiAudioPlayer(object:AiAudioPlayer.Listener {
            override fun onStarted(utteranceId:String){starts[utteranceId]=System.nanoTime()}
            override fun onCompleted(utteranceId:String){starts.remove(utteranceId)?.let{intervals.add(it to System.nanoTime())};completed.trySend(utteranceId)}
            override fun onError(utteranceId:String,message:String){completed.close(IllegalStateException(message))}
        })
        if(!player.setPlaybackRate(rate)){player.close();error("Audio output cannot use the requested speed.")}
        var stalls=0;var waited=0.0;var audio=0.0;var compute=0.0;var overlapping=0
        val start=System.nanoTime()
        val producer=launch(Dispatchers.Default) {
            try {
                for(index in 0 until total) {
                    permits.acquire()
                    val began=System.nanoTime()
                    val clip=generate(index)
                    val seconds=(System.nanoTime()-began)/1e9
                    if(index>=preload)check(player.queueNext(clip)){"Playback queue rejected a generated passage."}
                    synchronized(rows) {
                        rows.put(JSONObject().put("index",index).put("computeSeconds",seconds)
                            .put("audioSeconds",clip.samples.size.toDouble()/clip.sampleRate).put("generationStartedNanos",began).put("generationEndedNanos",System.nanoTime()))
                    }
                    ready[index].complete(clip)
                }
            } catch(error:Throwable){ready.forEach{it.completeExceptionally(error)};throw error}
        }
        try {
            val initial=(0 until preload).map{ready[it].await()}
            player.play(initial.first(),"0")
            initial.drop(1).forEach{check(player.queueNext(it))}
            val playbackStart=System.nanoTime()
            for(index in 0 until total) {
                val began=System.nanoTime();val wasReady=ready[index].isCompleted
                val clip=ready[index].await()
                if(index>0 && !wasReady) {stalls++;waited+=(System.nanoTime()-began)/1e9}
                if(index>0)check(player.append(clip,"$index")){"Playback did not finish in passage order."}
                permits.release()
                progress("Live playback · passage ${index+1}/$total · $stalls queue waits")
                audio+=clip.samples.size.toDouble()/clip.sampleRate
                withTimeout((clip.samples.size*1000L/clip.sampleRate/rate).toLong()+30000) {
                    check(completed.receive()=="$index"){"Playback completion order changed."}
                }
            }
            producer.join()
            val playbackSeconds=(System.nanoTime()-playbackStart)/1e9
            for(index in 0 until rows.length()) {
                compute+=rows.getJSONObject(index).getDouble("computeSeconds")
                val row=rows.getJSONObject(index)
                val overlap=intervals.any { (start,end)->start<row.getLong("generationEndedNanos") && end>row.getLong("generationStartedNanos") }
                row.put("generationOverlappedPlayback",overlap)
                if(overlap)overlapping++
            }
            JSONObject().put("requestedPlaybackRate",rate.toDouble()).put("appliedPlaybackRate",player.state.value.playbackRate.toDouble())
                .put("preloadedPassages",preload).put("passages",rows).put("audioSeconds",audio).put("generationSeconds",compute)
                .put("playbackWallSeconds",playbackSeconds).put("effectiveAudioPerWallSecond",audio/playbackSeconds)
                .put("totalWallSeconds",(System.nanoTime()-start)/1e9).put("queueWaits",stalls).put("queueWaitSeconds",waited)
                .put("generationOverlappedPlayback",overlapping)
                .put("method","Live generation overlaps real AudioTrack playback. Queue waits measure waiting for ungenerated passages after presentation completion callbacks; not an acoustic gap or Bluetooth latency measurement.")
        } finally {
            // Cancellation must stop output even when joining the producer would itself suspend.
            player.close();completed.close()
            withContext(NonCancellable){producer.cancelAndJoin()}
        }
    }
}
