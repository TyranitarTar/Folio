package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AppUpdatesSheet(close: () -> Unit, model: AppUpdateModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val latest by model.latestRelease.collectAsStateWithLifecycle()
    val checkedAt by model.lastChecked.collectAsStateWithLifecycle()
    // Material may read this callback during semantics/layout after the model advances.
    // Capture this composition's non-null value rather than reading a later nullable state.
    val progress = state.progress
    val release=state.release
    val context = LocalContext.current
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val bundled=remember(installed){ReleaseCatalog.history(context).firstOrNull{it.version==installed}}
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal=24.dp).padding(bottom=24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("App updates", style = MaterialTheme.typography.headlineSmall)
            Text("Installed · Folio $installed")
            Button(onClick={when{state.ready->model.install();state.release!=null->model.download();else->model.check()}},
                enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
                Text(when{state.busy && state.downloading->"Downloading…";state.busy->if(state.release!=null)"Verifying…" else "Checking…";
                    state.ready->"Install update";release!=null->"Download ${release.version} · ${release.bytes/1_000_000} MB";else->"Check for updates"})
            }
            Text(state.status, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (state.busy) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}% downloaded")
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = model::cancel) { Text("Cancel update") }
            }
            if(!state.busy && state.release!=null)TextButton(onClick=model::check) { Text("Check again") }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                val remote=latest?.takeUnless{UpdatePolicy.newer(installed,it.version)}
                val shownVersion=remote?.version ?: bundled?.version ?: installed
                Text(if(remote!=null)"Latest published · Folio $shownVersion" else "Installed release · Folio $shownVersion",style=MaterialTheme.typography.titleMedium)
                val date=(remote?.publishedAt?.substringBefore('T') ?: bundled?.date).orEmpty()
                if(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))Text(date,style=MaterialTheme.typography.labelMedium)
                if(remote!=null && checkedAt>0)Text("Last checked · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(checkedAt)),style=MaterialTheme.typography.labelSmall)
                val body=remote?.notes ?: bundled?.let(ReleaseCatalog::body).orEmpty()
                if(body.isBlank())Text("No release notes were provided.") else ReleaseBody(body)
                Text("APK downloads are verified before Android asks to install.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
