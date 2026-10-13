@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package app.folio.reader

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.decodeString
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.use
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Keep offsets into shared element text, not a full quote/locator per word. */
class ReadingWord(val text: String, private val base: Locator, val characterCount: Int,
    private val sourceText: String? = null, private val start: Int = 0, private val end: Int = 0, val elementId: Int = 0) {
    val locator: Locator get() = sourceText?.let { BookText.quoteAt(base, it, start, end) } ?: base
}
data class ReadingParagraph(val firstWord: Int, val endWord: Int)

data class ReadingSentence(val text: String, val locator: Locator, val firstWord: Int, val endWord: Int)
data class TextSection(val href: Url, val title: String, val words: List<ReadingWord>, val sentences: List<ReadingSentence>)
data class ReadingCursor(val sectionIndex: Int, val wordIndex: Int, val locator: Locator)

/** Text quotes, rather than screen coordinates, survive changes to the reading layout. */
data class BookText(val sections: List<TextSection>, val chapters: List<ReadingCursor> = emptyList()) {
    private val paragraphRanges = sections.map { section ->
        val ranges = mutableListOf<ReadingParagraph>(); var first = 0
        while (first < section.words.size) {
            var end = first + 1
            while (end < section.words.size && section.words[end].elementId == section.words[first].elementId) end++
            ranges += ReadingParagraph(first, end); first = end
        }
        ranges
    }
    fun cursor(section: Int, word: Int): ReadingCursor? {
        val s = sections.getOrNull(section) ?: return null
        val w = s.words.getOrNull(word) ?: return null
        return ReadingCursor(section, word, w.locator)
    }
    fun first(): ReadingCursor? = sections.indices.firstNotNullOfOrNull { cursor(it, 0) }
    fun next(cursor: ReadingCursor): ReadingCursor? = cursor(cursor.sectionIndex, cursor.wordIndex + 1)
        ?: (cursor.sectionIndex + 1 until sections.size).firstNotNullOfOrNull { cursor(it, 0) }
    fun previous(cursor: ReadingCursor): ReadingCursor? = cursor(cursor.sectionIndex, cursor.wordIndex - 1)
        ?: (cursor.sectionIndex - 1 downTo 0).firstNotNullOfOrNull { cursor(it, sections[it].words.lastIndex) }
    fun locate(locator: Locator?): ReadingCursor? {
        if (locator == null) return first()
        val section = sections.indexOfFirst { it.href.removeFragment() == locator.href.removeFragment() }
        if (section < 0) return first()
        val words = sections[section].words
        if (words.isEmpty()) return (section + 1 until sections.size).firstNotNullOfOrNull { cursor(it, 0) }
        val quote = locator.text.highlight?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        fun normalized(value: String?) = value.orEmpty().replace(Regex("\\s+"), " ")
        val before = normalized(locator.text.before).takeLast(60)
        val after = normalized(locator.text.after).take(60)
        val selector = locator.locations["cssSelector"]
        val offset = (locator.locations["folioOffset"] as? Number)?.toInt()
        val candidates = if (quote == null) emptyList() else words.indices.filter { i -> words[i].text == quote }
        val target = ((locator.locations.progression ?: 0.0) * words.size).toInt().coerceIn(0, words.lastIndex)
        val pool = candidates.ifEmpty { words.indices.filter { selector != null && words[it].locator.locations["cssSelector"] == selector } }
        // Rank all evidence together. Identical refrains must not override a valid element anchor.
        val index = pool.maxWithOrNull(compareBy<Int> { i ->
            val candidate = words[i].locator
            var score = if (selector != null && candidate.locations["cssSelector"] == selector) 8 else 0
            if (before.isNotBlank() && normalized(candidate.text.before).endsWith(before)) score += 4
            if (after.isNotBlank() && normalized(candidate.text.after).startsWith(after)) score += 4
            score
        }.thenBy { i ->
            val candidate = words[i].locator
            val actual = (candidate.locations["folioOffset"] as? Number)?.toInt()
            if (offset != null && actual != null && selector != null && candidate.locations["cssSelector"] == selector)
                -kotlin.math.abs(actual - offset) else -kotlin.math.abs(i - target)
        }) ?: target
        return cursor(section, index)
    }
    fun restore(cursor: ReadingCursor?): ReadingCursor? = cursor?.let { saved ->
        cursor(saved.sectionIndex, saved.wordIndex)?.takeIf { it.locator.text == saved.locator.text && it.locator.href == saved.locator.href }
            ?: locate(saved.locator)
    } ?: first()
    fun paragraph(cursor: ReadingCursor): ReadingParagraph? {
        val ranges = paragraphRanges.getOrNull(cursor.sectionIndex) ?: return null
        var low = 0; var high = ranges.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1; val range = ranges[mid]
            when { cursor.wordIndex < range.firstWord -> high = mid - 1
                cursor.wordIndex >= range.endWord -> low = mid + 1
                else -> return range }
        }
        return null
    }
    fun sentence(cursor: ReadingCursor): ReadingSentence? {
        val items = sections.getOrNull(cursor.sectionIndex)?.sentences ?: return null
        var low = 0; var high = items.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val sentence = items[mid]
            when { cursor.wordIndex < sentence.firstWord -> high = mid - 1
                cursor.wordIndex >= sentence.endWord -> low = mid + 1
                else -> return sentence }
        }
        return null
    }

    companion object {
        private val extraction = Mutex()
        private val cache = linkedMapOf<String, BookText>()
        fun forget(id: String) = synchronized(cache) { cache.remove(id); Unit }
        suspend fun load(publication: Publication, cacheKey: String? = null): BookText = extraction.withLock {
            cacheKey?.let { synchronized(cache) { cache[it] } }?.let { return@withLock it }
            val result = extract(publication)
            if (cacheKey != null && result.sections.sumOf { it.words.size } <= 150000) synchronized(cache) {
                cache[cacheKey] = result
                while (cache.size > 2) cache.remove(cache.keys.first())
            }
            result
        }
        private suspend fun extract(publication: Publication): BookText = withContext(Dispatchers.Default) {
            val content = publication.content() ?: throw IllegalArgumentException("This book has no readable text.")
            data class Builder(val href: Url, val title: String, val words: MutableList<ReadingWord> = mutableListOf(),
                val sentences: MutableList<ReadingSentence> = mutableListOf())
            val groups = linkedMapOf<Url, Builder>()
            var elementIdentity = 0
            var totalWords = 0
            var totalCharacters = 0L
            val language = publication.metadata.languages.firstOrNull()?.toString().orEmpty().ifBlank { "en" }
            val locale = Locale.forLanguageTag(language)
            for (element in content) if (element is Content.TextElement) {
                currentCoroutineContext().ensureActive()
                val identity = ++elementIdentity
                val text = element.text
                if (text.isBlank()) continue
                totalCharacters += text.length
                require(totalCharacters <= 12_000_000) { "This book has too much text to prepare safely on this device." }
                val href = element.locator.href.removeFragment()
                val group = groups.getOrPut(href) { Builder(href, element.locator.title ?: "Section") }
                val words = group.words; val sentences = group.sentences
                val base = words.size
                // Split pathological unbroken tokens so every speech chunk respects engine limits.
                val matches = Regex("\\S{1,256}").findAll(text).toList()
                totalWords += matches.size
                require(totalWords <= 750000) { "This book has too many words to prepare safely on this device." }
                matches.forEachIndexed { index, match ->
                    val nextStart = matches.getOrNull(index + 1)?.range?.first ?: text.length
                    val characters = match.value.codePointCount(0, match.value.length) + if (nextStart > match.range.last + 1) 1 else 0
                    words.add(ReadingWord(match.value, element.locator, characters.coerceAtLeast(1), text,
                        match.range.first, match.range.last + 1, identity))
                }
                val breaks = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
                breaks.first()
                var end = breaks.next(); var assigned = 0
                while (end != BreakIterator.DONE) {
                    currentCoroutineContext().ensureActive()
                    val first = assigned
                    while (assigned < matches.size && matches[assigned].range.first < end) assigned++
                    var chunk = first
                    while (chunk < assigned) {
                        var limit = chunk + 1
                        while (limit < assigned && matches[limit].range.last - matches[chunk].range.first < 2800) limit++
                        val a = matches[chunk].range.first; val b = matches[limit - 1].range.last + 1
                        sentences.add(ReadingSentence(text.substring(a, b), quote(element.locator, text, a, b), base + chunk, base + limit))
                        chunk = limit
                    }
                    end = breaks.next()
                }
            }
            val sections = groups.values.filter { it.words.isNotEmpty() }.map { TextSection(it.href, it.title, it.words, it.sentences) }
            require(sections.isNotEmpty()) { "This book contains no text for paced reading or speech." }
            val book = BookText(sections)
            book.copy(chapters = chapterAnchors(publication, book))
        }
        /** Verify TOC fragments against resource DOM order, including chapters sharing one spine item. */
        private suspend fun chapterAnchors(publication: Publication, book: BookText): List<ReadingCursor> {
            fun flatten(items: List<org.readium.r2.shared.publication.Link>): List<org.readium.r2.shared.publication.Link> =
                items.flatMap { listOf(it) + flatten(it.children) }
            val links = flatten(publication.tableOfContents).take(5000)
            val anchors = mutableListOf<ReadingCursor>()
            var clonedCharacters = 0L
            book.sections.forEachIndexed { sectionIndex, section ->
                currentCoroutineContext().ensureActive()
                val chapters = links.filter { it.url().removeFragment() == section.href }
                if (chapters.isEmpty()) return@forEachIndexed
                chapters.filter { it.url().fragment == null }.forEach { book.cursor(sectionIndex, 0)?.let(anchors::add) }
                val fragments = chapters.mapNotNull { it.url().fragment }.distinct()
                if (fragments.isEmpty()) return@forEachIndexed
                val html = publication.get(section.href)?.use { resource ->
                    resource.read().flatMap { it.decodeString() }.getOrElse { null }
                } ?: return@forEachIndexed
                if (html.length > 12_000_000) return@forEachIndexed
                val document = Jsoup.parse(html)
                val order = java.util.IdentityHashMap<Element, Int>()
                document.getAllElements().forEachIndexed { index, element -> order[element] = index }
                data class ExtractedElement(val ordinal: Int, val firstWord: Int, val element: Element)
                val elements = mutableListOf<ExtractedElement>()
                var previousIdentity = -1
                section.words.forEachIndexed { wordIndex, word ->
                    if (word.elementId == previousIdentity) return@forEachIndexed
                    previousIdentity = word.elementId
                    val selector = word.locator.locations["cssSelector"] as? String ?: return@forEachIndexed
                    val element = runCatching { document.selectFirst(selector) }.getOrNull() ?: return@forEachIndexed
                    order[element]?.let { elements += ExtractedElement(it, wordIndex, element) }
                }
                val byElement = java.util.IdentityHashMap<Element, ExtractedElement>()
                elements.forEach { if (!byElement.containsKey(it.element)) byElement[it.element] = it }
                fun atOrAfter(ordinal: Int): Int? {
                    var low = 0; var high = elements.size
                    while (low < high) { val mid = (low + high) ushr 1
                        if (elements[mid].ordinal < ordinal) low = mid + 1 else high = mid }
                    return elements.getOrNull(low)?.firstWord
                }
                fragments.forEach { fragment ->
                    val target = document.getElementById(fragment) ?: return@forEach
                    val ordinal = order[target] ?: return@forEach
                    val containing = byElement[target] ?: target.parents().firstNotNullOfOrNull { byElement[it] }
                    val word = if (containing != null) {
                        if (containing.element === target) containing.firstWord else {
                            // An empty inline fragment can begin a chapter midway through a paragraph.
                            clonedCharacters += containing.element.wholeText().length
                            if (clonedCharacters > 12_000_000) return emptyList() // Bounded optional TOC verification; use labelled sections.
                            val clone = containing.element.clone()
                            val marker = "\uE000FOLIO_CHAPTER_ANCHOR\uE001"
                            val node = clone.getElementById(fragment) ?: return@forEach
                            node.before(marker)
                            val prefix = clone.wholeText().substringBefore(marker).replace(Regex("\\s+"), " ").trimStart()
                            val range = book.paragraph(book.cursor(sectionIndex, containing.firstWord)!!) ?: return@forEach
                            (range.firstWord until range.endWord).firstOrNull { i ->
                                val locator = section.words[i].locator
                                val offset = (locator.locations["folioOffset"] as? Number)?.toInt() ?: 0
                                offset + section.words[i].text.length > prefix.length
                            } ?: atOrAfter(ordinal + 1)
                        }
                    } else atOrAfter(ordinal)
                    word?.let { book.cursor(sectionIndex, it)?.let(anchors::add) }
                }
            }
            val sorted = anchors.distinctBy { it.sectionIndex to it.wordIndex }.sortedWith(compareBy({ it.sectionIndex }, { it.wordIndex }))
            if (sorted.isEmpty()) return emptyList()
            // Front matter before the first TOC anchor remains a navigable boundary.
            return (listOfNotNull(book.first()) + sorted).distinctBy { it.sectionIndex to it.wordIndex }
        }
        internal fun quoteAt(locator: Locator, text: String, start: Int, end: Int) = quote(locator, text, start, end)

        private fun quote(locator: Locator, text: String, start: Int, end: Int) = locator.copy(locations = locator.locations.copy(otherLocations = locator.locations.otherLocations + ("folioOffset" to start)), text = Locator.Text(
            before = (locator.text.before.orEmpty().takeLast((160 - start).coerceAtLeast(0)) + text.substring((start - 160).coerceAtLeast(0), start)).takeLast(160),
            highlight = text.substring(start, end),
            after = (text.substring(end, (end + 160).coerceAtMost(text.length)) + locator.text.after.orEmpty().take((160 - (text.length - end)).coerceAtLeast(0))).take(160)))
    }
}

