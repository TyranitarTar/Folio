package app.folio.reader

import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(title: String, choices: List<String>, selected: String, choose: (String) -> Unit) {
    Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { item -> FilterChip(selected == item, { choose(item) }, label = { Text(item) }) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun GuidedPanel(guide: GuidedReading, start: (Boolean) -> Unit) {
    LaunchedEffect(guide) { guide.prepare() }
    val ready by guide.ready.collectAsStateWithLifecycle()
    val cursor by guide.cursor.collectAsStateWithLifecycle()
    val storageFailed by guide.storageFailed.collectAsStateWithLifecycle()
    val message by guide.message.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(guide.options) }
    val session by guide.session.collectAsStateWithLifecycle()
    var reflection by remember { mutableStateOf<String?>(null) }
    var pace by remember { mutableStateOf(guide.pace) }
    var custom by remember(pace.unit) { mutableStateOf(pace.speed.toInt().toString()) }
    val number = custom.toDoubleOrNull()
    val range = if (pace.unit == "CPS") 1.0..200.0 else 30.0..1200.0
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Guided reading", style = MaterialTheme.typography.headlineSmall)
        cursor?.locator?.text?.highlight?.let { Text("Saved word: $it", style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { start(false) }, enabled = ready && !storageFailed && number != null && number in range, modifier = Modifier.weight(1f)) { Text("Resume") }
            OutlinedButton(onClick = { start(true) }, enabled = ready && !storageFailed && number != null && number in range, modifier = Modifier.weight(1f)) { Text("Start from here") }
        }
        ChoiceRow("Focus mode", listOf("Window", "Groups", "Line"), options.mode) {
            options = options.copy(mode = it); guide.setOptions(options)
        }
        ChoiceRow("Previous / next buttons", listOf("Sentence", "Paragraph"), options.navigationUnit.replaceFirstChar { it.uppercase() }) {
            options = options.copy(navigationUnit = it.lowercase()); guide.setOptions(options)
        }
        Text("Skip whole sentences or paragraphs. The focus mode still controls automatic pacing.", style = MaterialTheme.typography.bodySmall)
        if (options.mode != "Line") {
            Text("Words in focus · ${options.words}")
            Slider(options.words.toFloat(), { options = options.copy(words = it.roundToInt()); guide.setOptions(options) }, valueRange = 1f..8f, steps = 6)
        }
        GuidedFocusPreview(options)
        if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (storageFailed) OutlinedButton(onClick = { guide.retrySaving() }) { Text("Retry saving position") }
        ChoiceRow("Timing", listOf("WPM", "CPS"), pace.unit) {
            pace = Pace(it, if (it == "CPS") 15.0 else 200.0); guide.setPace(pace)
        }
        Text(if (pace.unit == "CPS") "Characters per second · longer words stay highlighted longer" else "Words per minute · equal time for each word", style = MaterialTheme.typography.bodySmall)
        val presets = if (pace.unit == "CPS") listOf(10, 15, 25, 40) else listOf(150, 200, 225, 250, 275, 300, 350, 400, 450, 500)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { speed -> FilterChip(pace.speed == speed.toDouble(), { pace = pace.copy(speed = speed.toDouble()); guide.setPace(pace); custom = speed.toString() }, label = { Text(speed.toString()) }) }
        }
        OutlinedTextField(custom, { custom = it }, label = { Text("Custom ${pace.unit}") }, singleLine = true,
            isError = number == null || number !in range,
            supportingText = { Text("${range.start.toInt()}–${range.endInclusive.toInt()}") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (number != null && number in range) LaunchedEffect(number, pace.unit) { pace = pace.copy(speed = number); guide.setPace(pace) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Punctuation and paragraph pauses", Modifier.weight(1f))
            Switch(options.punctuation, { options = options.copy(punctuation = it); guide.setOptions(options) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Extra time for long words (WPM)", Modifier.weight(1f))
            Switch(options.longWords, { options = options.copy(longWords = it); guide.setOptions(options) })
        }
        if (session.words > 0) {
            Text("${session.words} words · ${session.elapsedMillis / 1000} seconds · ${session.effectiveWpm} actual WPM")
            Text("How well did you understand the passage? This is your own reflection, not a comprehension test.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Reread", "Mostly", "Clearly").forEach { answer -> FilterChip(reflection == answer, { reflection = answer }, label = { Text(answer) }) }
            }
            TextButton(onClick = { guide.resetSession(); reflection = null }) { Text("Reset session statistics") }
        }
    }
}

@Composable
private fun GuidedFocusPreview(options: GuidedOptions) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val animate=remember(context) { runCatching { android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f }.getOrDefault(true) }
    val words = listOf("A", "quiet", "afternoon", "with", "a", "book", "and", "a", "sleeping", "cat.")
    var step by remember(options.mode, options.words) { mutableIntStateOf(0) }
    LaunchedEffect(options.mode, options.words, animate) {
        if(!animate)return@LaunchedEffect
        while (true) { kotlinx.coroutines.delay(850); step = (step + if (options.mode == "Groups") options.words else 1) % 6 }
    }
    val begin = if (options.mode == "Line") 0 else step
    val end = if (options.mode == "Line") words.size else (begin + options.words).coerceAtMost(words.size)
    val color = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Text(buildAnnotatedString {
            append(words.take(begin).joinToString(" ").let { if (it.isEmpty()) it else "$it " })
            withStyle(SpanStyle(background = color, color = foreground)) { append(words.subList(begin, end).joinToString(" ")) }
            if (end < words.size) append(" " + words.drop(end).joinToString(" "))
        }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun TapZoneOverlay(settings: NavigationSettings, modifier: Modifier, rtl: Boolean = settings.direction == "RTL") {
    Canvas(modifier) {
        for (row in 0..2) for (col in 0..2) {
            val color = when (settings.action((col + .5f) / 3, (row + .5f) / 3, rtl)) {
                TapAction.NEXT -> Color(0x22609C82); TapAction.PREVIOUS -> Color(0x22E5C684); TapAction.MENU -> Color(0x22AABBAA)
            }
            drawRect(color, Offset(col * size.width / 3, row * size.height / 3), Size(size.width / 3, size.height / 3))
        }
    }
}
