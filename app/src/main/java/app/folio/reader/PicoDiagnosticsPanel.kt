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
@Composable internal fun PicoDiagnosticsPanel(close:()->Unit) {
    val context=LocalContext.current
    val state by PicoBenchmarks.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current
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
            Text("Pico performance test",style=MaterialTheme.typography.headlineSmall)
            Text("Compares 1, 2, 4, 6 and 8 inference threads, then groups of 20, 30 and 35 words. This can take several minutes. Stop narration first; keep this screen open. It does not read your book or move your position.")
            if(state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.message.isNotBlank())Text(state.message)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={PicoBenchmarks.start(context)},enabled=!state.running) { Text("Run benchmark") }
                if(state.running)OutlinedButton(onClick={PicoBenchmarks.cancel()}) { Text("Cancel") }
            }
            state.report?.let { report ->
                val trials=remember(report){JSONObject(report).getJSONArray("trials")}
                Text("Thread count · generation time · real-time factor",style=MaterialTheme.typography.titleSmall)
                listOf(1,2,4,6,8).forEach { count ->
                    val items=(0 until trials.length()).map{trials.getJSONObject(it)}.filter{it.getInt("threads")==count}
                    if(items.isNotEmpty())Text(String.format(java.util.Locale.ENGLISH,"%d threads · %.2f s · %.2f×",count,
                        items.map{it.getDouble("wallMillis")}.average()/1000,items.map{it.getDouble("rtf")}.average()))
                }
                Text("A real-time factor below 1 keeps up at 1× playback; below 0.67 is needed at 1.5×. Results vary with heat, battery and the passage.",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Share Pico report")) }) { Text("Share diagnostics report") }
            }
        }
    }
}