/** Speech and paced-reading files are separate from both one another and ordinary reading progress. */
class ReaderCursorStore(context: Context) {
    private val directory = File(context.filesDir, "reader-state").apply { mkdirs() }
    private fun file(bookId: String, kind: String): AtomicFile {
        require(bookId.matches(Regex("[a-f0-9]{64}")))
        require(kind == "speech" || kind == "guided" || kind == "pace")
        return AtomicFile(File(directory, "$bookId-$kind.json"))
    }
    fun read(bookId: String, kind: String): ReadingCursor? = synchronized(lock) {
        val atomic = file(bookId, kind)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@synchronized null
        try {
            val json = atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
            require(json.getInt("version") == 1)
            val locator = requireNotNull(Locator.fromJSON(json.getJSONObject("locator")))
            ReadingCursor(json.getInt("section").also { require(it >= 0) }, json.getInt("word").also { require(it >= 0) }, locator)
        } catch (error: Throwable) {
            throw IllegalStateException("The saved $kind position could not be read. It has been kept unchanged.", error)
        }
    }
    fun write(bookId: String, kind: String, cursor: ReadingCursor) = synchronized(lock) {
        val atomic = file(bookId, kind)
        val data = JSONObject().put("version", 1).put("section", cursor.sectionIndex).put("word", cursor.wordIndex)
            .put("locator", cursor.locator.toJSON()).toString().toByteArray()
        DurableJson.write(atomic, data)
    }
    fun forget(bookId: String, kind: String) = synchronized(lock) { DurableJson.delete(file(bookId, kind)) }
    fun recover(bookId: String, kind: String): ReadingCursor? = synchronized(lock) {
        try { read(bookId, kind) } catch (_: IllegalStateException) {
            val source = file(bookId, kind)
            if (source.baseFile.exists()) source.baseFile.copyTo(File(directory, "$bookId-$kind.damaged-${System.currentTimeMillis()}.json"))
            source.delete(); null
        }
    }
    companion object { private val lock = Any() }

}
