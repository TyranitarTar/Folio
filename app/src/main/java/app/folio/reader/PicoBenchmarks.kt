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

internal data class PicoBenchmarkState(val running:Boolean=false,val message:String="",val report:String?=null)

/** User-started foreground experiment. Fixed original text; never touches a book or its cache. */
internal object PicoBenchmarks {
    private val mutable=MutableStateFlow(PicoBenchmarkState())
    val state=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private val execution=Mutex()
    @Volatile private var activeRun:Job?=null
    @Volatile private var engine:PicoEngine?=null
    fun cancel() { job?.cancel();activeRun?.cancel();engine?.cancelGeneration() }
    fun start(context:Context) {
        if(mutable.value.running || job?.isActive==true) return
        val app=context.applicationContext as FolioApp
        if(SpeechController.serviceRunning) { mutable.value=PicoBenchmarkState(message="Stop narration before benchmarking.");return }
        mutable.value=PicoBenchmarkState(running=true,message="Preparing Pico benchmark…")
        job=scope.launch {
            val wake=app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Folio:PicoBenchmark")
            try {
                wake.acquire(30*60*1000L)
                val result=run(app,progress={ mutable.value=mutable.value.copy(message=it) })
                val report=result.toString(2)
                withContext(Dispatchers.IO) { val folder=File(app.filesDir,"diagnostics").apply{mkdirs()};File(folder,"pico-benchmark.json").writeText(report) }
                mutable.value=PicoBenchmarkState(message="Benchmark complete. Compare timings before choosing a thread count.",report=report)
            } catch(cancelled:CancellationException) { mutable.value=mutable.value.copy(running=false,message="Benchmark cancelled.");throw cancelled }
            catch(error:Throwable) { mutable.value=PicoBenchmarkState(message="Benchmark failed: ${error.message.orEmpty()}") }
            finally { if(wake.isHeld)wake.release();job=null }
        }
    }
    suspend fun run(context:Context, progress:(String)->Unit = {}, counts:List<Int> = listOf(1,2,4,6,8), rounds:Int=2, groups:Boolean=true):JSONObject {
        check(execution.tryLock()) { "A Pico benchmark is already running." }
        try {
            check(!SpeechController.serviceRunning) { "Stop narration before benchmarking." }
            activeRun=currentCoroutineContext()[Job]
            mutable.value=mutable.value.copy(running=true)
            return measure(context,progress={ mutable.value=mutable.value.copy(message=it);progress(it) },counts,rounds,groups)
        } finally {
            engine?.close();engine=null;activeRun=null
            mutable.value=mutable.value.copy(running=false)
            execution.unlock()
        }
    }
    private suspend fun measure(context:Context, progress:(String)->Unit, counts:List<Int>, rounds:Int, groups:Boolean):JSONObject {
        require(counts.isNotEmpty() && counts.all{it in 1..8} && rounds in 1..3)
        val app=context.applicationContext
        val initialDevice=device(app)
        val trials=JSONArray()
        val prompt="Fire små fugle synger stille ved det åbne vindue."
        val order=listOf(4,1,8,2,6).filter{it in counts}+counts.filter{it !in listOf(4,1,8,2,6)}
        for(round in 0 until rounds) for(threads in if(round%2==0)order else order.reversed()) {
            currentCoroutineContext().ensureActive()
            progress("Round ${round+1}/$rounds · $threads inference threads · warming up")
            val local=PicoEngine(app,threads);engine=local
            try {
                local.prepare();local.generate("Godmorgen. Hun åbner sin bog.",SpeechSettings(backend="pico",picoThreads=threads))
                progress("Round ${round+1}/$rounds · measuring $threads threads")
                val cpu=Process.getElapsedCpuTime();val began=System.nanoTime()
                val clip=local.generate(prompt,SpeechSettings(backend="pico",picoThreads=threads))
                val wall=(System.nanoTime()-began)/1e6;val cpuMs=Process.getElapsedCpuTime()-cpu
                val profile=local.lastProfile
                trials.put(JSONObject().put("round",round).put("threads",threads).put("wallMillis",wall)
                    .put("processCpuMillis",cpuMs).put("averageProcessCpuCores",cpuMs/wall)
                    .put("audioSeconds",clip.samples.size.toDouble()/clip.sampleRate).put("rtf",wall*clip.sampleRate/clip.samples.size/1000)
                    .put("prefillMillis",profile[0]/1e6).put("decodeMillis",profile[1]/1e6).put("samplingMillis",profile[2]/1e6)
                    .put("kanadeMillis",profile[3]/1e6).put("hiftMillis",profile[4]/1e6).put("audioCodes",profile[6])
                    .put("nativeTotalMillis",profile[5]/1e6).put("audioCodesPerSecond",profile[6]*1e9/profile[5])
                    .put("audioDigest",clip.digest).put("device",device(app)))
            } finally { local.close();engine=null }
        }
        val grouping=JSONArray()
        if(groups) {
            val parts=listOf("Hun åbner forsigtigt sin bog og finder sin gamle plads.","Udenfor skinner solen gennem vinduet mens katten sover ved døren.","På bordet står en kop te som stadig er varm.","Historien fortsætter i morgen igen.")
            val local=PicoEngine(app,4);engine=local
            try {
                local.prepare();local.generate("Hun læser videre.",SpeechSettings(backend="pico"))
                for(number in 2..4) {
                    val texts=parts.take(number);val text=texts.joinToString(" ")
                    for(batch in listOf(false,true)) {
                        progress("Comparing ${text.split(' ').size} words · ${if(batch)"one group" else "separate sentences"}")
                        val began=System.nanoTime();var seconds=0.0
                        for(message in if(batch)listOf(text) else texts) {
                            val clip=local.generate(message,SpeechSettings(backend="pico"));seconds+=clip.samples.size.toDouble()/clip.sampleRate
                        }
                        grouping.put(JSONObject().put("words",text.split(' ').size).put("grouped",batch).put("wallMillis",(System.nanoTime()-began)/1e6).put("audioSeconds",seconds))
                    }
                }
            } finally { local.close();engine=null }
        }
        return JSONObject().put("schema",1).put("appVersion","0.10.2").put("initialDevice",initialDevice).put("device",device(app)).put("trials",trials).put("grouping",grouping)
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
