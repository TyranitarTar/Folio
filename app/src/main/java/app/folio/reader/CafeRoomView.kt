package app.folio.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream

internal val cafeRoomActions = linkedMapOf(
    "eat" to "Spis · forfra", "drink" to "Drik · forfra",
    "eat_far" to "Spis · bagfra", "drink_far" to "Drik · bagfra",
    "scratch" to "Hvæs kløer", "step" to "Nederste trin", "perch" to "Øverste platform",
    "perch_watch" to "Se dig omkring", "perch_groom" to "Pelspleje på platformen",
    "perch_sleep" to "Lur på platformen", "bookshelf" to "Hop til bogreolen",
    "bookshelf_sleep" to "Lur på bogreolen", "box" to "Gem dig i kassen",
    "tunnel" to "Kravl gennem tunnelen", "ball" to "Leg med bolden", "feather" to "Spring efter fjeren",
    "window" to "Kig ud ad vinduet", "door" to "Undersøg døren",
    "groom" to "Pelspleje på tæppet", "sleep" to "Lur på tæppet", "sofa" to "Lur på sofaen"
)
internal val cafeRoomModes = linkedMapOf(
    "random" to "Fri udforskning", "manual" to "Du bestemmer",
    "morning" to "En rolig morgen", "playful" to "Legestund",
    "climbing" to "Til vejrs og til ro", "tour" to "Alle 21 handlinger"
)
internal val cafeRoomPaces = linkedMapOf(
    "stroll" to "Slentren", "walk" to "Gang", "trot" to "Trav", "run" to "Løb", "sprint" to "Sprint"
)

/** Separate namespace: room discoveries never redeem treats or write book/story data. */
internal class CafeRoomPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("cafe-room-v1", Context.MODE_PRIVATE)
    var mode: String
        get() = prefs.getString("mode", "random").takeIf { it in cafeRoomModes } ?: "random"
        set(value) { if(value in cafeRoomModes) prefs.edit().putString("mode", value).apply() }
    var pace: String
        get() = prefs.getString("pace", "walk").takeIf { it in cafeRoomPaces } ?: "walk"
        set(value) { if(value in cafeRoomPaces) prefs.edit().putString("pace", value).apply() }
    var ambient: Boolean
        get() = prefs.getBoolean("ambient", true)
        set(value) { prefs.edit().putBoolean("ambient", value).apply() }
    var reduced: Boolean
        get() = prefs.getBoolean("reduced", false)
        set(value) { prefs.edit().putBoolean("reduced", value).apply() }
    fun counts(): JSONObject = sanitize(prefs.getString("counts", "{}") ?: "{}")
    fun saveCounts(json: String) { prefs.edit().putString("counts", sanitize(json).toString()).apply() }
    companion object {
        fun sanitize(json: String): JSONObject {
            val result = JSONObject()
            if(json.length > 4096) return result
            val source = runCatching { JSONObject(json) }.getOrNull() ?: return result
            cafeRoomActions.keys.forEach { id ->
                val value = source.opt(id)
                if(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0..100_000)
                    result.put(id, value.toLong())
            }
            return result
        }
    }
}

/** A single, local-only WebView owns one renderer. It has no access to file/content URLs. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class CafeRoomView(context: Context) : WebView(context) {
    companion object { val liveInstances=java.util.concurrent.atomic.AtomicInteger(0) }
    var onStatus: (JSONObject) -> Unit = {}
    var onPick: (String) -> Unit = {}
    var onFailure: () -> Unit = {}
    var onDiscoveries: (String) -> Unit = {}
    var onPageReady: () -> Unit = {}
    private var disposed = false
    private var hostActive = false
    private var initialized=false
    private val origin = "https://folio.local/cafe/"
    init {
        liveInstances.incrementAndGet()
        // WebView treats a WRAP_CONTENT height as a zero-height CSS viewport,
        // even when Compose gives AndroidView a fixed measured rectangle.
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        contentDescription = "Misos kattecafé · interaktivt værelse"
        setBackgroundColor(android.graphics.Color.rgb(242, 235, 223))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.blockNetworkLoads = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(Bridge(), "FolioRoom")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = asset(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if(url != origin + "index.html") { stopLoading(); onFailure() }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if(!disposed && url == origin + "index.html") { onPageReady(); setHostActive(hostActive) }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if(request.isForMainFrame && !disposed) onFailure()
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if(!disposed) onFailure()
                return true // Retry replaces this dead WebView; don't crash the reader.
            }
        }
        initialized=true
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if(initialized)applyVisibility()
    }
    override fun onWindowVisibilityChanged(visibility:Int) {
        super.onWindowVisibilityChanged(visibility)
        if(initialized)applyVisibility()
    }
    internal fun asset(uri: Uri): WebResourceResponse {
        fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        if(uri.scheme != "https" || uri.host != "folio.local" || uri.port != -1 || uri.query != null || uri.fragment != null) return missing()
        val path = uri.path ?: return missing()
        if(!path.startsWith("/cafe/") || path.contains("..") || path.contains('\\')) return missing()
        val mime = when(path.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "application/javascript"; "json" -> "application/json"; "png" -> "image/png"
            else -> return missing()
        }
        return try { WebResourceResponse(mime, if(mime == "image/png") null else "UTF-8", context.assets.open(path.removePrefix("/"))) }
        catch(_: Exception) { missing() }
    }
    fun openRoom() { loadUrl(origin + "index.html") }
    fun configure(json: JSONObject) { script("window.FolioCafe?.configure($json)") }
    fun command(id: String) { script("window.FolioCafe?.command(${JSONObject.quote(id)})") }
    fun setHostActive(active: Boolean) {
        hostActive = active
        applyVisibility()
    }
    private fun applyVisibility() {
        if(disposed) { return }
        val active=hostActive && windowVisibility==android.view.View.VISIBLE && hasWindowFocus()
        if(active) { onResume() }
        script("window.FolioCafe?.active($active)")
        if(!active) { onPause() }
    }
    private fun script(code: String) { if(!disposed) evaluateJavascript(code, null) }
    fun disposeRoom() {
        if(disposed) { return }
        script("window.FolioCafe?.destroy()")
        disposed = true
        liveInstances.decrementAndGet()
        onStatus = {}; onPick = {}; onFailure = {}; onDiscoveries = {}; onPageReady = {}
        stopLoading(); removeJavascriptInterface("FolioRoom"); onPause()
        (parent as? android.view.ViewGroup)?.removeView(this)
        removeAllViews(); destroy()
    }
    private inner class Bridge {
        private fun dispatch(value: String, action: (JSONObject) -> Unit) {
            if(value.length > 8192) return
            val json = runCatching { JSONObject(value) }.getOrNull() ?: return
            post { if(!disposed) action(json) }
        }
        @JavascriptInterface fun status(value: String) = dispatch(value) { onStatus(it) }
        @JavascriptInterface fun pick(value: String) = dispatch(value) { onPick(it.optString("prop")) }
        @JavascriptInterface fun discoveries(value: String) = dispatch(value) { onDiscoveries(it.toString()) }
        @JavascriptInterface fun failure(value: String) = dispatch(value) { onFailure() }
    }
}
