package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.jsoup.Jsoup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BookSourcesTest {
    private lateinit var folder: File
    private lateinit var context: Context
    private fun book(language: String = "da") = SourceBook(BookSource.WIKISOURCE, "Prøve", language, "Prøve: æ, ø og å", "Folio test",
        pageUrl = SourceUrls.wikiPage(language, "Prøve"), downloadUrl = SourceUrls.wikiApi(language, "Prøve"), format = "wiki", rights = "Original test text; retain this notice.")
    @Before fun setup() {
        val base = ApplicationProvider.getApplicationContext<FolioApp>()
        folder = java.nio.file.Files.createTempDirectory(base.cacheDir.toPath(), "sources-test-").toFile()
        context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
    }
    @Test fun gutenbergNavigationAndAcquisitionFeedsResolveLinksAndPreferIllustratedEpub3() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="?start_index=26"/><entry><id>https://www.gutenberg.org/ebooks/42.opds</id><title>Æble &amp; Ø</title><content>Example Author</content><link rel="subsection" href="/ebooks/42.opds"/></entry><entry><id>https://www.gutenberg.org/ebooks/43.opds</id><title>Book</title><author><name>Writer</name></author><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/ebooks/43.epub.noimages"/></entry><entry><id>https://www.gutenberg.org/ebooks/43.opds</id><title>Book</title><author><name>Writer</name></author><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/ebooks/43.epub3.images"/></entry></feed>"""
        val result = GutenbergCatalog.parse(xml, "https://www.gutenberg.org/ebooks/search.opds/", "da")
        assertEquals(2, result.books.size)
        assertEquals("Æble & Ø", result.books.first().title)
        assertNull(result.books.first().downloadUrl)
        assertEquals("https://www.gutenberg.org/ebooks/43.epub3.images", result.books.last().downloadUrl)
        assertEquals("Writer", result.books.last().author)
        assertTrue(result.next!!.contains("start_index=26"))
    }
    @Test fun sourceLinksRejectCredentialsUnsafeSchemesPortsAndUnrelatedServers() {
        for (url in listOf("http://www.gutenberg.org/a", "file:///a", "https://www.gutenberg.org.evil.invalid/a", "https://name:password@www.gutenberg.org/a", "https://www.gutenberg.org:444/a", "https://127.0.0.1/a")) {
            assertTrue(url, runCatching { SourceUrls.checked(url, BookSource.GUTENBERG) }.isFailure)
        }
        assertTrue(runCatching { SourceUrls.checked("https://upload.wikimedia.org/image.png", BookSource.WIKISOURCE) }.isFailure)
        assertEquals("https://upload.wikimedia.org/image.png", SourceUrls.checked("//upload.wikimedia.org/image.png", BookSource.WIKISOURCE, true))
        assertEquals("https://thumb.wikimedia.org/image.png", SourceUrls.checked("//thumb.wikimedia.org/image.png", BookSource.WIKISOURCE, true))
        assertTrue(runCatching { SourceUrls.checked("https://thumb.wikimedia.org/image.png", BookSource.WIKISOURCE) }.isFailure)
    }
    @Test fun liveGutenbergUrnEditionsPreferIllustratedEpubInsteadOfUnsupportedMobipocket() {
        val xml = """<feed><entry><id>urn:gutenberg:11:2</id><title>Alice</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://www.gutenberg.org/ebooks/11.epub.noimages"/></entry><entry><id>urn:gutenberg:11:3</id><title>Alice</title><link rel="http://opds-spec.org/acquisition" type="application/x-mobipocket-ebook" href="https://www.gutenberg.org/ebooks/11.kindle.images"/><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://www.gutenberg.org/ebooks/11.epub3.images"/></entry></feed>"""
        val book = GutenbergCatalog.parse(xml, "https://www.gutenberg.org/ebooks/11.opds", "en").books.single()
        assertEquals("11", book.id); assertEquals("epub", book.format); assertTrue(book.downloadUrl!!.endsWith("epub3.images"))
    }
    @Test fun xmlExternalEntityDeclarationsAndOffProviderDownloadsAreRejected() {
        assertTrue(runCatching { GutenbergCatalog.parse("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///private'>]><feed/>", "https://www.gutenberg.org/", "en") }.isFailure)
        assertTrue(runCatching { GutenbergCatalog.parse("""<feed><entry><id>https://www.gutenberg.org/ebooks/1</id><title>Book</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://unrelated.invalid/a.epub"/></entry></feed>""", "https://www.gutenberg.org/", "en") }.isFailure)
    }
    @Test fun wikiContentsKeepSourceOrderAndExcludeHeaderNavigationAndUnrelatedPages() {
        val html = """<div class="mw-parser-output"><div class="ws-header"><a href="/wiki/Pr%C3%B8ve/Other_edition">Other edition</a></div><div class="ws-summary"><a href="/wiki/Pr%C3%B8ve/Anden_del">Second</a><a href="/wiki/Pr%C3%B8ve/F%C3%B8rste_del">First</a><a href="/wiki/Forfatter:Someone">Author</a></div><a href="/wiki/Unrelated">Other</a></div>"""
        val page = WikiBookPage("da", "Prøve", "Prøve", html)
        assertEquals(listOf("Prøve/Anden del", "Prøve/Første del"), page.chapters())
        page.checkAvailable()
    }
    @Test fun wikiMissingSectionsAndAmbiguousEditionPagesCannotBePreparedAsCompleteBooks() {
        val incomplete = WikiBookPage("en", "Work", "Work", """<div class="mw-parser-output"><div class="ws-summary"><a class="new" href="/w/index.php?title=Work/Part&amp;redlink=1">Part</a></div></div>""")
        assertTrue(runCatching { incomplete.checkAvailable() }.isFailure)
        val ambiguous = WikiBookPage("en", "Work", "Work", "<div class='disambigbox'>${"Choose an edition. ".repeat(20)}</div>")
        assertTrue(runCatching { ambiguous.checkAvailable() }.isFailure)
    }
    @Test fun textEncodingPreservesDanishAndRejectsUnknownOrBinaryContent() {
        val expected = "Æbler, øer og blå åer.\nAnden linje."
        assertEquals(expected, BookConversion.decodeText(expected.toByteArray()))
        val bom = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        assertEquals(expected, BookConversion.decodeText(bom + expected.toByteArray(Charsets.UTF_16LE)))
        assertTrue(runCatching { BookConversion.decodeText(byteArrayOf(0xff.toByte(), 0x00, 0x80.toByte())) }.isFailure)
        assertTrue(runCatching { BookConversion.decodeText("binary\u0000data".toByteArray()) }.isFailure)
    }
    @Test fun textConversionBuildsReadableEpubWithLineBreaksMetadataAndRights() = runBlocking {
        val original = File(folder, "original.txt").apply { writeText(("Æbler, øer og blå åer.\nEn linje med <tegn> & andre tegn.\n\n").repeat(6)) }
        val converted = BookConversion.fromText(book(), original, File(folder, "text.epub"))
        EpubValidator.validate(converted.file)
        ZipFile(converted.file).use { zip ->
            assertEquals("mimetype", zip.entries().nextElement().name)
            assertEquals(java.util.zip.ZipEntry.STORED, zip.getEntry("mimetype").method)
            val chapter = zip.getInputStream(zip.getEntry("OEBPS/chapter-0.xhtml")).bufferedReader().readText()
            assertTrue(chapter.contains("<br />")); assertTrue(chapter.contains("&lt;tegn&gt;"))
            assertTrue(Jsoup.parse(chapter).text().contains("Æbler, øer og blå åer."))
            val opf = zip.getInputStream(zip.getEntry("OEBPS/book.opf")).bufferedReader().readText()
            assertTrue(opf.contains("<dc:language>da</dc:language>")); assertTrue(opf.contains("retain this notice"))
        }
        val store = LibraryStore(context, BookEngine(context))
        val imported = store.importStream { converted.file.inputStream() }
        assertEquals(book().title, imported.title)
        val publication = store.engine.open(store.file(imported.id))
        val text = try { BookText.load(publication, imported.id) } finally { publication.close() }
        assertTrue(text.sections.any { section -> section.words.any { "Æbler" in it.text } })
    }
    @Test fun htmlConversionRemovesExecutableContentButPreservesNotesAndInternalLinks() = runBlocking {
        val original = File(folder, "original.html").apply { writeText("""<html><body><h1>Prøve</h1><p onclick="bad()">${"Dette er original dansk tekst. ".repeat(6)}<a href="#note">1</a></p><p id="note">En bevaret fodnote.</p><script>bad()</script><iframe src="https://evil.invalid"></iframe><a href="javascript:bad()">unsafe link</a></body></html>""") }
        val converted = BookConversion.fromHtml(book().copy(format = "html"), original, File(folder, "html.epub"), SourceHttp())
        ZipFile(converted.file).use { zip ->
            val html = zip.getInputStream(zip.getEntry("OEBPS/chapter-0.xhtml")).bufferedReader().readText()
            assertFalse(html.contains("<script")); assertFalse(html.contains("<iframe")); assertFalse(html.contains("onclick=")); assertFalse(html.contains("javascript:"))
            assertTrue(html.contains("href=\"#note\"")); assertTrue(html.contains("id=\"note\"")); assertTrue(html.contains("En bevaret fodnote."))
        }
    }
    @Test fun originDeduplicationPreservesProgressAndAnnotationsAcrossReload() = runBlocking {
        val store = LibraryStore(context, BookEngine(context))
        val initial = store.importSample()
        val publication = store.engine.open(store.file(initial.id))
        val text = try { BookText.load(publication, initial.id) } finally { publication.close() }
        val locator = text.cursor(0, 0)!!.locator
        store.progress(initial.id, locator); store.addMark(initial.id, locator, true, "Keep this annotation")
        val savedLocatorBytes = store.books.value.single().locator
        val origin = SourceOrigin("WIKISOURCE", "Prøve", "da", book().pageUrl, "a".repeat(64))
        val attached = store.importStream(origin) { context.assets.open("sample.epub") }
        assertEquals(initial.id, attached.id); assertEquals(origin, attached.origin)
        val reloaded = LibraryStore(context, BookEngine(context)); reloaded.load()
        val same = reloaded.importStream(origin) { ByteArrayInputStream("This stream should never be imported".toByteArray()) }
        assertEquals(initial.id, same.id); assertEquals("Keep this annotation", same.highlights.single().note)
        assertEquals(savedLocatorBytes, same.locator); assertEquals(1, reloaded.books.value.size)
    }
    @Test fun sourceMetadataRoundTripDoesNotAcceptUnrelatedUrls() {
        assertEquals(book(), SourceBook.from(book().json()))
        assertTrue(runCatching { SourceBook.from(book().json().put("downloadUrl", "https://unrelated.invalid/book")) }.isFailure)
        assertTrue(runCatching { SourceOrigin.from(JSONObject().put("source", "WIKISOURCE").put("entry", "Book").put("language", "da").put("url", "https://da.wikisource.org/wiki/Book").put("contentHash", "invalid")) }.isFailure)
    }
    @Test fun wikiConversionPackagesAllOrderedSectionsIllustrationsAndCrossChapterFootnotes() = runBlocking {
        val pages = mapOf("Prøve" to """<div class="mw-parser-output"><div class="ws-summary"><a href="/wiki/Pr%C3%B8ve/Anden">Second</a><a href="/wiki/Pr%C3%B8ve/F%C3%B8rste">First</a></div></div>""",
            "Prøve/Anden" to """<div class="mw-parser-output"><h1>Second</h1><p>${"Anden del bevares. ".repeat(10)}<a href="/wiki/Pr%C3%B8ve/F%C3%B8rste#note">Footnote</a></p><img src="//thumb.wikimedia.org/test.svg"/></div>""",
            "Prøve/Første" to """<div class="mw-parser-output"><h1>First</h1><p id="note">${"Første del med æ, ø og å. ".repeat(10)}</p></div>""")
        val http = object : SourceHttp() {
            override suspend fun bytes(url: String, source: BookSource, limit: Int, images: Boolean): ByteArray {
                if (images) return """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"><rect width="1" height="1" fill="black"/></svg>""".toByteArray()
                val title = java.net.URLDecoder.decode(URL(url).query.split('&').first { it.startsWith("page=") }.substringAfter('='), "UTF-8")
                return JSONObject().put("parse", JSONObject().put("title", title).put("text", pages.getValue(title))).toString().toByteArray()
            }
        }
        val converted = BookConversion.fromWikisource(book(), folder, File(folder, "wiki.epub"), http) { _, _, _ -> }
        ZipFile(converted.file).use { zip ->
            val second = zip.getInputStream(zip.getEntry("OEBPS/chapter-1.xhtml")).bufferedReader().readText()
            val first = zip.getInputStream(zip.getEntry("OEBPS/chapter-2.xhtml")).bufferedReader().readText()
            assertTrue(second.contains("chapter-2.xhtml#note")); assertTrue(first.contains("Første del med æ, ø og å."))
            assertTrue(second.contains("images/image-0.svg")); assertNotNull(zip.getEntry("OEBPS/images/image-0.svg"))
            assertNotNull(zip.getEntry("OEBPS/chapter-3.xhtml"))
        }
        assertTrue(File(folder, "original-wikisource.json").isFile)
    }
    private open class Response(private val data: String, private val status: Int = 200,
        private val headers: Map<String, String> = mapOf("ETag" to "\"edition-1\""), private val declared: Long = data.length.toLong()) : HttpURLConnection(URL("https://www.gutenberg.org/book")) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = headers[name]
        override fun getContentLengthLong() = declared
        override fun getInputStream() = ByteArrayInputStream(data.toByteArray())
    }
    private fun transferBook() = SourceBook(BookSource.GUTENBERG, "42", "en", "Test book", pageUrl = "https://www.gutenberg.org/ebooks/42",
        downloadUrl = "https://www.gutenberg.org/ebooks/42.epub3.images", format = "epub")
    @Test fun interruptedTransferResumesOnlyWithMatchingValidatorAndCommitsAfterAllBytes() = runBlocking {
        val first = Response("abc", declared = 6)
        val second = Response("def", 206, mapOf("ETag" to "\"edition-1\"", "Content-Range" to "bytes 3-5/6"))
        val requests = mutableListOf<Map<String, String>>()
        val http = object : SourceHttp() {
            override suspend fun open(url: String, source: BookSource, images: Boolean, headers: Map<String, String>): HttpURLConnection {
                requests.add(headers); return if (requests.size == 1) first else second
            }
        }
        val failed = runCatching { SourceTransfer.fetch(transferBook(), folder, http) { _, _ -> } }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertFalse(File(folder, "original.download").exists()); assertEquals("abc", File(folder, "original.part").readText())
        val result = SourceTransfer.fetch(transferBook(), folder, http) { _, _ -> }
        assertEquals("abcdef", result.readText()); assertEquals("bytes=3-", requests[1]["Range"])
        assertEquals("\"edition-1\"", requests[1]["If-Range"]); assertTrue(first.closed && second.closed)
    }
    @Test fun serverIgnoringResumeRestartsInsteadOfAppendingAndBadRangeCannotCommit() = runBlocking {
        File(folder, "original.part").writeText("old")
        File(folder, "transfer.json").writeText(JSONObject().put("url", transferBook().downloadUrl).put("etag", "\"edition-1\"").toString())
        val bad = Response("wrong", 206, mapOf("Content-Range" to "bytes 0-4/5"))
        val fresh = Response("new complete edition")
        var calls = 0
        val http = object : SourceHttp() { override suspend fun open(url: String, source: BookSource, images: Boolean, headers: Map<String, String>) = if (calls++ == 0) bad else fresh }
        assertTrue(runCatching { SourceTransfer.fetch(transferBook(), folder, http) { _, _ -> } }.isFailure)
        assertFalse(File(folder, "original.download").exists())
        assertEquals("new complete edition", SourceTransfer.fetch(transferBook(), folder, http) { _, _ -> }.readText())
        assertTrue(bad.closed && fresh.closed)
    }
    @Test fun cancelledTransferDoesNotImportOrRenameItsPartialFile() = runBlocking {
        val response = object : Response("test content") {
            override fun getInputStream() = object : ByteArrayInputStream("test content".toByteArray()) {
                private var readOnce = false
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readOnce) throw CancellationException("User cancelled")
                    readOnce = true
                    return super.read(buffer, offset, length)
                }
            }
        }
        val http = object : SourceHttp() { override suspend fun open(url: String, source: BookSource, images: Boolean, headers: Map<String, String>) = response }
        val task = launch { SourceTransfer.fetch(transferBook(), folder, http) { _, _ -> } }
        task.join()
        assertTrue(task.isCancelled); assertFalse(File(folder, "original.download").exists()); assertTrue(response.closed)
        assertEquals("test content", File(folder, "original.part").readText())
    }
}
