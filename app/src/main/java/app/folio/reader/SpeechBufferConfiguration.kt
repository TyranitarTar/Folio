package app.folio.reader

import org.readium.r2.shared.publication.Locator
import org.json.JSONObject

/** Labels and effective limits shared by Read aloud and its diagnostic trial. */
internal object SpeechBufferConfiguration {
    val words=listOf("Default","10","20","30","35")
    val lookahead=listOf("1","2","4","8","16","32")
    val startup=listOf("0","6","12","24","30")
    val speeds=listOf("1.0","1.5","2.0","2.5","3.0")

    /** Authored Danish material through the SAME sentence/character boundary builder as narration. */
    fun prompts(settings:SpeechSettings):List<String> {
        val sentences=listOf("Hun åbner forsigtigt sin bog og finder sin gamle plads.",
            "Udenfor skinner solen gennem vinduet mens katten sover ved døren.",
            "På bordet står en kop te som stadig er varm.","Historien fortsætter i morgen igen.",
            "En ven banker stille på døren og kommer ind med friske boller.",
            "Sammen læser de videre mens regnen begynder at falde udenfor.")
        val base=Locator.fromJSON(JSONObject().put("href","benchmark.xhtml").put("type","application/xhtml+xml"))!!
        val words=mutableListOf<ReadingWord>();val ranges=mutableListOf<ReadingSentence>()
        repeat(8) {for(text in sentences) {
            val start=words.size
            words+=text.split(' ').map{ReadingWord(it,base,it.length+1)}
            ranges+=ReadingSentence(text,base,start,words.size)
        }}
        val book=BookText(listOf(TextSection(base.href,"Benchmark",words,ranges)))
        val prompts=mutableListOf<String>();var at=book.first()
        while(at!=null) {
            val selected=SpeechPassages.words(book,at,settings)
            check(selected.isNotEmpty())
            prompts+=selected.joinToString(" "){it.text}
            at=book.cursor(at.sectionIndex,at.wordIndex+selected.size)
        }
        return prompts
    }
}
