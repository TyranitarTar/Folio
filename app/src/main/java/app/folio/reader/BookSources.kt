package app.folio.reader

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

enum class BookSource(val label: String) { GUTENBERG("Project Gutenberg"), WIKISOURCE("Wikisource") }

data class SourceBook(val source: BookSource, val id: String, val language: String, val title: String,
    val author: String = "", val summary: String = "", val pageUrl: String, val coverUrl: String? = null,
    val downloadUrl: String? = null, val format: String? = null, val rights: String = "") {
    val key: String get() = sourceHash("${source.name}\n$language\n$id".toByteArray())
    fun json() = JSONObject().put("source", source.name).put("id", id).put("language", language)
        .put("title", title).put("author", author).put("summary", summary).put("pageUrl", pageUrl)
        .put("coverUrl", coverUrl).put("downloadUrl", downloadUrl).put("format", format).put("rights", rights)
    companion object {
        fun from(j: JSONObject): SourceBook {
            val source = BookSource.valueOf(j.getString("source"))
            val id = j.getString("id").also { require(it.isNotBlank() && it.length <= 512) }
            val language = j.getString("language").also { require(it in setOf("en", "da")) }
            val page = SourceUrls.checked(j.getString("pageUrl"), source)
            return SourceBook(source, id, language, j.getString("title").take(1024), j.optString("author").take(2048),
                j.optString("summary").take(8192), page,
                j.optString("coverUrl").takeIf { it.isNotBlank() }?.let { SourceUrls.checked(it, source, images = true) },
                j.optString("downloadUrl").takeIf { it.isNotBlank() }?.let { SourceUrls.checked(it, source) },
                j.optString("format").takeIf { it in setOf("epub", "txt", "html", "wiki") }, j.optString("rights").take(8192))
        }
    }
}

data class SourcePage(val books: List<SourceBook>, val next: String? = null, val cached: Boolean = false)
data class SourceOrigin(val source: String, val entry: String, val language: String, val url: String, val contentHash: String) {
    val key: String get() = sourceHash("$source\n$language\n$entry".toByteArray())
    fun json() = JSONObject().put("source", source).put("entry", entry).put("language", language)
        .put("url", url).put("contentHash", contentHash)
    companion object { fun from(j: JSONObject): SourceOrigin {
        val source = BookSource.valueOf(j.getString("source"))
        val entry = j.getString("entry").also { require(it.isNotBlank() && it.length <= 512) }
        val language = j.getString("language").also { require(it in setOf("en", "da")) }
        val hash = j.getString("contentHash").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        return SourceOrigin(source.name, entry, language, SourceUrls.checked(j.getString("url"), source), hash)
    } }
}

internal fun sourceHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun sourceQuery(text: String) = URLEncoder.encode(text, "UTF-8")

internal object SourceUrls {
    fun checked(raw: String, source: BookSource, images: Boolean = false): String {
        val u = URI(if (raw.startsWith("//")) "https:$raw" else raw)
        require(u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443)) { "The source supplied an unsupported address." }
        val host = u.host?.lowercase().orEmpty()
        val allowed = when (source) {
            BookSource.GUTENBERG -> host in setOf("www.gutenberg.org", "gutenberg.org")
            BookSource.WIKISOURCE -> host in setOf("en.wikisource.org", "da.wikisource.org") || (images && host in setOf("upload.wikimedia.org", "thumb.wikimedia.org"))
        }
        require(allowed) { "The source supplied a download outside its supported servers." }
        return u.toASCIIString()
    }
    fun resolve(base: String, raw: String, source: BookSource, images: Boolean = false): String = checked(URI(base).resolve(raw).toString(), source, images)
    fun wikiPage(language: String, title: String) = URI("https", "$language.wikisource.org", "/wiki/${title.replace(' ', '_')}", null).toASCIIString()
    fun wikiApi(language: String, title: String) = "https://$language.wikisource.org/w/api.php?action=parse&page=${sourceQuery(title)}&prop=text%7Cdisplaytitle%7Crevid&redirects=1&formatversion=2&format=json"
}

