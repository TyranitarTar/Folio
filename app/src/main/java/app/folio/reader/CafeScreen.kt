@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.folio.reader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable internal fun CafeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as FolioApp
    val store = app.cafe
    val profiles by store.profiles.collectAsStateWithLifecycle()
    var test by remember { mutableStateOf(false) }
    val state = if(test) profiles.test else profiles.normal
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var story by remember { mutableStateOf(false) }
    var animation by rememberSaveable { mutableStateOf<String?>(null) }
    var resetPrompt by remember { mutableStateOf(false) }
    var restorePrompt by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if(busy) return
        busy = true
        scope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { snackbar.showSnackbar("Det kunne ikke gemmes. Kontrollér ledig plads, og prøv igen.") }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) {
        try { store.load() }
        catch(_: Exception) { snackbar.showSnackbar("Caféen kunne ikke indlæses. Dine bøger er stadig tilgængelige.") }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { run {
            val data = app.cafeReading.export()
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(it)).use { output -> output.write(data.toByteArray(Charsets.UTF_8)) } }
            snackbar.showSnackbar("Caféen er eksporteret. Testprofilen er ikke med.")
        } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { run {
            val data = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(it)).use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while(true) { val n = input.read(buffer); if(n < 0) break; require(bytes.size() + n <= 16_384); bytes.write(buffer, 0, n) }
                    bytes.toString("UTF-8")
                }
            }
            app.cafeReading.restore(data); story = false
            snackbar.showSnackbar("Den almindelige café er gendannet. Bøger og testprofil er bevaret.")
        } }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().widthIn(max = 760.dp).align(Alignment.TopCenter),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(test) "TESTCAFÉ" else "Folio Kattecafé", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                        Text("Et lille hvil mellem siderne", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { tools = true }, enabled = profiles.ready && !busy) { Icon(Icons.Outlined.Tune, "Caféindstillinger og testtilstand") }
                }
            }
            if(!profiles.ready) item {
                Text("Indlæser caféen…")
                OutlinedButton(onClick = { run { store.load() } }, enabled = !busy) { Text("Prøv igen") }
            } else if(!state.enabled) {
                item { MisoArtwork(Modifier.fillMaxWidth().aspectRatio(1.55f), room = true) }
                item {
                    Text("Mød Miso", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text("Læs sammen med caféens selvudnævnte læsechef. Giv ham godbidder, se små animationer, og oplev hans historie på dansk.")
                    Spacer(Modifier.height(12.dp))
                    Text("Du får 12 velkomstgodbidder og derefter 1 pr. aktivt læse- eller lytteminut. Hurtigere oplæsning giver ikke ekstra. Der er ingen straf for pauser eller glemte dage.")
                    Button(onClick = { run { store.setEnabled(true) } }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Åbn min café") }
                    TextButton(onClick = { test = true }) { Text("Prøv alt i testcaféen") }
                }
            } else {
                if(test) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Kun testprofilen ændres", style = MaterialTheme.typography.titleMedium)
                            Text("Alle kapitler og animationer kan prøves frit. Dine optjente godbidder og din rigtige historie er adskilt.")
                            TextButton(onClick = { story = false; animation = null; test = false }, enabled = !busy) { Text("Tilbage til min café") }
                        }
                    }
                }
                item { MisoArtwork(Modifier.fillMaxWidth().aspectRatio(1.55f), room = true, relaxed = state.completed(5)) }
                item {
                    Text("Miso · læsechefen", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.balance} godbidder", style = MaterialTheme.typography.titleMedium)
                        Text("${state.redemptions} samvær")
                        Text("${state.spent} givet")
                    }
                    if(!test) Text("1 godbid pr. aktivt minut · ${state.readingMillis / 60_000} minutter optjent", style = MaterialTheme.typography.bodySmall)
                    if(app.cafeReading.savingError) Text("Læsetid kunne ikke gemmes. Kontrollér ledig plads.", color = MaterialTheme.colorScheme.error)
                }
                item { Text("Lidt tid sammen", style = MaterialTheme.typography.titleMedium) }
                items(CafeAction.entries.toList(), key = { "action:${it.name}" }) { action ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(action.title, style = MaterialTheme.typography.titleMedium)
                            Text(action.caption, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = {
                                val profile = test
                                val token = UUID.randomUUID().toString()
                                run { if(store.redeem(profile, action, token)) animation = action.name }
                            }, enabled = !busy && state.balance >= action.cost) { Text("${action.cost} ${if(action.cost == 1) "godbid" else "godbidder"}") }
                            if(state.balance < action.cost) Text("Læs eller lyt lidt mere for at få flere godbidder.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("Misos store plan", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                    Text("Seks små kapitler om at finde sin plads. Samvær og godbidder, du giver, åbner historien. Du behøver ikke spare op.")
                }
                items(MisoStory.chapters, key = { "story:${it.id}" }) { chapter ->
                    val available = state.available(chapter.id)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${chapter.id + 1}. ${chapter.title}", style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                state.completed(chapter.id) -> "Læst · kan opleves igen"
                                available -> "Klar til at læse"
                                else -> "${minOf(state.redemptions, chapter.visits.toLong())}/${chapter.visits} samvær · ${minOf(state.spent, chapter.treats.toLong())}/${chapter.treats} godbidder givet"
                            }, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { run { if(store.openScene(test, chapter.id)) story = true } }, enabled = !busy && (available || test)) {
                                Text(if(state.scene == chapter.id) "Fortsæt kapitlet" else if(state.completed(chapter.id)) "Læs igen" else if(test && !available) "Åbn som test" else "Læs kapitlet")
                            }
                            if(state.scene == chapter.id && state.page > 0) TextButton(onClick = { run { store.openScene(test, chapter.id, replay = true); story = true } }, enabled = !busy) { Text("Start forfra") }
                        }
                    }
                }
                item { Text("Ingen daglig pligt. Miso venter gerne. Caféen spiller ingen lyd og afbryder ikke oplæsningen.", style = MaterialTheme.typography.bodySmall) }
            }
            item { TextButton(onClick = { tools = true }, enabled = profiles.ready && !busy) { Text("Indstillinger, sikkerhedskopi og testtilstand") } }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
    if(story && state.scene in 0..5 && state.enabled) CafeStoryPlayer(state, busy,
        next = { page, choice -> run { store.page(test, state.scene, page, choice) } },
        finish = { run { store.finishScene(test, state.scene); story = false } }, close = { story = false })
    animation?.let { name -> CafeAnimation(CafeAction.valueOf(name), state.reducedMotion, { animation = null }) }
    if(tools) ModalBottomSheet(onDismissRequest = { tools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Caféindstillinger", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Testtilstand · separat profil", Modifier.weight(1f))
                Switch(test, { test = it; story = false; animation = null }, enabled = !busy)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reduceret bevægelse", Modifier.weight(1f))
                Switch(state.reducedMotion, { run { store.reducedMotion(test, it) } }, enabled = !busy)
            }
            if(test) {
                Text("Test de rigtige regler", style = MaterialTheme.typography.titleMedium)
                Text("Kapitler kan altid åbnes i test. Knapperne herunder ændrer tællere og beregner de normale låse.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { run { store.testCounters(0, 0, 1000) } }, enabled = !busy) { Text("Låst") }
                    OutlinedButton(onClick = { run { store.testCounters(9, 10, 1000) } }, enabled = !busy) { Text("Halvvejs") }
                    OutlinedButton(onClick = { run { store.testCounters(20, 25, 1000) } }, enabled = !busy) { Text("Alt åbent") }
                    OutlinedButton(onClick = { run { store.testReadAll() } }, enabled = !busy) { Text("Alt læst") }
                }
                var visits by remember { mutableStateOf(state.redemptions.toString()) }
                var spent by remember { mutableStateOf(state.spent.toString()) }
                var balance by remember { mutableStateOf(state.balance.toString()) }
                LaunchedEffect(state.redemptions, state.spent, state.balance) {
                    visits = state.redemptions.toString(); spent = state.spent.toString(); balance = state.balance.toString()
                }
                listOf(Triple("Samvær", visits, { value: String -> visits = value }), Triple("Givet i alt", spent, { value: String -> spent = value }), Triple("Testgodbidder", balance, { value: String -> balance = value })).forEach { (label, value, edit) ->
                    OutlinedTextField(value, { if(it.length <= 5 && it.all(Char::isDigit)) edit(it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                val counters = listOf(visits.toLongOrNull(), spent.toLongOrNull(), balance.toLongOrNull())
                Button(onClick = { run { store.testCounters(visits.toLong(), spent.toLong(), balance.toLong()) } }, enabled = !busy && counters.all { it != null && it in 0..10000 }) { Text("Beregn låse med disse tællere") }
                Text("Afspil animation uden køb", style = MaterialTheme.typography.titleMedium)
                CafeAction.entries.forEach { action -> OutlinedButton(onClick = { tools = false; animation = action.name }, enabled = !busy) { Text(action.title) } }
                OutlinedButton(onClick = { resetPrompt = true }, enabled = !busy) { Text("Nulstil kun testcaféen") }
            } else {
                Text("Aktiv læsning tæller i læsevisningen. Efter to minutter uden berøring holder manuel læsning pause i optjeningen. Kørende læseguide og afspillet bogoplæsning tæller også. Samtidig læsning og lytning tæller kun én gang.")
                Text("Caféen har sin egen sikkerhedskopi. Den almindelige bogbackup indeholder ikke caféen.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { export.launch("Folio-cafe.json") }, enabled = !busy) { Text("Eksportér café") }
                OutlinedButton(onClick = { restorePrompt = true }, enabled = !busy) { Text("Gendan café") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Café og optjening aktive", Modifier.weight(1f))
                    Switch(state.enabled, { run { store.setEnabled(it) } }, enabled = !busy)
                }
                Text("Slå fra for at holde pause. Opsparing og historie bliver gemt; velkomstgodbidder gives kun én gang.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { tools = false }) { Text("Luk indstillinger") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if(resetPrompt) AlertDialog(onDismissRequest = { resetPrompt = false }, title = { Text("Nulstil testcaféen?") },
        text = { Text("Kun testgodbidder, tællere og testhistorie nulstilles. Din almindelige café bevares.") },
        confirmButton = { TextButton(onClick = { resetPrompt = false; run { store.resetTest() } }) { Text("Nulstil test") } },
        dismissButton = { TextButton(onClick = { resetPrompt = false }) { Text("Annuller") } })
    if(restorePrompt) AlertDialog(onDismissRequest = { restorePrompt = false }, title = { Text("Erstat caféens gemte data?") },
        text = { Text("Sikkerhedskopien erstatter din almindelige café, inklusive godbidder og historie. Beløb lægges ikke sammen. Eksportér først, hvis du vil bevare den nuværende café. Bøger og testprofil ændres ikke.") },
        confirmButton = { TextButton(onClick = { restorePrompt = false; restore.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Vælg cafébackup") } },
        dismissButton = { TextButton(onClick = { restorePrompt = false }) { Text("Annuller") } })
}

@Composable private fun CafeStoryPlayer(state: CafeState, busy: Boolean, next: (Int, Int) -> Unit, finish: () -> Unit, close: () -> Unit) {
    val chapter = MisoStory.chapters[state.scene]
    val scroll = rememberScrollState()
    val actorProgress = remember { Animatable(0f) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(state.scene, state.page) { scroll.scrollTo(0) }
    LaunchedEffect(state.scene, state.page, state.reducedMotion, lifecycle) {
        if(state.reducedMotion) actorProgress.snapTo(1f)
        else if(lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            actorProgress.snapTo(0f); actorProgress.animateTo(1f, tween(2000))
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${chapter.id + 1}. ${chapter.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Gem og luk kapitlet") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MisoArtwork(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp).aspectRatio(1.8f), chapter.pose, actorProgress.value, relaxed = state.completed(5))
                    Text("Miso", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(chapter.text(state.page, state.choice), fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 32.sp)
                    if(state.page == chapter.opening.size) chapter.choices.forEachIndexed { index, choice ->
                        OutlinedButton(onClick = { next(state.page + 1, index) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(choice.caption) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text("${state.page + 1} / ${chapter.lastPage + 1} · Du bestemmer tempoet", style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { next(state.page - 1, state.choice) }, enabled = !busy && state.page > 0) { Text("Tilbage") }
                    if(state.page == chapter.lastPage) Button(onClick = finish, enabled = !busy) { Text("Afslut kapitlet") }
                    else if(state.page != chapter.opening.size) Button(onClick = { next(state.page + 1, state.choice) }, enabled = !busy) { Text("Videre") }
                    TextButton(onClick = close, enabled = !busy) { Text("Pause og gem") }
                }
            }
        }
    }
}

@Composable private fun CafeAnimation(action: CafeAction, reduced: Boolean, close: () -> Unit) {
    val progress = remember(action) { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(action, paused, replay, reduced, lifecycle) {
        if(reduced) progress.snapTo(1f)
        else if(!paused && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) progress.animateTo(1f, tween((2600 * (1f - progress.value)).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = close) {
        Card {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(action.title, style = MaterialTheme.typography.titleLarge)
                MisoArtwork(Modifier.fillMaxWidth().aspectRatio(1.1f), when(action) { CafeAction.TREAT -> "treat"; CafeAction.PAW -> "paw"; CafeAction.BOOKMARK -> "bookmark" }, progress.value)
                Text(action.caption)
                if(reduced) Text("Reduceret bevægelse · stille slutpose", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(progress.value < 1f && !reduced) OutlinedButton(onClick = { paused = !paused }) { Text(if(paused) "Fortsæt" else "Pause") }
                    if(progress.value >= 1f) OutlinedButton(onClick = { scope.launch { progress.snapTo(0f); paused = false; replay++ } }) { Text("Se igen · gratis") }
                    Button(onClick = close) { Text(if(progress.value < 1f) "Spring over" else "Tilbage til Miso") }
                }
                Text("Samvær gemmes ved købet. At se animationen igen koster intet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
