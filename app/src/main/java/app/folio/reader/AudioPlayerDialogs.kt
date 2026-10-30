@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.folio.reader

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.round

@Composable internal fun AudioSpeedDialog(speed: Float, apply: (Float)->Unit, dismiss: ()->Unit) {
    var selected by rememberSaveable { mutableFloatStateOf(speed) }
    AlertDialog(onDismissRequest=dismiss, title={Text("Playback speed")}, text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Text(String.format(java.util.Locale.ROOT,"%.2f×",selected),style=MaterialTheme.typography.headlineMedium)
            Slider(selected,{selected=round(it*20)/20},valueRange=.5f..3f,steps=49,
                modifier=Modifier.semantics{contentDescription="Playback speed"})
            Text("Choose a preset or adjust in 0.05× steps. Audio pitch stays unchanged.",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f).forEach { rate ->
                    FilterChip(selected=selected==rate,onClick={selected=rate},label={Text("${rate}×")})
                }
            }
            TextButton(onClick={selected=1f}) { Text("Reset to 1×") }
        }
    },confirmButton={TextButton(onClick={apply(selected);dismiss()}){Text("Apply speed")}},
        dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})
}

@Composable internal fun AudioSleepDialog(state: AudioPlaybackState?, preset: Int, passageAvailable: Boolean,
    timed: (Int,Boolean)->Unit, boundary: (Boolean)->Unit, extend: ()->Unit, clear: ()->Unit, dismiss: ()->Unit) {
    var fade by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Sleep timer")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            state?.sleepLabel?.let { Text(it,style=MaterialTheme.typography.titleMedium) }
            state?.sleepRemainingSeconds?.let { Text("${audioTime(it*1000)} remaining") }
            Row { Checkbox(fade,{fade=it});Text("Gentle fade in the last 10 seconds",Modifier.padding(top=12.dp)) }
            listOf(preset,5,15,30,45,60,90).distinct().forEach { minutes ->
                TextButton(onClick={timed(minutes,fade);dismiss()},modifier=Modifier.fillMaxWidth()) { Text("$minutes minutes") }
            }
            HorizontalDivider()
            TextButton(onClick={boundary(false);dismiss()},modifier=Modifier.fillMaxWidth()) { Text("Stop after this chapter") }
            TextButton(onClick={boundary(true);dismiss()},enabled=passageAvailable,modifier=Modifier.fillMaxWidth()) { Text("Stop after this passage") }
            if(!passageAvailable) Text("Passage stopping needs an active timed passage.",style=MaterialTheme.typography.bodySmall)
            if(state?.sleepRemainingSeconds!=null) TextButton(onClick={extend();dismiss()},modifier=Modifier.fillMaxWidth()) { Text("Extend by 5 minutes") }
        }
    },confirmButton={TextButton(onClick={clear();dismiss()}){Text("Turn off timer")}},
        dismissButton={TextButton(onClick=dismiss){Text("Close")}})
}
