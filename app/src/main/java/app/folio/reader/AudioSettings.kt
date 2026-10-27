@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.folio.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun AudioSettingsSheet(close:()->Unit) {
    val context=LocalContext.current
    var options by remember{mutableStateOf(AudioPreferences.load(context))}
    var tab by rememberSaveable{mutableIntStateOf(0)}
    fun save(value:AudioPreferences){options=value;value.save(context)}
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(horizontal=20.dp)) {
            Text("Bluetooth & playback",style=MaterialTheme.typography.headlineSmall)
            PrimaryScrollableTabRow(tab,edgePadding=0.dp) {
                listOf("Headset","Interruptions","Sleep & rewind").forEachIndexed{i,title->Tab(tab==i,{tab=i},text={Text(title)})}
            }
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()).padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(tab) {
                    0 -> {
                        Text("Headset play / pause controls playback while Folio’s media session is active.")
                        ChoiceRow("Previous / next buttons",listOf("Skip seconds","Change chapter"),if(options.headsetChapters)"Change chapter" else "Skip seconds") {save(options.copy(headsetChapters=it=="Change chapter"))}
                        ChoiceRow("Back · seconds",listOf("5","10","15","30","60"),options.backSeconds.toString()){save(options.copy(backSeconds=it.toInt()))}
                        ChoiceRow("Forward · seconds",listOf("5","10","15","30","60"),options.forwardSeconds.toString()){save(options.copy(forwardSeconds=it.toInt()))}
                        Text("The notification uses these skip intervals. The player always offers 10-second and 1-minute buttons.",style=MaterialTheme.typography.bodySmall)
                        AudioSettingToggle("Pause when headphones disconnect",options.pauseOnDisconnect){save(options.copy(pauseOnDisconnect=it))}
                    }
                    1 -> {
                        AudioSettingToggle("Pause for notification audio",options.pauseForNotifications){save(options.copy(pauseForNotifications=it))}
                        AudioSettingToggle("Resume after calls and temporary interruptions",options.resumeAfterInterruption){save(options.copy(resumeAfterInterruption=it))}
                        Text("Folio pauses when another app takes audio focus. It resumes after a temporary interruption only if it was playing. Pressing Pause or Stop, or disconnecting headphones, cancels automatic resume. Apps that do not request audio focus cannot be detected reliably.",style=MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        ChoiceRow("Sleep timer preset · minutes",listOf("5","15","30","45","60","90"),options.sleepMinutes.toString()){save(options.copy(sleepMinutes=it.toInt()))}
                        Text("Start the timer from the player menu. It stays off until you choose it.",style=MaterialTheme.typography.bodySmall)
                        AudioSettingToggle("Rewind after a longer pause",options.autoRewind){save(options.copy(autoRewind=it))}
                        if(options.autoRewind) {
                            ChoiceRow("Rewind · seconds",listOf("3","5","10","15"),options.rewindSeconds.toString()){save(options.copy(rewindSeconds=it.toInt()))}
                            ChoiceRow("After · minutes",listOf("1","5","15"),options.afterMinutes.toString()){save(options.copy(afterMinutes=it.toInt()))}
                        }
                    }
                }
                TextButton(onClick=close,modifier=Modifier.fillMaxWidth()){Text("Done")}
            }
        }
    }
}

@Composable private fun AudioSettingToggle(label:String,value:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable{change(!value)}.padding(vertical=4.dp)) {
        Text(label,Modifier.weight(1f).padding(end=12.dp));Switch(value,change)
    }
}
