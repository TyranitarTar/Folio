package app.folio.reader

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PicoDiagnosticsPanel(backend:String="pico",close:()->Unit) {
    val context=LocalContext.current
    val state by PicoBenchmarks.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current
    var audible by remember { mutableStateOf(true) }
    val label=if(backend=="plapre3")"Plapre 3" else "Pico"
    var wordTarget by remember { mutableIntStateOf(SpeechSettings.load(context).passageTargetWords) }
    var preload by remember { mutableIntStateOf(4) }
    var playback by remember { mutableFloatStateOf(SpeechSettings.load(context).rate) }
    var elapsed by remember { mutableLongStateOf(0) }
    LaunchedEffect(backend) { PicoBenchmarks.loadReport(context,backend) }
    LaunchedEffect(state.running,state.startedAt) {
        do { elapsed=if(state.startedAt>0)((if(state.running)android.os.SystemClock.elapsedRealtime() else state.finishedAt)-state.startedAt).coerceAtLeast(0)/1000 else 0
            if(state.running)kotlinx.coroutines.delay(1000)
        } while(state.running)
    }
    DisposableEffect(state.running) {
        val window=(context as? Activity)?.window
        val alreadyOn=((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0
        if(state.running)window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if(state.running && !alreadyOn)window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP)PicoBenchmarks.cancel() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);PicoBenchmarks.cancel() }
    }
    ModalBottomSheet(onDismissRequest={ if(state.running)PicoBenchmarks.cancel();close() },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("$label performance test",style=MaterialTheme.typography.headlineSmall)
            Text("Tests threads, passage size and buffering. The optional live trial plays audio while generating upcoming passages. Stop narration first; this may take several minutes.")
            if(!state.running) {
                ChoiceRow("Target words (nearest sentence)",listOf("Default","10","20","30","35"),if(wordTarget==0)"Default" else "$wordTarget") { wordTarget=it.toIntOrNull() ?: 0 }
                ChoiceRow("Preloaded passages",listOf("1","2","4","8"),"$preload") { preload=it.toInt() }
                ChoiceRow("Playback speed",listOf("1.0","1.5","2.0","3.0"),"$playback") { playback=it.toFloat() }
            }
            if(!state.running)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked=audible,onCheckedChange={audible=it})
                Text("Include audible live playback trial")
            }
            if(state.running) {
                Text("${state.phase} · ${elapsed}s elapsed · ${state.completed}/${state.total} milestones",style=MaterialTheme.typography.bodySmall)
                if(state.phase=="Loading / warm-up")LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress={if(state.total>0)state.completed.toFloat()/state.total else 0f},modifier=Modifier.fillMaxWidth())
            }
            if(state.backend==backend && state.message.isNotBlank())Text(state.message)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={PicoBenchmarks.start(context,backend,wordTarget,preload,playback,audible)},enabled=!state.running) { Text("Run benchmark") }
                if(state.running)OutlinedButton(onClick={PicoBenchmarks.cancel()}) { Text("Cancel") }
            }
            state.report?.takeIf { state.backend==backend }?.let { report ->
                val trials=remember(report){JSONObject(report).getJSONArray("trials")}
                Text("Thread count · generation time · real-time factor",style=MaterialTheme.typography.titleSmall)
                listOf(1,2,4,6,8).forEach { count ->
                    val items=(0 until trials.length()).map{trials.getJSONObject(it)}.filter{it.getInt("threads")==count}
                    if(items.isNotEmpty())Text(String.format(java.util.Locale.ENGLISH,"%d threads · %.2f s · %.2f×",count,
                        items.map{it.getDouble("wallMillis")}.average()/1000,items.map{it.getDouble("rtf")}.average()))
                }
                Text("A real-time factor below 1 keeps up at 1× playback; below 0.67 is needed at 1.5×. Results vary with heat, battery and the passage.",style=MaterialTheme.typography.bodySmall)
                val simulation=remember(report) { JSONObject(report).optJSONObject("bufferSimulation") }
                simulation?.let { row ->
                    Text("${row.optInt("actualWords")} words · ${row.optInt("preloadedPassages")} preloaded · ${row.optDouble("playbackRate")}×")
                    Text("Simulated buffering: ${row.optInt("simulatedStalls")} stalls · ${"%.1f".format(row.optDouble("simulatedStallSeconds"))} s")
                    Text("Generation is measured; playback consumption is simulated. This does not test audio output or Bluetooth.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={SpeechController.settings(context,SpeechSettings.load(context).copy(passageTargetWords=row.optInt("requestedWords"),lookaheadPassages=row.optInt("preloadedPassages",4)))},enabled=!state.running) { Text("Use tested chunk / lookahead settings") }
                }
                remember(report){JSONObject(report).optJSONObject("livePlayback")}?.let { row ->
                    Text("Live audio: ${row.optDouble("appliedPlaybackRate")}× · ${row.optInt("queueWaits")} queue waits · ${"%.1f".format(row.optDouble("queueWaitSeconds"))} s")
                    Text("${row.optInt("generationOverlappedPlayback")} passages generated while playback was active. Completion callbacks measure queue starvation, not acoustic or Bluetooth delay.",style=MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick={ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Share $label report")) }) { Text("Share diagnostics report") }
            }
        }
    }
}
