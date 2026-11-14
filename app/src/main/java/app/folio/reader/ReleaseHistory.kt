package app.folio.reader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class VersionNote(val version:String,val summary:String,val changes:List<String>,val date:String="")
internal object ReleaseCatalog {
    @Volatile private var cached:List<VersionNote>?=null
    fun history(context:Context):List<VersionNote> = cached ?: synchronized(this) {
        cached ?: context.assets.open("release-notes.json").bufferedReader().use{it.readText()}.let(::decode).also{cached=it}
    }
    fun decode(text:String):List<VersionNote> {
        BoundedJson.check(text,8,10000)
        val json=JSONObject(text);require(json.getInt("version")==1)
        val rows=json.getJSONArray("releases");require(rows.length() in 1..200)
        return (0 until rows.length()).map {i->val row=rows.getJSONObject(i);val changes=row.getJSONArray("changes")
            val version=row.getString("version");UpdatePolicy.version(version)
            require(changes.length() in 1..50)
            VersionNote(version,row.getString("summary"),(0 until changes.length()).map{changes.getString(it)},row.optString("date"))
        }.also{require(it.map{n->n.version}.distinct().size==it.size)}
    }
    fun body(note:VersionNote)=note.changes.joinToString("\n\n"){"- $it"}
}

/** Restrained Markdown headings and bullets, without interpreting HTML. */
@Composable internal fun ReleaseBody(text:String) {
    text.replace("\r","").split("\n").filter{it.isNotBlank()}.forEach { line->
        val trimmed=line.trim()
        val heading=trimmed.startsWith("#")
        val clean=trimmed.removePrefix("### ").removePrefix("## ").removePrefix("# ")
            .replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"),"$1").replace("**","").replace("`","")
        Text(if(clean.startsWith("- ") || clean.startsWith("* "))"• "+clean.drop(2) else clean,
            style=if(heading)MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VersionHistorySheet(close:()->Unit) {
    val context=LocalContext.current
    val history=remember{ReleaseCatalog.history(context)}
    ModalBottomSheet(onDismissRequest=close,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=640.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item{Text("Version history",style=MaterialTheme.typography.headlineSmall)}
            items(history,key={it.version}){note->
                var expanded by rememberSaveable(note.version){mutableStateOf(false)}
                Column {
                    Text("Folio ${note.version}"+if(note.date.isNotEmpty())" · ${note.date}" else "",style=MaterialTheme.typography.titleMedium)
                    Text(note.summary,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=6.dp))
                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide notes" else "Full release notes")}
                    if(expanded)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){ReleaseBody(ReleaseCatalog.body(note))}
                    HorizontalDivider(Modifier.padding(top=8.dp,bottom=8.dp))
                }
            }
        }
    }
}
