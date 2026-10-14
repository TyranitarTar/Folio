package app.folio.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.readium.r2.shared.publication.Locator

@Composable
internal fun BookStatisticsPanel(model: ReaderModel,counter: PageCounter?,anchor: Locator?,jump:(Locator)->Unit,pauseSpeech:()->Unit) {
    val statistics by model.statistics.collectAsStateWithLifecycle()
    val before by model.informationWord.collectAsStateWithLifecycle()
    val error by model.statisticsError.collectAsStateWithLifecycle()
    val pages by (counter?.information ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PageInformation()) }).collectAsStateWithLifecycle()
    val speech by SpeechController.state.collectAsStateWithLifecycle()
    val wpm=Pace.load(androidx.compose.ui.platform.LocalContext.current).let { if(it.unit=="WPM")it.speed else 200.0 }
    LaunchedEffect(statistics,counter) { statistics?.let { counter?.requestBoundaries(it.chapters.map { row->row.locator }) } }
    val current=statistics?.let { info -> before?.let { info.currentChapter(it,anchor?.href?.removeFragment()?.toString().orEmpty()) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(horizontal=24.dp),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Reading information",style=MaterialTheme.typography.headlineSmall)
            Text(pages.label)
            anchor?.locations?.totalProgression?.takeIf { it.isFinite() }?.let { Text("${(it.coerceIn(0.0,1.0)*100).toInt()}% overall") }
            current?.let { Text("Current chapter: ${it.title}");Text("${it.words} readable words in this chapter") }
        }
        item {
            val info=statistics
            if(info==null) {
                val message=error
                if(message==null){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Preparing word information…")}else Text(message)
            } else {
                Text("${info.totalWords} readable words",style=MaterialTheme.typography.titleMedium)
                // Lazy item callbacks can outlive the parent composition; read nullable state once.
                val position=before?.coerceIn(0,info.totalWords)
                if(position==null)Text("Locating the current reading page…") else {
                    Text("$position words before · ${info.totalWords-position} remaining")
                    Text("About ${info.remainingMinutes(position,wpm)} min remaining at ${wpm.toInt()} WPM")
                }
                Text("Remaining uses the first visible word on the reading page when this sheet opened. Guided and listening positions are independent.",style=MaterialTheme.typography.bodySmall)
                Text("Counts use the same readable tokens as guided reading and speech, including headings, retained notes and front matter. Time excludes illustrations and additional pauses. CPS mode uses a 200 WPM estimate.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(speech.bookId==model.bookId&&speech.playing)item { OutlinedButton(onClick=pauseSpeech) { Text("Pause narration") } }
        statistics?.let { info ->
            item { Text("Chapter distribution",style=MaterialTheme.typography.titleMedium);Text("Top-level rows partition the book's words. Indented contents entries are included within their parent. Page ranges can share boundary pages; they are not additive.",style=MaterialTheme.typography.bodySmall) }
            items(info.chapters) { row ->
                val color=MaterialTheme.colorScheme.primary
                Column(Modifier.fillMaxWidth().padding(start=(minOf(row.depth,4)*12).dp)) {
                    Text(row.title,style=MaterialTheme.typography.titleSmall)
                    val share=if(info.totalWords>0)row.words.toFloat()/info.totalWords else 0f
                    Text("${row.words} words · ${"%.1f".format(share*100)}%${if(row.depth>0) " · within parent" else ""}")
                    Canvas(Modifier.fillMaxWidth().height(8.dp)) { drawRect(color.copy(alpha=.12f));drawRect(color,size=Size(size.width*share,size.height)) }
                    val start=pages.page(row.locator)
                    val next=info.chapters.firstOrNull { it.depth<=row.depth&&it.start==row.end&&it!==row }
                    val end=if(row.words==0)pages.lastPageInResource(row.locator) else next?.let { pages.page(it.locator) } ?: if(row.end==info.totalWords)pages.total else null
                    Text(if(start!=null&&end!=null) "${if(pages.scrolling) "Screens" else "Pages"} $start–$end" else "Page range: calculating / unavailable",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={jump(row.locator)}) { Text("Go to chapter") }
                }
            }
        }
    }
}
