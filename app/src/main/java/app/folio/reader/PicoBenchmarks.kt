package app.folio.reader

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

internal data class PicoBenchmarkState(val running:Boolean=false,val message:String="",val report:String?=null,val backend:String="pico",val startedAt:Long=0L,val finishedAt:Long=0L,val completed:Int=0,val total:Int=0,val phase:String="Ready")

/** User-started foreground experiment. Fixed original text; never touches a book or its cache. */
internal object PicoBenchmarks {
    private val mutable=MutableStateFlow(PicoBenchmarkState())
    val state=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private val execution=Mutex()
    @Volatile private var activeRun:Job?=null
    @Volatile private var engine:PicoEngine?=null
    suspend fun loadReport(context:Context,backend:String) {
        if(mutable.value.running)return
        val text=withContext(Dispatchers.IO) { File(context.filesDir,"diagnostics/$backend-benchmark.json").takeIf { it.isFile && it.length()<2_000_000 }?.readText() }
        if(text!=null && !mutable.value.running && runCatching { JSONObject(text).getJSONArray("trials") }.isSuccess)
            mutable.value=PicoBenchmarkState(message="Saved report from this device",report=text,backend=backend)
    }
    fun cancel() { job?.cancel();activeRun?.cancel();engine?.cancelGeneration() }
    fun start(context:Context,backend:String="pico",wordTarget:Int=0,preload:Int=4,playbackRate:Float=1f,audible:Boolean=true,startupSeconds:Int=12,compareThreads:Boolean=false) {
        require(backend in setOf("pico","plapre3"))
        if(mutable.value.running || job?.isActive==true) return
        val app=context.applicationContext as FolioApp
        if(SpeechController.serviceRunning) { mutable.value=PicoBenchmarkState(message="Stop narration before benchmarking.");return }
        mutable.value=PicoBenchmarkState(running=true,message="Preparing ${if(backend=="plapre3")"Plapre 3" else "Pico"} benchmark…",backend=backend,startedAt=android.os.SystemClock.elapsedRealtime())
        job=scope.launch {
            val wake=app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Folio:PicoBenchmark")
            try {
                wake.acquire(30*60*1000L)
                val settings=SpeechSettings.load(app).copy(backend=backend)
                val selectedThreads=settings.inferenceThreads.takeIf{it>0} ?: Runtime.getRuntime().availableProcessors().coerceIn(1,4)
                val result=run(app,counts=if(compareThreads)listOf(1,2,4,6,8) else listOf(selectedThreads),rounds=if(compareThreads)2 else 1,groups=compareThreads,startupSeconds=startupSeconds,progress={ mutable.value=mutable.value.copy(message=it) },backend=backend,wordTarget=wordTarget,preload=preload,playbackRate=playbackRate,audible=audible)
                mutable.value=mutable.value.copy(phase="Saving report")
                val report=result.toString(2)
                withContext(Dispatchers.IO) { val folder=File(app.filesDir,"diagnostics").apply{mkdirs()};File(folder,"$backend-benchmark.json").writeText(report) }
                mutable.value=mutable.value.copy(running=false,message="Benchmark complete.",report=report,backend=backend,finishedAt=android.os.SystemClock.elapsedRealtime(),phase="Complete")
            } catch(cancelled:CancellationException) { mutable.value=mutable.value.copy(running=false,message="Benchmark cancelled.");throw cancelled }
            catch(error:Throwable) { mutable.value=PicoBenchmarkState(message="Benchmark failed: ${error.message.orEmpty()}",backend=backend) }
            finally { if(wake.isHeld)wake.release();job=null }
        }
    }
    suspend fun run(context:Context, progress:(String)->Unit = {}, counts:List<Int> = listOf(1,2,4,6,8), rounds:Int=2, groups:Boolean=true,backend:String="pico",wordTarget:Int=0,preload:Int=4,playbackRate:Float=1f,audible:Boolean=false,startupSeconds:Int=12):JSONObject {
        require(backend in setOf("pico","plapre3"))
        check(execution.tryLock()) { "A Pico benchmark is already running." }
        try {
            check(!SpeechController.serviceRunning) { "Stop narration before benchmarking." }
            activeRun=currentCoroutineContext()[Job]
            mutable.value=mutable.value.copy(running=true,backend=backend,report=null)
            return measure(context,progress={ mutable.value=mutable.value.copy(message=it);progress(it) },counts,rounds,groups,backend,wordTarget,preload,playbackRate,audible,startupSeconds)
        } finally {
            engine?.close();engine=null;activeRun=null
            mutable.value=mutable.value.copy(running=false,finishedAt=android.os.SystemClock.elapsedRealtime())
            execution.unlock()
        }
    }
    private suspend fun measure(context:Context, progress:(String)->Unit, counts:List<Int>, rounds:Int, groups:Boolean,backend:String,wordTarget:Int,preload:Int,playbackRate:Float,audible:Boolean,startupSeconds:Int):JSONObject {
        require(wordTarget in 0..35 && preload in 1..32 && playbackRate in .5f..3f && startupSeconds in 0..30)
        mutable.value=mutable.value.copy(startedAt=android.os.SystemClock.elapsedRealtime(),completed=0,total=rounds*counts.size+(if(groups)6 else 0)+1+(if(audible)1 else 0))
        require(counts.isNotEmpty() && counts.all{it in 1..8} && rounds in 1..3)
        val app=context.applicationContext
        val initialDevice=device(app)
        val trials=JSONArray()
        val parts=listOf("Hun åbner forsigtigt sin bog og finder sin gamle plads.","Udenfor skinner solen gennem vinduet mens katten sover ved døren.","På bordet står en kop te som stadig er varm.","Historien fortsætter i morgen igen.")
        val selected=SpeechSettings.load(app).copy(backend=backend,passageTargetWords=wordTarget,lookaheadPassages=preload,startBufferSeconds=startupSeconds)
        val prompts=SpeechBufferConfiguration.prompts(selected)
        val prompt=prompts.first()
        fun milestone(){mutable.value=mutable.value.copy(completed=mutable.value.completed+1)}
        val order=listOf(4,1,8,2,6).filter{it in counts}+counts.filter{it !in listOf(4,1,8,2,6)}
        for(round in 0 until rounds) for(threads in if(round%2==0)order else order.reversed()) {
            currentCoroutineContext().ensureActive()
            mutable.value=mutable.value.copy(phase="Loading / warm-up")
            progress("Round ${round+1}/$rounds · $threads inference threads · warming up")
            val local=PicoEngine(app,threads,plapre3=backend=="plapre3",onPreparation={progress(it)});engine=local
            try {
                local.prepare();local.generate("Godmorgen. Hun åbner sin bog.",selected.copy(picoThreads=threads,plapre3Threads=threads))
                mutable.value=mutable.value.copy(phase="Generation")
                progress("Round ${round+1}/$rounds · measuring $threads threads")
                val cpu=Process.getElapsedCpuTime();val began=System.nanoTime()
                val clip=local.generate(prompt,selected.copy(picoThreads=threads,plapre3Threads=threads))
                val wall=(System.nanoTime()-began)/1e6;val cpuMs=Process.getElapsedCpuTime()-cpu
                val profile=local.lastProfile
                trials.put(JSONObject().put("round",round).put("threads",threads).put("wallMillis",wall)
                    .put("processCpuMillis",cpuMs).put("averageProcessCpuCores",cpuMs/wall)
                    .put("audioSeconds",clip.samples.size.toDouble()/clip.sampleRate).put("rtf",wall*clip.sampleRate/clip.samples.size/1000)
                    .put("prefillMillis",profile[0]/1e6).put("decodeMillis",profile[1]/1e6).put("samplingMillis",profile[2]/1e6)
                    .put("kanadeMillis",profile[3]/1e6).put("hiftMillis",profile[4]/1e6).put("audioCodes",profile[6])
                    .put("nativeTotalMillis",profile[5]/1e6).put("audioCodesPerSecond",profile[6]*1e9/profile[5])
                    .put("audioDigest",clip.digest).put("words",prompt.split(' ').size).put("device",device(app)))
                milestone()
            } finally { local.close();engine=null }
        }
        val grouping=JSONArray()
        if(groups) {
            val local=PicoEngine(app,4,plapre3=backend=="plapre3",onPreparation={progress(it)});engine=local
            try {
                local.prepare();local.generate("Hun læser videre.",selected)
                for(number in 2..4) {
                    val texts=parts.take(number);val text=texts.joinToString(" ")
                    for(batch in listOf(false,true)) {
                        mutable.value=mutable.value.copy(phase="Passage grouping")
                        progress("Comparing ${text.split(' ').size} words · ${if(batch)"one group" else "separate sentences"}")
                        val began=System.nanoTime();var seconds=0.0
                        for(message in if(batch)listOf(text) else texts) {
                            val clip=local.generate(message,selected);seconds+=clip.samples.size.toDouble()/clip.sampleRate
                        }
                        grouping.put(JSONObject().put("words",text.split(' ').size).put("grouped",batch).put("wallMillis",(System.nanoTime()-began)/1e6).put("audioSeconds",seconds))
                        milestone()
                    }
                }
            } finally { local.close();engine=null }
        }
        mutable.value=mutable.value.copy(phase="Buffer simulation")
        progress("Measuring generation with $preload preloaded passages at ${playbackRate}× simulated playback")
        val measured=(0 until trials.length()).map { trials.getJSONObject(it) }
        val best=measured.groupBy { it.getInt("threads") }.minBy { (_,rows)->rows.map { it.getDouble("rtf") }.average() }.key
        val local=PicoEngine(app,best,plapre3=backend=="plapre3",onPreparation={progress(it)});engine=local
        val buffered=JSONArray();var stallSeconds=0.0;var stalls=0;var generationSeconds=0.0;var playEnd=0.0;var generatedAudio=0.0;var initialCount=0;var playbackStarted=false;var initialBufferedSeconds=0.0
        try {
            local.prepare()
            for(index in 0 until 12) {
                currentCoroutineContext().ensureActive()
                progress("Buffer simulation · passage ${index+1}/12")
                val began=System.nanoTime();val clip=local.generate(prompts[index%prompts.size],selected.copy(picoThreads=best,plapre3Threads=best))
                val compute=(System.nanoTime()-began)/1e9;val duration=clip.samples.size.toDouble()/clip.sampleRate
                generationSeconds+=compute;generatedAudio+=duration
                buffered.put(JSONObject().put("computeSeconds",compute).put("audioSeconds",duration))
                if(!playbackStarted) {
                    initialCount++;initialBufferedSeconds+=duration;playEnd+=duration/playbackRate
                    if(initialCount>=preload || initialBufferedSeconds/playbackRate>=startupSeconds) {playbackStarted=true;playEnd+=generationSeconds}
                } else {
                    val wait=(generationSeconds-playEnd).coerceAtLeast(0.0)
                    if(wait>0){stalls++;stallSeconds+=wait}
                    playEnd=maxOf(playEnd,generationSeconds)+duration/playbackRate
                }
            }
        } finally { local.close();engine=null }
        milestone()
        val buffer=JSONObject().put("preloadedPassages",preload).put("initialPreloadedPassages",initialCount).put("startupBufferSeconds",startupSeconds).put("playbackRate",playbackRate.toDouble()).put("requestedWords",wordTarget)
            .put("actualWords",prompt.split(' ').size).put("threads",best).put("generationSeconds",generationSeconds).put("audioSeconds",generatedAudio)
            .put("simulatedStalls",stalls).put("simulatedStallSeconds",stallSeconds).put("passages",buffered)
            .put("method","Real sequential generation; modeled audio consumption after preloading. No physical playback, Bluetooth or real queue stall measurement.")
        val live=if(audible) {
            mutable.value=mutable.value.copy(phase="Live audio playback")
            val audio=app.getSystemService(android.media.AudioManager::class.java)
            val focus=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change->if(change<0)cancel() }.build()
            check(audio.requestAudioFocus(focus)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"Audio focus is unavailable; stop other playback and retry."}
            val playbackEngine=PicoEngine(app,best,plapre3=backend=="plapre3",onPreparation={progress(it)});engine=playbackEngine
            try {
                playbackEngine.prepare()
                LiveSpeechBenchmark.run(preload,12,playbackRate,progress,startupSeconds) {index->
                    playbackEngine.generate(prompts[index%prompts.size],selected.copy(picoThreads=best,plapre3Threads=best))
                }
            } finally {playbackEngine.close();engine=null;audio.abandonAudioFocusRequest(focus)}
        } else null
        if(audible)milestone()
        return JSONObject().put("schema",2).put("appVersion",app.packageManager.getPackageInfo(app.packageName,0).versionName).put("backend",backend).put("initialDevice",initialDevice).put("device",device(app)).put("trials",trials).put("grouping",grouping).put("bufferSimulation",buffer).put("livePlayback",live ?: JSONObject.NULL).put("requestedWords",wordTarget).put("startupBufferSeconds",startupSeconds).put("actualWordCounts",JSONArray(prompts.map{it.split(' ').size})).put("voice",selected.localVoice)
            .put("notes","Warmup and model loading excluded. CPU cores is process CPU time / wall time, including UI work; not per-model exclusive. Source playback rate is 1x. No listening cursor or voice preference changed.")
    }
    private fun device(context:Context):JSONObject {
        val info=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val power=context.getSystemService(PowerManager::class.java)
        val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("model",Build.MODEL).put("manufacturer",Build.MANUFACTURER).put("android",Build.VERSION.SDK_INT)
            .put("reportedCpuCores",Runtime.getRuntime().availableProcessors()).put("abi",Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            .put("availableMemoryMiB",info.availMem/1048576).put("totalMemoryMiB",info.totalMem/1048576)
            .put("processPssMiB",Debug.getPss()/1024.0)
            .put("batteryTemperatureC",(battery?.getIntExtra("temperature",0) ?: 0)/10.0)
            .put("thermalStatus",if(Build.VERSION.SDK_INT>=29)power.currentThermalStatus else -1)
    }
}
