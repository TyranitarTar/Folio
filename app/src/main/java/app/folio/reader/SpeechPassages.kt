package app.folio.reader

import android.speech.tts.TextToSpeech

/** Identical source boundaries for live lookahead and disk prepare-ahead. */
internal object SpeechPassages {
    fun words(book: BookText, position: ReadingCursor, settings: SpeechSettings, bounded: Boolean = true): List<ReadingWord> {
        val sentence = book.sentence(position) ?: return emptyList()
        val chapterEnd = SpeechNavigation.nextChapter(book,position)?.takeIf { it.sectionIndex==position.sectionIndex }?.wordIndex
        val endWord = minOf(sentence.endWord,chapterEnd ?: sentence.endWord)
        val remaining = book.sections[position.sectionIndex].words.subList(position.wordIndex,endWord)
        val limit = if(bounded && settings.backend=="pico") 160 else if(bounded) 320 else TextToSpeech.getMaxSpeechInputLength()
        var characters=0;var end=0
        for(word in remaining) {
            if(end>0 && characters+word.text.length+1>limit) break
            characters+=word.text.length+1;end++
        }
        return remaining.take(end)
    }
}