internal class SourceHttpError(val status: Int, val retrySeconds: Long = 0) : IOException(when (status) {
    429 -> "The source is busy. Wait before retrying."
    403 -> "The source refused this request. Please try again later."
    404 -> "This edition is no longer available from the source."
    else -> "The source returned HTTP $status."
})

/** Only public provider endpoints are used; no application publishing credentials enter this client. */
internal open class SourceHttp(private val cache: File? = null) {
    companion object { private val pacing = Mutex(); private var lastRequest = 0L }
    private suspend fun pace() = pacing.withLock {
        val wait = 1000 - (android.os.SystemClock.elapsedRealtime() - lastRequest)
        if (wait > 0) delay(wait)
        lastRequest = android.os.SystemClock.elapsedRealtime()
    }
    open suspend fun open(url: String, source: BookSource, images: Boolean = false, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var current = SourceUrls.checked(url, source, images)
        repeat(6) {
            currentCoroutineContext().ensureActive(); pace()
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Folio/0.8.0 (https://github.com/TyranitarTar/Folio; EPUB reader)")
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    current = SourceUrls.resolve(current, connection.getHeaderField("Location") ?: error("Missing redirect address"), source, images)
                    connection.disconnect()
                } else return connection
            } catch (e: Throwable) { connection.disconnect(); throw e }
        }
        throw IOException("The source redirected too many times.")
    }
    fun check(connection: HttpURLConnection) {
        if (connection.responseCode !in 200..299) throw SourceHttpError(connection.responseCode,
            connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 86400) ?: 0)
    }
    open suspend fun bytes(url: String, source: BookSource, limit: Int, images: Boolean = false): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url, source, images)
        try {
            check(connection)
            require(connection.contentLengthLong <= limit) { "The source response exceeds the supported size." }
            connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer); if (n < 0) break
                    require(out.size() + n <= limit) { "The source response exceeds the supported size." }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    suspend fun text(url: String, source: BookSource, staleFallback: Boolean = true, freshCache: Boolean = false): Pair<String, Boolean> {
        val file = cache?.let { File(it.apply { mkdirs() }, sourceHash(url.toByteArray()) + ".json") }
        if (freshCache && file?.exists() == true && System.currentTimeMillis() - file.lastModified() < 600000 && file.length() <= 4_000_000) {
            return withContext(Dispatchers.IO) { file.readText() } to false
        }
        try {
            val text = bytes(url, source, 4_000_000).toString(Charsets.UTF_8)
            withContext(Dispatchers.IO) { file?.let { target ->
                val temporary = File(target.parentFile, target.name + ".tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(target)) { temporary.delete(); throw IOException("Source cache could not be saved.") }
                target.parentFile?.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
            } }
            return text to false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: IOException) {
            if (staleFallback && file?.exists() == true && file.length() <= 4_000_000) return withContext(Dispatchers.IO) { file.readText() } to true
            throw e
        }
    }
}

