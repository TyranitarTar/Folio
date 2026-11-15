@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.folio.reader

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject

@Composable internal fun CafeScreen() {
    var legacy by rememberSaveable { mutableStateOf(false) }
    if(legacy) {
        BackHandler { legacy = false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { legacy = false }) {
                Icon(Icons.Outlined.ArrowBack, null); Text("Tilbage til Misos værelse")
            }
            Box(Modifier.weight(1f)) { CafeLegacyScreen() }
        }
    } else CafeRoomScreen { legacy = true }
}

@Composable private fun CafeRoomScreen(legacy: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { CafeRoomPreferences(context) }
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(prefs.mode) }
    var pace by remember { mutableStateOf(prefs.pace) }
    var ambient by remember { mutableStateOf(prefs.ambient) }
    var reduced by remember { mutableStateOf(prefs.reduced) }
    var close by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var actions by rememberSaveable { mutableStateOf(false) }
    var prop by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(JSONObject()) }
    var discovered by remember { mutableIntStateOf(prefs.counts().length()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<CafeRoomView?>(null) }
    val ready = status.optBoolean("ready") && !failed
    val paused = status.optBoolean("paused")
    val systemReduced = remember(lifecycle) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    fun config() = JSONObject().put("mode", mode).put("pace", pace).put("ambient", ambient)
        .put("reduced", reduced || systemReduced).put("close", close)
    fun command(id: String) {
        if(id != "pause") { mode = "manual"; prefs.mode = mode }
        web?.command(id)
    }
    LaunchedEffect(web, mode, pace, ambient, reduced, systemReduced, close) { web?.configure(config()) }
    LaunchedEffect(web, lifecycle, actions, tools, failed) {
        web?.setHostActive(lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !actions && !tools && !failed)
    }
    DisposableEffect(web) {
        val current = web
        onDispose { current?.disposeRoom() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Miso · Kattecafé", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { tools = true }) { Icon(Icons.Outlined.Tune, "Værelsesindstillinger") }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val roomHeight = maxHeight * .60f
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(roomHeight)) {
                    key(attempt) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            CafeRoomView(ctx).also { view ->
                                view.onStatus = { value ->
                                    status = value
                                    val actual = value.optString("mode")
                                    if(actual in cafeRoomModes && actual != mode) { mode = actual; prefs.mode = actual }
                                }
                                view.onDiscoveries = { value -> prefs.saveCounts(value); discovered = prefs.counts().length() }
                                view.onPick = { value -> prop = value; actions = true }
                                view.onFailure = { failed = true }
                                view.onPageReady = { view.configure(config().put("counts", prefs.counts())) }
                                web = view; view.openRoom()
                            }
                        })
                    }
                    if(failed) Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Misos værelse kunne ikke åbnes.")
                            Button(onClick = {
                                web?.disposeRoom(); web = null; status = JSONObject(); failed = false; attempt++
                            }) { Text("Prøv igen") }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val action = status.optString("pending").takeIf { it in cafeRoomActions }
                        ?: status.optString("action").takeIf { it in cafeRoomActions }
                    Text(when {
                        failed -> "Prøv at åbne værelset igen."
                        !ready -> "Åbner Misos værelse…"
                        paused -> "Pause · Miso venter på dig"
                        action != null -> cafeRoomActions.getValue(action)
                        status.optString("resting") == "sleep" -> "Miso tager en lur"
                        status.optBoolean("busy") -> "Miso er på vej"
                        else -> "Et lille hvil mellem siderne"
                    }, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { prop = null; actions = true }, enabled = ready, modifier = Modifier.weight(1f)) { Text("Handlinger") }
                        OutlinedButton(onClick = { command("pause") }, enabled = ready) { Text(if(paused) "Fortsæt" else "Pause") }
                    }
                    CafeRoomChoice("Adfærd", mode, cafeRoomModes, ready) { mode = it; prefs.mode = it }
                    Text("Tryk på gulvet for at gå, eller på møbler for handlinger.\n$discovered / 21 handlinger oplevet · alle kan prøves frit.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if(actions) ModalBottomSheet(onDismissRequest = { actions = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val byProp = mapOf(
            "bowls" to setOf("eat", "drink", "eat_far", "drink_far"),
            "cat_tower" to setOf("scratch", "step", "perch", "perch_sleep", "perch_groom", "perch_watch"),
            "cubby_books" to setOf("bookshelf", "bookshelf_sleep"), "sofa" to setOf("sofa"),
            "box" to setOf("box"), "tunnel" to setOf("tunnel"), "ball_track" to setOf("ball"),
            "feather" to setOf("feather"), "window" to setOf("window"), "door" to setOf("door")
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lidt tid med Miso", style = MaterialTheme.typography.headlineSmall)
            Text("Vælg en handling. Miso gør den aktuelle bevægelse færdig og finder en sikker vej videre.", style = MaterialTheme.typography.bodySmall)
            if(prop != null) TextButton(onClick = { prop = null }) { Text("Vis alle handlinger") }
            val choices = cafeRoomActions.filterKeys { prop == null || it in byProp[prop].orEmpty() }
            if(choices.isEmpty()) Text("Her er lidt hyggelig pynt. Vælg alle handlinger for at lege med Miso.")
            choices.forEach { (id, title) ->
                OutlinedButton(onClick = { command(id); actions = false }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text(title) }
            }
            OutlinedButton(onClick = { command("down"); actions = false }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text("Ned på gulvet") }
            OutlinedButton(onClick = { command("stop"); actions = false }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text("Stop efter denne bevægelse") }
            TextButton(onClick = { actions = false }) { Text("Luk handlinger") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if(tools) ModalBottomSheet(onDismissRequest = { tools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Misos værelse", style = MaterialTheme.typography.headlineSmall)
            CafeRoomChoice("Tempo", pace, cafeRoomPaces, ready) { pace = it; prefs.pace = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Små bevægelser i værelset", Modifier.weight(1f)); Switch(ambient, { ambient = it; prefs.ambient = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reduceret bevægelse", Modifier.weight(1f)); Switch(reduced || systemReduced, { reduced = it; prefs.reduced = it }, enabled = !systemReduced)
            }
            Text("Reduceret bevægelse slukker pyntens animationer og dæmper tomgang. Valgte handlinger og ruter kan stadig afprøves. Pause standser al bevægelse.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Se Miso tættere på", Modifier.weight(1f)); Switch(close, { close = it })
            }
            OutlinedButton(onClick = { command("home"); tools = false }, enabled = ready) { Text("Tilbage til tæppet") }
            TextButton(onClick = { tools = false; legacy() }) { Text("Tidligere café og gemte data") }
            Text("Her finder du dine tidligere godbidder, kapitler og cafébackup. Nye historiehændelser kommer senere.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { tools = false }) { Text("Luk indstillinger") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable private fun CafeRoomChoice(label: String, value: String, choices: Map<String, String>, enabled: Boolean, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${choices[value]}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; select(id) }) }
        }
    }
}
