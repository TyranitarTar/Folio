package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BookTextTest {
    private lateinit var context: Context
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<FolioApp>()
        val root = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "book-text-").toFile()
        context = object : ContextWrapper(app) { override fun getFilesDir() = root }
    }
    private suspend fun extracted(): Pair<BookText, String> {
        val library = LibraryStore(context, BookEngine(context))
        val book = library.importSample()
        val publication = library.engine.open(library.file(book.id))
        return try { BookText.load(publication) to book.id } finally { publication.close() }
    }
    @Test fun contentExtractionCoversWordsOnceAndPreservesDanish() = runBlocking {
        val (text, _) = extracted()
        assertTrue(text.sections.size >= 3)
        assertTrue(text.sections.flatMap { it.words }.any { it.text.contains("lænestol", ignoreCase = true) })
        text.sections.forEach { section ->
            val all = section.sentences.flatMap { sentence -> (sentence.firstWord until sentence.endWord).toList() }
            assertEquals(section.words.indices.toList(), all)
            section.words.forEach {
                assertEquals(it.text, it.locator.text.highlight)
                val characters = it.text.codePointCount(0, it.text.length)
                assertTrue(it.characterCount in characters..characters + 1)
            }
        }
    }
    @Test fun quotedCursorsSurviveLayoutChangesAndProgressionDoesNotResetToFirstWord() = runBlocking {
        val (text, _) = extracted()
        val section = text.sections.indexOfFirst { it.words.size > 20 }
        val point = text.cursor(section, 17)!!
        assertEquals(point, text.locate(point.locator.copyWithLocations(progression = .99)))
        val generic = point.locator.copy(locations = Locator.Locations(progression = .5), text = Locator.Text())
        assertEquals(text.sections[section].words.size / 2, text.locate(generic)!!.wordIndex)
        assertEquals(text.cursor(section, 18), text.next(point))
        assertEquals(point, text.previous(text.next(point)!!))
    }
    @Test fun ListeningAndGuidedPositionsAreDurableAndIndependent() = runBlocking {
        val (text, id) = extracted()
        val speech = text.cursor(0, 1)!!
        val guided = text.cursor(0, 3)!!
        val store = ReaderCursorStore(context)
        store.write(id, "speech", speech); store.write(id, "guided", guided)
        val reloaded = ReaderCursorStore(context)
        assertEquals(speech, reloaded.read(id, "speech"))
        assertEquals(guided, reloaded.read(id, "guided"))
        assertEquals(speech, text.restore(reloaded.read(id, "speech")))
    }
    @Test fun visibleWordContextWinsOverTheStartOfItsParagraph() = runBlocking {
        val (text, _) = extracted()
        val section = text.sections.indexOfFirst { it.words.size > 50 }
        val point = text.cursor(section, 37)!!
        val visible = point.locator.copy(text = point.locator.text.copy(
            before = point.locator.text.before?.replace(" ", "\n ")))
        assertEquals(point, text.locate(visible))
        val afterOnly = point.locator.copy(text = point.locator.text.copy(before = null))
        assertEquals(point, text.locate(afterOnly))
    }
    @Test fun paragraphsUseExtractionIdentityAndCoverEachWordExactlyOnce() = runBlocking {
        val (text, _) = extracted()
        text.sections.forEachIndexed { sectionIndex, section ->
            var word = 0
            while (word < section.words.size) {
                val paragraph = text.paragraph(text.cursor(sectionIndex, word)!!)!!
                assertEquals(word, paragraph.firstWord)
                assertTrue(paragraph.endWord > word)
                assertEquals(1, section.words.subList(word, paragraph.endWord).map { it.elementId }.distinct().size)
                assertEquals(paragraph, text.paragraph(text.cursor(sectionIndex, paragraph.endWord - 1)!!))
                word = paragraph.endWord
            }
        }
    }
    @Test fun tocFragmentsIdentifyTwoChaptersInsideOneResource() = runBlocking {
        val target = java.io.File(context.filesDir, "internal-chapters.epub")
        val sample = context.assets.open("sample.epub")
        java.util.zip.ZipInputStream(sample).use { input -> java.util.zip.ZipOutputStream(target.outputStream()).use { output ->
            var entry = input.nextEntry
            while (entry != null) {
                val current = entry!!
                var bytes = input.readBytes()
                if (current.name == "EPUB/nav.xhtml") bytes = bytes.toString(Charsets.UTF_8)
                    .replace("<li><a href=\"chapter2.xhtml\">", "<li><a href=\"chapter1.xhtml#inline-first\">Inline chapter</a></li><li><a href=\"chapter1.xhtml#internal-second\">Second internal chapter</a></li><li><a href=\"chapter2.xhtml\">").toByteArray()
                if (current.name == "EPUB/chapter1.xhtml") bytes = bytes.toString(Charsets.UTF_8)
                    .replace("</body>", "<p>Earlier words precede this fragment. <span id=\"inline-first\"></span>Following chapter starts here.</p><h2 id=\"internal-second\">Internal chapter heading</h2><p>Unique chapter opening and its final words.</p></body>").toByteArray()
                val copy = java.util.zip.ZipEntry(current.name)
                if (current.name == "mimetype") { copy.method = java.util.zip.ZipEntry.STORED; copy.size = bytes.size.toLong()
                    copy.crc = java.util.zip.CRC32().apply { update(bytes) }.value }
                output.putNextEntry(copy); output.write(bytes); output.closeEntry(); input.closeEntry(); entry = input.nextEntry
            }
        } }
        val publication = BookEngine(context).open(target)
        val text = try { BookText.load(publication) } finally { publication.close() }
        val internal = text.chapters.single { it.locator.text.highlight == "Internal" }
        assertTrue(internal.wordIndex > 0)
        assertEquals("Second internal chapter",text.chapterEntries.single { it.cursor==internal }.title)
        assertEquals("Inline chapter",text.chapterEntries.single { it.cursor?.locator?.text?.highlight=="Following" }.title)
        assertTrue(text.chapters.any { it.sectionIndex == internal.sectionIndex && it.wordIndex == 0 })
        assertEquals(text.cursor(internal.sectionIndex, internal.wordIndex), internal)
        val inline = text.chapters.single { it.locator.text.highlight == "Following" }
        assertTrue(inline.wordIndex > 0 && inline.wordIndex < internal.wordIndex)
        assertEquals(inline, SpeechNavigation.target(text, text.first()!!, 1, "chapter", false))
        assertEquals(internal, SpeechNavigation.target(text, inline, 1, "chapter", false))
        assertEquals(inline.wordIndex, SpeechNavigation.range(text, text.first()!!, "chapter")!!.end)
        assertEquals(2, SpeechNavigation.chapterIndex(text, internal))
    }
    @Test fun damagedSavedPositionReportsAnErrorInsteadOfResettingIt() {
        val store = ReaderCursorStore(context)
        val id = "a".repeat(64)
        val file = java.io.File(context.filesDir, "reader-state/$id-speech.json")
        file.writeText("damaged position")
        val error = runCatching { store.read(id, "speech") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("damaged position", file.readText())
        assertNull(store.read(id, "guided"))
    }
}
