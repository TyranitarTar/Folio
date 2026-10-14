package app.folio.reader

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/** Opening or closing these controls never starts or resets narration. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SpeechPanel(bookId: String, currentLocator: Locator?, settingsOnly: Boolean = false, onAcceptedStart: () -> Unit = {}, onReadFromHere: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SpeechController.state.collectAsStateWithLifecycle()
    val app=context.applicationContext as FolioApp
    val packStates by app.voicePacks.states.collectAsStateWithLifecycle()
    var packsOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(SpeechSettings.load(context)) }
    var voices by remember { mutableStateOf<List<SpeechVoice>>(emptyList()) }
    var probing by remember { mutableStateOf(true) }
    var voiceMenu by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val thisBook = state.bookId == bookId
    val pack=app.voicePacks.forBackend(settings.backend)
    val localReady=!settings.isLocal || pack?.let { packStates[it.id]?.phase=="Ready" }==true
    val localVoices = when (settings.backend) {
        "supertonic" -> SupertonicEngine.voices.map { SpeechVoice(it.id.toString(), it.label) }
        "kokoro" -> KokoroEngine.voices.map { SpeechVoice(it.id.toString(), it.label) }
        else -> emptyList()
    }
    DisposableEffect(context) {
        var probe: TextToSpeech? = null
        probe = TextToSpeech(context) { status -> scope.launch {
            if (status == TextToSpeech.SUCCESS) {
                voices = probe?.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                    .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenByDescending { it.locale.country == "GB" }.thenBy { it.name })
                    .map { SpeechVoice(it.name, "${it.locale.getDisplayName(Locale.ENGLISH)} · ${it.name}") }
            }
            probing = false
        } }
        onDispose { probe?.shutdown(); probe = null }
    }
    LaunchedEffect(state.voices) { if (state.backend == "android" && state.voices.isNotEmpty()) voices = state.voices }
    LaunchedEffect(context) { SpeechController.loadFollow(context) }
    LaunchedEffect(state.error) { if(state.error!=null)settings=SpeechSettings.load(context) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (settingsOnly) "Speech settings" else "Read aloud", style = MaterialTheme.typography.headlineSmall)
        Text("Offline speech", style = MaterialTheme.typography.titleSmall)
        Text(if (settingsOnly) "Choose an offline voice and speed. Open a book to start listening." else "Listening has its own saved position. Browsing the book does not move it.", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.backend == "kokoro", onClick = {
                settings = settings.copy(backend = "kokoro"); SpeechController.settings(context, settings)
            }, label = { Text("Kokoro · English") })
            FilterChip(selected = settings.backend == "supertonic", onClick = {
                settings = settings.copy(backend = "supertonic"); SpeechController.settings(context, settings)
            }, label = { Text("Supertonic 3") })
            FilterChip(selected = settings.backend == "android", onClick = {
                settings = settings.copy(backend = "android"); SpeechController.settings(context, settings)
            }, label = { Text("Android speech") })
        }
        if(settings.isLocal) {
            OutlinedButton(onClick={packsOpen=true}) { Icon(Icons.Outlined.Download,null);Text("Voice packs and samples") }
            if(!localReady)Text("Download this voice pack before starting local narration. You can hear a sample first.",style=MaterialTheme.typography.bodySmall)
        }
        if (settings.backend == "kokoro") Text("Kokoro speaks entirely on your device after its voice pack is installed. Model loading may take a little longer on the first start.",
            style = MaterialTheme.typography.bodySmall)
        if (settings.backend == "supertonic") {
            Text("Supertonic speaks English and Danish offline after its voice pack is installed.", style = MaterialTheme.typography.bodySmall)
            Text("Language", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("en" to "English", "da" to "Dansk").forEach { (code, label) ->
                    FilterChip(selected = settings.supertonicLanguage == code, onClick = {
                        settings = settings.copy(supertonicLanguage = code); SpeechController.settings(context, settings)
                    }, label = { Text(label) })
                }
            }
        }
        if (thisBook && state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (thisBook && state.preparing) Text(state.preparation, style = MaterialTheme.typography.bodySmall)
        if ((thisBook || state.previewing || settingsOnly) && state.error != null) Text(state.error!!, color = MaterialTheme.colorScheme.error)
        if (settings.backend == "android" && !probing && voices.isEmpty()) {
            Text("No installed offline English voice was found. Enable a speech engine and download English voice data in Android Speech settings.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (thisBook && state.passage.isNotBlank()) Text(state.passage.take(240), style = MaterialTheme.typography.bodySmall)
        if (!settingsOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if(!localReady){packsOpen=true;return@Button}
                if (thisBook && !state.finished && state.error == null) {
                    if (state.playing || state.preparing) SpeechController.pause(context)
                    else { SpeechController.resume(context); onAcceptedStart() }
                } else { SpeechController.start(context, bookId); onAcceptedStart() }
            }, modifier = Modifier.weight(1f)) {
                Text(if(!localReady)"Download voice pack" else if (thisBook && state.playing) "Pause" else "Resume listening")
            }
            OutlinedButton(onClick = { if(localReady)onReadFromHere() else packsOpen=true },
                enabled = currentLocator != null, modifier = Modifier.weight(1f)) { Text("Read from here") }
        }
        if (thisBook) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { SpeechController.previous(context) }, enabled = !state.preparing) {
                Icon(Icons.Outlined.SkipPrevious, "Previous sentence")
            }
            IconButton(onClick = { SpeechController.next(context) }, enabled = !state.preparing) {
                Icon(Icons.Outlined.SkipNext, "Next sentence")
            }
            TextButton(onClick = { SpeechController.stop(context) }) { Text("Stop narration") }
        }
        Text("Voice", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { voiceMenu = true }, enabled = settings.isLocal || voices.isNotEmpty()) {
                Text(if (settings.isLocal) localVoices.firstOrNull { it.name == settings.localVoice.toString() }?.label ?: "Choose a voice"
                    else voices.firstOrNull { it.name == settings.voice || (settings.voice.isBlank() && it.name == state.selectedVoice) }?.label
                    ?: if (probing) "Checking installed voices…" else "Best available English voice", maxLines = 2)
            }
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                if (settings.isLocal) localVoices.forEach { voice ->
                    DropdownMenuItem(text = { Text(voice.label) }, onClick = {
                        settings = if (settings.backend == "supertonic") settings.copy(supertonicVoice = voice.name.toInt())
                            else settings.copy(aiVoice = voice.name.toInt())
                        SpeechController.settings(context, settings); voiceMenu = false
                    })
                } else voices.forEach { voice -> DropdownMenuItem(text = { Text(voice.label) }, onClick = {
                        settings = settings.copy(voice = voice.name); SpeechController.settings(context, settings); voiceMenu = false
                    }) }
            }
        }
        Text("Speed: ${String.format(Locale.ENGLISH, "%.2f", settings.rate)}×")
        Slider(value = settings.rate, onValueChange = { settings = settings.copy(rate = it) }, valueRange = .5f..3f, steps = 49,
            onValueChangeFinished = { SpeechController.settings(context, settings) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { rate ->
                FilterChip(kotlin.math.abs(settings.rate - rate) < .01f, {
                    settings = settings.copy(rate = rate); SpeechController.settings(context, settings)
                }, label = { Text("${rate}×") })
            }
        }
        OutlinedButton(onClick = {
            if(!localReady){packsOpen=true;return@OutlinedButton}
            app.voiceSamples.stop()
            if (state.previewing) SpeechController.cancelPreview(context) else SpeechController.preview(context, settings)
        }) { Text(if(!localReady)"Hear voice samples" else if (state.previewing) "Cancel voice preview" else "Preview voice") }
        if (state.previewing) {
            if (state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.preparing) state.preparation else "Playing voice preview…", style = MaterialTheme.typography.bodySmall)
        }
        Text("Reader navigation", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("line", "sentence", "paragraph").forEach { unit ->
                FilterChip(settings.navigationUnit == unit, {
                    settings = settings.copy(navigationUnit = unit); SpeechController.settings(context, settings)
                }, label = { Text(unit.replaceFirstChar { it.titlecase() }) })
            }
        }
        Text("Extra pauses", style = MaterialTheme.typography.titleSmall)
        Text("Between sentences · ${settings.sentencePauseMillis} ms", style = MaterialTheme.typography.bodySmall)
        Slider(settings.sentencePauseMillis.toFloat(), { settings = settings.copy(sentencePauseMillis = (it / 100).roundToInt() * 100) },
            valueRange = 0f..2000f, steps = 19, onValueChangeFinished = { SpeechController.settings(context, settings) })
        Text("Between paragraphs · ${settings.paragraphPauseMillis} ms", style = MaterialTheme.typography.bodySmall)
        Slider(settings.paragraphPauseMillis.toFloat(), { settings = settings.copy(paragraphPauseMillis = (it / 100).roundToInt() * 100) },
            valueRange = 0f..2000f, steps = 19, onValueChangeFinished = { SpeechController.settings(context, settings) })
        if (thisBook && !settingsOnly) {
            Text("Sleep timer", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 30, 60).forEach { minutes -> OutlinedButton(onClick = { SpeechController.sleep(context, minutes) }) { Text("$minutes min") } }
                OutlinedButton(onClick = { SpeechController.sleep(context, endChapter = true) }) { Text("End of ${state.chapterLabel.lowercase()}") }
                TextButton(onClick = { SpeechController.clearSleep(context) }) { Text("Off") }
            }
            if (state.sleepEndChapter) Text("Stops at the end of this ${state.chapterLabel.lowercase()}.", style = MaterialTheme.typography.bodySmall)
            state.sleepRemainingSeconds?.let { Text("${it / 60} min ${it % 60} sec remaining", style = MaterialTheme.typography.bodySmall) }
        }
        Text("Spoken passages are always highlighted and followed. Page navigation pauses listening and keeps its bookmark.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            runCatching { context.startActivity(intent) }.recoverCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure { settingsError = "Android Speech settings could not be opened on this device." }
        }) { Text("Android Speech settings") }
        settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.recoveryNote?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        if (settings.isLocal) Text("Pause resumes at the saved point in the audio. Changing voice or language repeats the current passage. Supertonic speed changes keep the current audio position. AI speech follows passages; word timing is unavailable.",
            style = MaterialTheme.typography.bodySmall)
        Text("All options work offline. Android speech uses installed English voices.",
            style = MaterialTheme.typography.bodySmall)
    }
    if(packsOpen)VoicePacksPanel(initialBackend=settings.backend,close={packsOpen=false})
}
