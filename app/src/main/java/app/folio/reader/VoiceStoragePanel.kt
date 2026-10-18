package app.folio.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import java.util.Locale

internal fun voiceStorageSize(bytes:Long)="%.1f MB".format(Locale.ROOT,bytes/1_000_000.0)
@Composable internal fun VoiceStorageCard(inventory:VoiceStorageInventory?,busy:Boolean,message:String?,refresh:()->Unit,clean:(VoiceCleanup)->Unit,cancel:()->Unit) {
    var details by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Voice storage",style=MaterialTheme.typography.titleMedium)
        if(inventory==null){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Measuring voice files…",style=MaterialTheme.typography.bodySmall)}else {
            Text("Models ${voiceStorageSize(inventory.modelBytes)} · Reclaimable ${voiceStorageSize(inventory.reclaimableBytes)}")
            Text("Downloads ${voiceStorageSize(inventory.transferBytes)} · Previews and audio ${voiceStorageSize(inventory.entries.filter { it.category=="Cache" }.sumOf { it.bytes })}",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={clean(VoiceCleanup.OLD_VERSIONS)},enabled=!busy&&inventory.reclaimable(VoiceCleanup.OLD_VERSIONS).isNotEmpty()){Text("Remove old versions")}
                TextButton(onClick={clean(VoiceCleanup.DOWNLOADS)},enabled=!busy&&inventory.reclaimable(VoiceCleanup.DOWNLOADS).isNotEmpty()){Text("Discard downloads")}
            }
        }
        if(busy){LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=cancel){Text("Cancel cleanup")}}
        message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        Row {
            TextButton(onClick=refresh,enabled=!busy){Text("Refresh storage")}
            TextButton(onClick={details=!details}){Text(if(details)"Hide storage details" else "Storage details")}
        }
        if(details) {
            Text("Current packs, loaded engines, rollback/shared files and unknown folders are kept. Samples and generated audio are separate; clear generated audio in Storage and backup. Books, bookmarks and listening positions are never part of voice cleanup. Sizes exclude unreadable or linked paths.",style=MaterialTheme.typography.bodySmall)
            inventory?.entries?.take(24)?.forEach { row->Text("${row.title} · ${row.category} · ${voiceStorageSize(row.bytes)}\n${row.reason}",style=MaterialTheme.typography.bodySmall) }
            if((inventory?.entries?.size ?: 0)>24)Text("Additional entries are included in the totals.",style=MaterialTheme.typography.bodySmall)
        }
    } }
}