internal object GutenbergCatalog {
    fun searchUrl(language: String, query: String) = "https://www.gutenberg.org/ebooks/search.opds/?query=${sourceQuery((query.trim() + " l." + if (language == "da") "danish" else "english").trim())}&sort_order=downloads"
    private fun Element.child(name: String): Element? = children().firstOrNull { it.tagName().substringAfter(':') == name }
    fun parse(xml: String, base: String, language: String): SourcePage {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Unsupported source XML declarations." }
        val doc = Jsoup.parse(xml, base, Parser.xmlParser())
        require(doc.getElementsByTag("feed").isNotEmpty()) { "The source did not return a book catalog." }
        val result = doc.getElementsByTag("entry").mapNotNull { entry ->
            val identifier = entry.child("id")?.text().orEmpty()
            val id = (Regex("/ebooks/(\\d+)").find(identifier) ?: Regex("^urn:gutenberg:(\\d+)(?::\\d+)?$").find(identifier))?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = entry.child("title")?.text()?.take(1024) ?: return@mapNotNull null
            val links = entry.children().filter { it.tagName() == "link" }
            val choices = links.filter { it.attr("rel").startsWith("http://opds-spec.org/acquisition") && it.attr("type") == "application/epub+zip" }
                .sortedByDescending { if ("epub3.images" in it.attr("href")) 3 else if (".images" in it.attr("href")) 2 else 1 }
            val chosen = choices.firstOrNull() ?: links.firstOrNull { it.attr("rel").startsWith("http://opds-spec.org/acquisition") && it.attr("type").substringBefore(';') in setOf("text/html", "application/xhtml+xml") }
                ?: links.firstOrNull { it.attr("rel").startsWith("http://opds-spec.org/acquisition") && it.attr("type").substringBefore(';') == "text/plain" }
            val download = chosen?.attr("href")?.let { SourceUrls.resolve(base, it, BookSource.GUTENBERG) }
            val format = chosen?.let { when (it.attr("type").substringBefore(';')) { "application/epub+zip" -> "epub"; "text/plain" -> "txt"; else -> "html" } }
            val author = entry.children().filter { it.tagName() == "author" }.mapNotNull { it.child("name")?.text() }.joinToString(", ")
                .ifBlank { entry.child("content")?.text().orEmpty() }
            val cover = links.firstOrNull { it.attr("rel").endsWith("/thumbnail") && !it.attr("href").startsWith("data:") }
                ?.attr("href")?.let { SourceUrls.resolve(base, it, BookSource.GUTENBERG, true) }
            SourceBook(BookSource.GUTENBERG, id, entry.child("language")?.text()?.takeIf { it in setOf("en", "da") } ?: language,
                title, author.take(2048), pageUrl = "https://www.gutenberg.org/ebooks/$id", coverUrl = cover,
                downloadUrl = download, format = format, rights = "Project Gutenberg copyright status is based on US law. Retain the edition's included license.")
        }.groupBy { it.id }.values.map { editions -> editions.maxBy { if (it.downloadUrl?.contains("epub3.images") == true) 3 else if (it.downloadUrl?.contains(".images") == true) 2 else 1 } }
        val next = doc.getElementsByTag("feed").first()?.children()?.firstOrNull { it.tagName() == "link" && it.attr("rel") == "next" }
            ?.attr("href")?.let { SourceUrls.resolve(base, it, BookSource.GUTENBERG) }
        return SourcePage(result, next)
    }
}

internal class BookCatalog(private val http: SourceHttp) {
    suspend fun search(source: BookSource, language: String, query: String, next: String? = null): SourcePage {
        require(language in setOf("en", "da"))
        if (source == BookSource.GUTENBERG) {
            val url = next ?: GutenbergCatalog.searchUrl(language, query)
            val (text, cached) = http.text(url, source)
            return GutenbergCatalog.parse(text, url, language).copy(cached = cached)
        }
        if (query.isBlank() && next == null) {
            val titles = if (language == "da") listOf("Den lille Havfrue", "Kejserens nye Klæder", "Den grimme Ælling", "Grantræet", "Nattergalen", "Fyrtøiet")
                else listOf("The Gift of the Magi", "The Raven", "The Tell-Tale Heart", "The Happy Prince", "The Yellow Wall Paper", "Alice's Adventures in Wonderland (1866)")
            return SourcePage(titles.map { SourceBook(source, it, language, it, pageUrl = SourceUrls.wikiPage(language, it), summary = "Open this work to check its available edition and text.") })
        }
        val url = next ?: "https://$language.wikisource.org/w/api.php?action=query&list=search&srsearch=${sourceQuery(query.trim().take(200))}&srnamespace=0&srlimit=20&format=json"
        val (text, cached) = http.text(url, source)
        val json = JSONObject(text)
        json.optJSONObject("error")?.let { throw IOException(it.optString("info", "The source search failed.")) }
        val array = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        val books = (0 until array.length()).map { array.getJSONObject(it) }.map { entry ->
            val title = entry.getString("title")
            SourceBook(source, title, language, title, summary = Jsoup.parse(entry.optString("snippet")).text().take(2048),
                pageUrl = SourceUrls.wikiPage(language, title))
        }
        val offset = json.optJSONObject("continue")?.optInt("sroffset", -1) ?: -1
        return SourcePage(books, if (offset >= 0) "https://$language.wikisource.org/w/api.php?action=query&list=search&srsearch=${sourceQuery(query.trim().take(200))}&srnamespace=0&srlimit=20&sroffset=$offset&format=json" else null, cached)
    }
    suspend fun edition(book: SourceBook): SourceBook {
        if (book.source == BookSource.GUTENBERG) {
            val url = "https://www.gutenberg.org/ebooks/${book.id}.opds"
            val (text, _) = http.text(url, book.source, staleFallback = false, freshCache = true)
            return GutenbergCatalog.parse(text, url, book.language).books.firstOrNull { it.id == book.id && it.downloadUrl != null }
                ?: throw IOException("This source does not offer a supported EPUB or text edition.")
        }
        val page = WikiBookPage.load(http, book.language, book.id)
        page.checkAvailable()
        return book.copy(id = page.title, title = Jsoup.parse(page.displayTitle).text().ifBlank { page.title },
            author = page.author, summary = page.body().text().take(1200), pageUrl = SourceUrls.wikiPage(book.language, page.title),
            downloadUrl = SourceUrls.wikiApi(book.language, page.title), format = "wiki",
            rights = "Wikisource transcriptions retain their source-page attribution and rights notices. Individual works have their own copyright status.")
    }
}

