package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

private data class VoiceCleanupRequest(val kind:VoiceCleanup,val entries:List<VoiceStorageEntry>)

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun VoicePacksPanel(initialBackend: String?=null,close: ()->Unit) {
    val context=LocalContext.current
    val app=context.applicationContext as FolioApp
    val store=remember { app.voicePacks }
    val samples=remember { app.voiceSamples }
    val states by store.states.collectAsStateWithLifecycle()
    val sampleState by samples.state.collectAsStateWithLifecycle()
    val speech by SpeechController.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current
    var download by remember { mutableStateOf<VoicePack?>(null) }
    var remove by remember { mutableStateOf<VoicePack?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inventory by remember { mutableStateOf<VoiceStorageInventory?>(null) }
    var storageMessage by remember { mutableStateOf<String?>(null) }
    var storageRefresh by remember { mutableIntStateOf(0) }
    var cleanup by remember { mutableStateOf<VoiceCleanupRequest?>(null) }
    var cleanupJob by remember { mutableStateOf<Job?>(null) }
    val phases=states.mapValues { it.value.phase }
    LaunchedEffect(phases,storageRefresh) {
        try { inventory=store.storage.inventory() } catch(cancelled:CancellationException){throw cancelled}
        catch(e:Exception){storageMessage=e.message ?: "Voice storage could not be measured"}
    }
    DisposableEffect(samples,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) { samples.stop();SpeechController.cancelPreview(context) } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);samples.stop();SpeechController.cancelPreview(context) }
    }
    var storageOpen by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=720.dp),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("Voice packs",style=MaterialTheme.typography.headlineSmall)
                Text("Preview and manage offline voices.",style=MaterialTheme.typography.bodySmall)
                error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp)) }
            }
            item { TextButton(onClick={storageOpen=!storageOpen}) { Text("Voice storage · ${inventory?.let { voiceStorageSize(it.modelBytes) } ?: "checking…"}") } }
            if(storageOpen) item { VoiceStorageCard(inventory,cleanupJob!=null,storageMessage,
                refresh={storageRefresh++},clean={kind->cleanup=VoiceCleanupRequest(kind,inventory?.reclaimable(kind).orEmpty())},cancel={cleanupJob?.cancel();storageMessage="Cleanup cancelled. Refreshing remaining files."}) }
            items(store.catalog.sortedBy { if(it.backend==initialBackend)0 else 1 },key={it.id}) { pack ->
                val state=states.getValue(pack.id)
                val compatible=pack.backend!="plapre3" || android.os.Process.is64Bit()
                var expanded by rememberSaveable(pack.id) { mutableStateOf(false) }
                var language by rememberSaveable(pack.id) { mutableStateOf(pack.languages.first()) }
                var voiceId by rememberSaveable(pack.id) { mutableStateOf(pack.voices.first { it.language==language }.id) }
                var menu by remember { mutableStateOf(false) }
                val available=pack.voices.filter { it.language==language }
                val voice=available.firstOrNull { it.id==voiceId } ?: available.first()
                val key="${pack.id}:${language}:${voice.id}"
                val baseSettings=SpeechSettings.load(context)
                val requestedSettings=baseSettings.copy(backend=pack.backend,
                    aiVoice=if(pack.backend=="kokoro")voice.id.toInt() else baseSettings.aiVoice,
                    picoVoice=if(pack.backend=="pico")voice.id.toInt() else baseSettings.picoVoice,
                    plapre3Voice=if(pack.backend=="plapre3")voice.id.toInt() else baseSettings.plapre3Voice,
                    supertonicVoice=if(pack.backend=="supertonic")voice.id.toInt() else baseSettings.supertonicVoice,supertonicLanguage=language)
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(pack.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick={expanded=!expanded}) { Text(if(expanded)"Less" else "Details") }
                    }
                    Text(when(pack.backend) {
                        "pico" -> "Danish neural voice · Z Flip7-class; S10 may buffer"
                        "plapre3" -> "Full-size Danish · very high demand; desktop recommended"
                        "kokoro" -> "English neural voice · low device demand"
                        else -> "Multilingual neural voice · moderate device demand"
                    },style=MaterialTheme.typography.bodySmall)
                    Text("${pack.bytes/1_000_000} MB download · ${pack.expandedBytes/1_000_000} MB installed · ${state.phase}",style=MaterialTheme.typography.labelSmall)
                    if(state.message.isNotBlank() && (expanded || state.phase=="Failed"))Text(state.message,style=MaterialTheme.typography.bodySmall)
                    if(state.busy) {
                        if(state.phase=="Downloading")LinearProgressIndicator(progress={(state.bytes.toFloat()/pack.bytes).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if(expanded) {
                        Text("Compare samples for voice quality. Device guidance does not guarantee real-time playback.",style=MaterialTheme.typography.bodySmall)
                        if(pack.backend=="plapre3")Text(if(compatible)"Sustained mobile narration is unverified, including Galaxy S26-class devices." else "Needs 64-bit Android; samples remain available.",style=MaterialTheme.typography.bodySmall)
                        if(pack.languages.size>1)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            pack.languages.forEach { code -> FilterChip(language==code,{samples.stop();SpeechController.cancelPreview(context);language=code},label={Text(if(code=="da")"Dansk" else "English")}) }
                        }
                        Box {
                            OutlinedButton(onClick={menu=true}) { Text(voice.label) }
                            DropdownMenu(menu,{menu=false}) { available.forEach { entry -> DropdownMenuItem(text={Text(entry.label)},onClick={samples.stop();SpeechController.cancelPreview(context);voiceId=entry.id;menu=false}) } }
                        }
                    }
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick={if(sampleState.key==key && sampleState.phase!="Stopped")samples.stop() else samples.play(pack,voice)},enabled=voice.sample!=null) {
                            Icon(if(sampleState.key==key && sampleState.phase!="Stopped")Icons.Outlined.Stop else Icons.Outlined.PlayArrow,null)
                            Text(if(sampleState.key==key && sampleState.phase!="Stopped")"Stop sample" else "Preview sample")
                        }
                        when {
                            state.phase=="Ready" -> {
                                Button(onClick={samples.stop();SpeechController.settings(context,requestedSettings)}) { Text("Use voice") }
                                IconButton(onClick={remove=pack}) { Icon(Icons.Outlined.Delete,"Remove ${pack.label}") }
                            }
                            state.phase in setOf("Queued","Downloading","Verifying","Installing") -> TextButton(onClick={scope.launch(Dispatchers.IO){store.cancelDownload(pack.id)}}) { Text("Cancel download") }
                            state.busy -> Unit
                            else -> Button(onClick={download=pack},enabled=compatible) { Icon(Icons.Outlined.Download,null);Text(if(state.phase=="Cancelled")"Resume download" else if(state.phase=="Failed")"Retry download" else "Download pack") }
                        }
                    }
                    if(sampleState.key==key && sampleState.phase=="Loading")LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(sampleState.key==key)sampleState.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    if(expanded && state.phase=="Ready") {
                        TextButton(onClick={samples.stop();if(speech.previewing)SpeechController.cancelPreview(context) else SpeechController.preview(context,requestedSettings)}) { Text(if(speech.previewing)"Stop local preview" else "Local preview · ${requestedSettings.rate}×") }
                        if(speech.previewing)Text(if(speech.preparing)speech.preparation else "Playing local preview…",style=MaterialTheme.typography.bodySmall)
                    }
                    if(expanded)Text(pack.license,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }

            }
            item { Text("Android speech uses voices installed through Android settings. Other compatible voice packs can be added in future app updates.",style=MaterialTheme.typography.bodySmall) }
        }
    }
    cleanup?.let { request ->
        val kind=request.kind
        val selected=request.entries
        AlertDialog(onDismissRequest={cleanup=null},title={Text(if(kind==VoiceCleanup.OLD_VERSIONS)"Remove old voice versions?" else "Discard partial voice downloads?")},
            text={Text("Up to ${voiceStorageSize(selected.sumOf { it.bytes })} can be recovered. "+if(kind==VoiceCleanup.DOWNLOADS)"Selected transfers will be cancelled and drained; resuming them later requires another download. Current packs, rollback data and unknown files are kept." else "Only verified, owned superseded versions are removed. Current packs, loaded engines, rollback/shared files and unknown folders are kept.")},
            confirmButton={TextButton(onClick={
                cleanup=null;val keys=selected.map { it.key }.toSet()
                cleanupJob=scope.launch {
                    try { val result=store.storage.clean(kind,keys);storageMessage="Recovered ${voiceStorageSize(result.bytes)}. ${result.skipped} changed or protected entries kept." }
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(e:Exception){storageMessage=e.message ?: "Voice cleanup could not finish. Refresh and retry."}
                    finally { cleanupJob=null;storageRefresh++ }
                }
            }){Text("Clean up")}},dismissButton={TextButton(onClick={cleanup=null}){Text("Cancel")}})
    }
    download?.let { pack -> AlertDialog(onDismissRequest={download=null},title={Text("Download ${pack.label}?")},
        text={Text("${pack.bytes/1_000_000} MB download. Allow ${(pack.bytes+pack.expandedBytes+32L*1024*1024)/1_000_000} MB free during installation. It works offline once installed. Downloading does not start narration.")},
        confirmButton={TextButton(onClick={download=null;scope.launch {
            try {
                if(SpeechController.state.value.backend==pack.backend && SpeechController.serviceRunning) {
                    SpeechController.stop(context);withTimeout(15000){while(SpeechController.serviceRunning)delay(50)}
                }
                withContext(Dispatchers.IO){store.enqueue(pack.id)}
            } catch(e: CancellationException){throw e} catch(e: Exception){error=e.message ?: "The pack could not be queued"}
        }}) { Text("Download") }},dismissButton={TextButton(onClick={download=null}){Text("Cancel")}}) }
    remove?.let { pack -> AlertDialog(onDismissRequest={remove=null},title={Text("Remove ${pack.label}?")},
        text={Text("Stops this voice and removes its downloaded files. Your books and saved listening positions are kept. You can download it again later.")},
        confirmButton={TextButton(onClick={remove=null;scope.launch {
            try {
                if(SpeechController.state.value.backend==pack.backend && SpeechController.serviceRunning) {
                    SpeechController.stop(context);withTimeout(15000){while(SpeechController.serviceRunning)delay(50)}
                }
                store.remove(pack.id)
            }catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: "The pack could not be removed"}
        }}){Text("Remove")}},dismissButton={TextButton(onClick={remove=null}){Text("Cancel")}}) }
}
