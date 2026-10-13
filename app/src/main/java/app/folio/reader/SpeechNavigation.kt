package app.folio.reader

/** Navigation uses source words/paragraph identity; rendered-line targets are resolved by the reader. */
internal object SpeechNavigation {
    data class Range(val first: Int, val end: Int)
    fun chapters(book: BookText): List<ReadingCursor> = book.chapters.ifEmpty {
        book.sections.indices.mapNotNull { book.cursor(it,0) }
    }
    fun compare(a: ReadingCursor,b: ReadingCursor): Int = if(a.sectionIndex!=b.sectionIndex) a.sectionIndex.compareTo(b.sectionIndex) else a.wordIndex.compareTo(b.wordIndex)
    fun chapterIndex(book: BookText,point: ReadingCursor): Int {
        val starts=chapters(book); var low=0; var high=starts.lastIndex; var found=0
        while(low<=high) { val middle=(low+high) ushr 1
            if(compare(starts[middle],point)<=0) { found=middle; low=middle+1 } else high=middle-1
        }
        return found
    }
    fun chapterStart(book: BookText,point: ReadingCursor) = chapters(book).getOrNull(chapterIndex(book,point))
    fun nextChapter(book: BookText,point: ReadingCursor) = chapters(book).getOrNull(chapterIndex(book,point)+1)
    fun range(book: BookText, point: ReadingCursor, unit: String): Range? = when (unit) {
        "paragraph" -> book.paragraph(point)?.let { Range(it.firstWord,it.endWord) }
        "chapter" -> book.sections.getOrNull(point.sectionIndex)?.let { section ->
            val start=chapterStart(book,point); val next=nextChapter(book,point)
            Range(if(start?.sectionIndex==point.sectionIndex) start.wordIndex else 0,
                if(next?.sectionIndex==point.sectionIndex) next.wordIndex else section.words.size)
        }
        else -> book.sentence(point)?.let { Range(it.firstWord,it.endWord) }
    }
    fun target(book: BookText, point: ReadingCursor, direction: Int, unit: String, replayCurrent: Boolean): ReadingCursor? {
        if(unit=="chapter") {
            val index=chapterIndex(book,point)
            return chapters(book).getOrNull(if(direction==0 || (direction<0 && replayCurrent)) index else index+if(direction>0) 1 else -1)
        }
        val bounds = range(book,point,unit) ?: return null
        if (direction == 0 || (direction < 0 && replayCurrent)) return book.cursor(point.sectionIndex,bounds.first)
        if (direction>0) return book.cursor(point.sectionIndex,bounds.end) ?: book.cursor(point.sectionIndex,bounds.end-1)?.let { book.next(it) }
        val previous = book.cursor(point.sectionIndex,bounds.first)?.let { book.previous(it) } ?: return null
        return range(book,previous,unit)?.let { book.cursor(previous.sectionIndex,it.first) }
    }
    fun pauseAfter(book: BookText, last: ReadingCursor, next: ReadingCursor, settings: SpeechSettings): Long {
        val sentenceEnd = book.sentence(last)?.endWord
        val paragraphEnd = book.paragraph(last)?.endWord
        val moved = next.sectionIndex != last.sectionIndex
        val sentence = if (moved || sentenceEnd == last.wordIndex+1) settings.sentencePauseMillis else 0
        val paragraph = if (moved || paragraphEnd == last.wordIndex+1) settings.paragraphPauseMillis else 0
        return maxOf(sentence,paragraph).coerceIn(0,5000).toLong()
    }
}
