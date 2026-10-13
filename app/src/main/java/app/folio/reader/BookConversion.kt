package app.folio.reader

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

internal data class ConvertedBook(val file: File, val sourceHash: String)
internal data class ConversionPart(val title: String, val url: String, val html: String)

/** Produces a local EPUB; unsupported/missing required content never becomes a library entry. */
internal object BookConversion {
    private const val MAX_TEXT = 20_000_000
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun decodeText(bytes: ByteArray): String {
        require(bytes.size <= MAX_TEXT) { "The text is too large to convert." }
        val encoding = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = try { encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
            catch (e: java.nio.charset.CharacterCodingException) { throw IOException("The text encoding is unsupported. Choose an EPUB or UTF-8/UTF-16 edition.", e) }
        require('\u0000' !in text) { "This is not a supported text file." }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    suspend fun fromText(book: SourceBook, original: File, destination: File): ConvertedBook = withContext(Dispatchers.IO) {
        require(original.length() <= MAX_TEXT)
        val bytes = original.readBytes()
        val text = decodeText(bytes)
        require(text.trim().length >= 100) { "This download has too little text to be a supported book." }
        val paragraphs = text.split(Regex("\n[\t ]*\n+"))
        // Do not infer chapter headings or collapse meaningful poem/preformatted line breaks.
        val body = paragraphs.joinToString("\n") { "<p>${xml(it).replace("\n", "<br />")}</p>" }
        write(book, listOf(ConversionPart(book.title, book.pageUrl, body)), emptyMap(), sourceHash(bytes), destination)
    }

    suspend fun fromHtml(book: SourceBook, original: File, destination: File, http: SourceHttp): ConvertedBook = withContext(Dispatchers.IO) {
        require(original.length() <= MAX_TEXT)
        val bytes = original.readBytes()
        val html = decodeText(bytes)
        require(!html.contains("id=\"anubis_challenge\"", true) && !html.contains("id=\"anubis_challenge_data\"", true)) { "The source returned an access page instead of the book." }
        val root = Jsoup.parse(html, book.downloadUrl ?: book.pageUrl).body()
        require(root.text().length >= 100)
        prepare(book, listOf(ConversionPart(book.title, book.downloadUrl ?: book.pageUrl, root.outerHtml())), destination, http)
    }

    suspend fun fromWikisource(book: SourceBook, folder: File, destination: File, http: SourceHttp,
        progress: suspend (String, Int, Int) -> Unit): ConvertedBook = withContext(Dispatchers.IO) {
        val root = WikiBookPage.load(http, book.language, book.id)
        root.checkAvailable()
        val parts = mutableListOf(ConversionPart(root.title, SourceUrls.wikiPage(book.language, root.title), WikiBookPage.readable(root.body()).outerHtml()))
        val pending = ArrayDeque(root.chapters())
        val visited = mutableSetOf(root.title)
        var totalBytes = parts.first().html.toByteArray().size
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val title = pending.removeFirst()
            if (!visited.add(title)) continue
            require(visited.size <= 121) { "This work has too many sections for a supported download." }
            progress("Preparing sections", parts.size, parts.size + pending.size + 1)
            val page = WikiBookPage.load(http, book.language, title)
            page.checkAvailable()
            val html = WikiBookPage.readable(page.body()).outerHtml()
            totalBytes += html.toByteArray().size
            require(totalBytes <= MAX_TEXT) { "This work is too large for text conversion." }
            parts += ConversionPart(page.title, SourceUrls.wikiPage(book.language, page.title), html)
            // Depth-first order follows the source's explicit TOC/subpage order.
            page.chapters().asReversed().forEach { if (it !in visited && it !in pending) pending.addFirst(it) }
        }
        folder.mkdirs()
        File(folder, "original-wikisource.json").writeText(JSONObject().put("source", book.json())
            .put("parts", JSONArray(parts.map { JSONObject().put("title", it.title).put("url", it.url).put("html", it.html) })).toString())
        prepare(book, parts, destination, http, progress)
    }

    private suspend fun prepare(book: SourceBook, parts: List<ConversionPart>, destination: File, http: SourceHttp,
        progress: suspend (String, Int, Int) -> Unit = { _, _, _ -> }): ConvertedBook {
        val digest = MessageDigest.getInstance("SHA-256")
        val images = linkedMapOf<String, ByteArray>()
        val imageNames = mutableMapOf<String, String>()
        val links = parts.mapIndexed { i, part -> URI(part.url).normalize().toString() to "chapter-$i.xhtml" }.toMap()
        var imageBytes = 0
        val prepared = parts.mapIndexed { i, part ->
            currentCoroutineContext().ensureActive()
            progress("Preparing book", i, parts.size)
            val document = Jsoup.parse(part.html, part.url)
            val body = WikiBookPage.readable(document.body())
            body.select("img").forEach { image ->
                val raw = image.attr("src")
                require(raw.isNotBlank()) { "An illustration has no supported source." }
                val url = SourceUrls.resolve(part.url, raw, book.source, images = true)
                val filename = imageNames[url] ?: run {
                    require(images.size < 100) { "This work contains too many illustrations to convert." }
                    val bytes = http.bytes(url, book.source, 5_000_000, images = true)
                    imageBytes += bytes.size
                    require(imageBytes <= 50_000_000) { "The illustrations exceed the conversion size limit." }
                    val ext = imageExtension(bytes)
                    "images/image-${images.size}.$ext".also { images[it] = bytes; imageNames[url] = it }
                }
                image.attr("src", filename).removeAttr("srcset").removeAttr("sizes")
            }
            body.select("a[href]").forEach { link ->
                val raw = link.attr("href")
                if (!raw.startsWith('#')) {
                    val uri = runCatching { URI(part.url).resolve(raw) }.getOrNull()
                    val local = uri?.takeIf { it.scheme in setOf("http", "https") }?.let { URI(it.scheme, it.authority, it.path, null, null).normalize().toASCIIString() }?.let { links[it] }
                    if (local != null) link.attr("href", local + (uri.rawFragment?.let { "#$it" } ?: ""))
                    else if (uri?.scheme == "https" || uri?.scheme == "http") link.attr("href", uri.toASCIIString())
                    else link.removeAttr("href")
                }
            }
            val safe = Safelist.relaxed().addTags("div", "span", "section", "figure", "figcaption", "sup", "sub", "h1", "h2", "h3", "h4", "h5", "h6")
                .addAttributes(":all", "id", "lang", "dir").addProtocols("a", "href", "#").preserveRelativeLinks(true)
            // Images are now local names. The cleaner must retain those relative references.
            safe.removeProtocols("img", "src", "http", "https")
            val clean = Cleaner(safe).clean(Document.createShell(part.url).apply { body().appendChild(body) })
            clean.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml).charset(Charsets.UTF_8)
            val html = clean.body().html()
            require(clean.body().text().isNotBlank()) { "A section has no readable text after preparation." }
            digest.update(part.url.toByteArray()); digest.update(html.toByteArray())
            ConversionPart(part.title, part.url, html)
        }
        images.forEach { (name, bytes) -> digest.update(name.toByteArray()); digest.update(bytes) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return write(book, prepared, images, hash, destination)
    }

    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)) -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> {
            val svg = bytes.toString(Charsets.UTF_8)
            require(bytes.size <= 1_000_000 && svg.contains("<svg") && !Regex("(?is)<!DOCTYPE|<!ENTITY|<(?:script|foreignObject|iframe|object|embed)|\\bon\\w+\\s*=|\\bhref\\s*=\\s*['\"](?!#)|url\\(\\s*(?!#)").containsMatchIn(svg)) {
                "An illustration uses an unsupported or unsafe format. Choose an EPUB edition."
            }
            "svg"
        }
    }

    private suspend fun write(book: SourceBook, parts: List<ConversionPart>, images: Map<String, ByteArray>, hash: String, destination: File): ConvertedBook {
        require(parts.isNotEmpty() && parts.size <= 121)
        val appendix = """<h1>Source and attribution</h1><p>${xml(book.source.label)}</p><p><a href="${xml(book.pageUrl)}">${xml(book.pageUrl)}</a></p><p>${xml(book.rights)}</p>""" +
            parts.joinToString("") { "<p>${xml(it.title)}: <a href=\"${xml(it.url)}\">Source page</a> · <a href=\"${xml(it.url)}?action=history\">Contributor history</a></p>" }
        val allParts = parts + ConversionPart("Source and attribution", book.pageUrl, appendix)
        val id = "urn:folio:$hash"
        val navItems = allParts.mapIndexed { i, part -> "<li><a href=\"chapter-$i.xhtml\">${xml(part.title)}</a></li>" }.joinToString("")
        val manifest = allParts.indices.joinToString("") { "<item id=\"c$it\" href=\"chapter-$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
            images.keys.mapIndexed { i, name -> "<item id=\"i$i\" href=\"$name\" media-type=\"${when (name.substringAfterLast('.')) { "jpg" -> "image/jpeg"; "svg" -> "image/svg+xml"; else -> "image/${name.substringAfterLast('.')}" }}\"/>" }.joinToString("")
        val opf = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:language>${xml(book.language)}</dc:language><dc:creator>${xml(book.author.ifBlank { book.source.label + " contributors" })}</dc:creator><dc:source>${xml(book.pageUrl)}</dc:source><dc:rights>${xml(book.rights)}</dc:rights><meta property="dcterms:modified">${java.time.Instant.now().toString().substringBefore('.') .substringBefore('Z')}Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="book.css" media-type="text/css"/>$manifest</manifest><spine>${allParts.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>"""
        val css = "body{font-family:serif;line-height:1.5}p{margin:.7em 0}img{max-width:100%;height:auto}table{max-width:100%}blockquote{margin-inline:1em}pre{white-space:pre-wrap}"
        destination.parentFile?.mkdirs()
        try {
            ZipOutputStream(destination.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray, stored: Boolean = false) {
                    val item = ZipEntry(name).apply { time = 0; if (stored) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value } }
                    zip.putNextEntry(item); zip.write(bytes); zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip".toByteArray(), true)
                entry("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
                entry("OEBPS/book.opf", opf.toByteArray())
                entry("OEBPS/book.css", css.toByteArray())
                entry("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${book.language}"><head><title>Contents</title></head><body><nav epub:type="toc"><h1>Contents</h1><ol>$navItems</ol></nav></body></html>""".toByteArray())
                allParts.forEachIndexed { i, part ->
                    currentCoroutineContext().ensureActive()
                    entry("OEBPS/chapter-$i.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" lang="${book.language}"><head><title>${xml(part.title)}</title><link rel="stylesheet" href="book.css"/></head><body>${part.html}</body></html>""".toByteArray())
                }
                images.forEach { (name, bytes) -> entry("OEBPS/$name", bytes) }
            }
            EpubValidator.validate(destination)
            return ConvertedBook(destination, hash)
        } catch (e: Throwable) { destination.delete(); throw e }
    }
}
