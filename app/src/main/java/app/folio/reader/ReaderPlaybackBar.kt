package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** This surface reserves viewport space and survives hiding the reader chrome. */
@Composable
internal fun ReaderPlaybackBar(bookId: String, guide: GuidedReading?, navigate: (Int) -> Unit,
    pageStart: () -> Unit, readVisible: () -> Unit, settings: () -> Unit) {
    val context = LocalContext.current
    val speech by SpeechController.state.collectAsStateWithLifecycle()
    val active by (guide?.active ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val guideReady by (guide?.ready ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val running by (guide?.running ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val message by (guide?.message ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsStateWithLifecycle()
    val countdown by (guide?.countdown ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsStateWithLifecycle()
    val session by (guide?.session ?: remember { kotlinx.coroutines.flow.MutableStateFlow(GuidedSession()) }).collectAsStateWithLifecycle()
    val isSpeech = !active && speech.bookId == bookId && !speech.previewing
    if (!active && !isSpeech) return
    var more by remember { mutableStateOf(false) }
    val options = SpeechSettings.load(context)
    val unit = if (active) guide?.options?.navigationUnit ?: "sentence" else options.navigationUnit
    val preparing = isSpeech && speech.preparing
    var waitedSeconds by remember(speech.requestId, preparing) { mutableIntStateOf(0) }
    LaunchedEffect(speech.requestId, preparing) {
        if (preparing) while (true) { kotlinx.coroutines.delay(1000); waitedSeconds++ }
    }
    val playing = if (active) running else speech.playing
    val toggleDescription = if (playing) "Pause reading" else if (isSpeech && speech.error != null) "Retry narration" else "Resume reading"
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (preparing) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(when {
                active && countdown > 0 -> "Starting in $countdown…"
                active && message != null -> message!!
                active -> "Guided · ${if (session.words > 0) "${session.words} words · ${session.effectiveWpm} actual WPM" else guide?.options?.mode}"
                speech.error != null -> speech.error!!
                preparing -> "${speech.preparation} · ${waitedSeconds}s"
                speech.finished -> "End of book"
                !speech.playing -> "Listening paused"
                else -> options.copy(backend=speech.backend).engineLabel
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (isSpeech && speech.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (preparing) TextButton(onClick = { SpeechController.stop(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        if (preparing && waitedSeconds >= 10) Text("First use can take longer while the voice is prepared.", style = MaterialTheme.typography.labelSmall)
        if (isSpeech && (speech.sleepRemainingSeconds != null || speech.sleepEndChapter)) Text(
            if (speech.sleepEndChapter) "Sleep at ${speech.chapterLabel.lowercase()} end" else "Sleep in ${speech.sleepRemainingSeconds!! / 60}:${(speech.sleepRemainingSeconds!! % 60).toString().padStart(2, '0')}",
            style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navigate(-1) }, enabled = !preparing && countdown == 0 && (!active || guideReady), modifier = Modifier.size(48.dp).semantics { contentDescription = "Previous $unit" }) { Icon(Icons.Outlined.SkipPrevious, null) }
            FilledTonalIconButton(onClick = {
                if (active) { if (running) guide?.pause() else guide?.start() }
                else if (speech.error != null || speech.finished) SpeechController.start(context, bookId)
                else if (speech.playing || speech.preparing) SpeechController.pause(context) else SpeechController.resume(context)
            }, enabled = !preparing && (!active || guideReady), modifier = Modifier.size(48.dp).semantics { contentDescription = toggleDescription }) {
                Icon(if (playing) Icons.Outlined.Pause else if (speech.error != null) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                    null)
            }
            IconButton(onClick = { navigate(1) }, enabled = !preparing && countdown == 0 && (!active || guideReady), modifier = Modifier.size(48.dp).semantics { contentDescription = "Next $unit" }) { Icon(Icons.Outlined.SkipNext, null) }
            TextButton(onClick = {
                if (active && guide != null) {
                    val presets = if (guide.pace.unit == "CPS") listOf(10.0,15.0,25.0,40.0) else listOf(150.0,200.0,225.0,250.0,275.0,300.0,350.0,400.0,450.0,500.0)
                    guide.setPace(guide.pace.copy(speed = presets.firstOrNull { it > guide.pace.speed } ?: presets.first()))
                } else {
                    val rates = listOf(.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f)
                    SpeechController.settings(context, options.copy(rate = rates.firstOrNull { it > options.rate + .01f } ?: rates.first()))
                }
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (active) "${guide?.pace?.speed?.toInt()} ${guide?.pace?.unit}" else String.format(Locale.ENGLISH, "%.2f×", speech.playbackRate)) }
            IconButton(onClick = {
                guide?.clear()
                if (speech.bookId == bookId) SpeechController.stop(context)
            }, modifier = Modifier.size(48.dp).semantics { contentDescription = if (active) "Stop guided reading" else "Stop narration" }) {
                Icon(Icons.Outlined.Stop, null)
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Reading actions" }) { Icon(Icons.Outlined.MoreVert, null) }
                DropdownMenu(more, onDismissRequest = { more = false }) {
                    fun close(action: () -> Unit) { more = false; action() }
                    if (active && guide != null) {
                        listOf("sentence", "paragraph").forEach { target ->
                            DropdownMenuItem(text = { Text("Skip by $target${if (unit == target) " ✓" else ""}") }, onClick = {
                                close { guide.setOptions(guide.options.copy(navigationUnit = target)) }
                            })
                        }
                    }
                    DropdownMenuItem(text = { Text("Start at beginning of page") }, onClick = { close(pageStart) })
                    DropdownMenuItem(text = { Text("Read from visible text") }, onClick = { close(readVisible) })
                    DropdownMenuItem(text = { Text("Replay sentence") }, onClick = { close { if (active) guide?.replaySentence() else SpeechController.replay(context) } })
                    if (!active) {
                        DropdownMenuItem(text = { Text("Previous ${speech.chapterLabel.lowercase()}") }, onClick = { close { SpeechController.navigate(context, -1, "chapter") } })
                        DropdownMenuItem(text = { Text("Next ${speech.chapterLabel.lowercase()}") }, onClick = { close { SpeechController.navigate(context, 1, "chapter") } })
                    }
                    DropdownMenuItem(text = { Text(if (active) "Guided settings" else "Voice and sleep settings") }, onClick = { close(settings) })
                    DropdownMenuItem(text = { Text(if (active) "Stop guided reading" else "Stop narration") }, onClick = {
                        close { if (active) guide?.clear() else SpeechController.stop(context) }
                    })
                }
            }
        }
    }
}