internal data class WikiBookPage(val language: String, val title: String, val displayTitle: String, val html: String) {
    private fun document() = Jsoup.parse(html, SourceUrls.wikiPage(language, title))
    val author: String get() = document().select("#ws-author, .ws-author, .author, #header_author_text a").firstOrNull()?.text().orEmpty().take(2048)
    fun body(): Element = (document().selectFirst(".mw-parser-output") ?: document().body()).clone()
    fun chapters(): List<String> {
        val root = readable(body())
        val toc = root.select(".ws-summary a[href]")
        val links = if (toc.isNotEmpty()) toc else root.select("a[href]")
        return links.mapNotNull { link ->
            if (toc.isNotEmpty() && (link.hasClass("new") || "redlink=1" in link.attr("href"))) throw IOException("This edition has missing sections and cannot be downloaded completely.")
            val uri = runCatching { URI(SourceUrls.wikiPage(language, title)).resolve(link.attr("href")) }.getOrNull() ?: return@mapNotNull null
            if (uri.host != "$language.wikisource.org" || !uri.path.startsWith("/wiki/")) return@mapNotNull null
            val child = uri.path.removePrefix("/wiki/").replace('_', ' ')
            if (child == title || child.substringBefore(':') in setOf("Author", "Forfatter", "File", "Fil", "Index", "Side", "Page", "Template", "Skabelon", "Wikisource") ||
                (toc.isEmpty() && !child.startsWith("$title/"))) null else {
                if (link.hasClass("new")) throw IOException("This edition has missing sections and cannot be downloaded completely.")
                child
            }
        }.distinct().also { require(it.size <= 120) { "This edition has too many sections for a supported download." } }
    }
    fun checkAvailable() {
        val root = body()
        require(root.select(".disambig, .disambigbox, #disambigbox, .redirectMsg").isEmpty() && !root.text().startsWith("Redirect to:")) {
            "Choose a specific edition of this work from the search results."
        }
        require(root.select(".noarticletext, .mw-warning-with-logexcerpt").isEmpty()) { "This source page has no downloadable text." }
        val parts = chapters()
        require(parts.isNotEmpty() || readable(root).text().length >= 100) { "This source page does not contain a complete readable work." }
    }
    companion object {
        fun readable(root: Element): Element = root.clone().apply {
            select("script, style, link, iframe, object, embed, form, button, input, nav, .mw-editsection, .ws-noexport, .noprint, .ws-header, #headerContainer, .header_notes, .redirectMsg").remove()
        }
        suspend fun load(http: SourceHttp, language: String, title: String): WikiBookPage {
            val (text, _) = http.text(SourceUrls.wikiApi(language, title), BookSource.WIKISOURCE, staleFallback = false, freshCache = true)
            val json = JSONObject(text)
            json.optJSONObject("error")?.let { throw IOException(it.optString("info", "This work could not be retrieved.")) }
            val parse = json.optJSONObject("parse") ?: throw IOException("The source did not return the work's text.")
            val html = (parse.opt("text") as? String) ?: parse.optJSONObject("text")?.optString("*") ?: error("No work text")
            return WikiBookPage(language, parse.getString("title"), parse.optString("displaytitle", title), html)
        }
    }
}
