package app.folio.reader

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class GitHubRelease(val version: String, val tag: String, val url: String, val bytes: Long, val digest: String, val notes: String) {
    fun json() = JSONObject().put("version", version).put("tag", tag).put("url", url).put("bytes", bytes).put("digest", digest).put("notes", notes)
}
internal object UpdatePolicy {
    const val repository = "TyranitarTar/Folio"
    const val maximumBytes = 1024L * 1024 * 1024
    fun version(value: String): List<Int> {
        require(value.matches(Regex("v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))) { "The release version is not supported." }
        return value.removePrefix("v").split('.').map { it.toInt() }
    }
    fun newer(candidate: String, installed: String): Boolean {
        val a = version(candidate); val b = version(installed)
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    fun parse(json: JSONObject): GitHubRelease {
        require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "Only stable published releases are supported." }
        val tag = json.getString("tag_name"); version(tag); val v = tag.removePrefix("v")
        val assets = json.getJSONArray("assets")
        val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == "Folio-$v.apk" }
        require(candidates.size == 1) { "This release has no unique Folio APK." }
        val asset = candidates.single(); val url = asset.getString("browser_download_url")
        require(url == "https://github.com/$repository/releases/download/$tag/Folio-$v.apk") { "The APK address is not from the Folio release repository." }
        val digest = asset.getString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { "The release is missing its SHA-256 checksum." }
        val bytes = asset.getLong("size"); require(bytes in 1..maximumBytes) { "The APK size is outside the supported limit." }
        return GitHubRelease(v, tag, url, bytes, digest.lowercase(), json.optString("body").take(50000))
    }
    fun restore(j: JSONObject): GitHubRelease = parse(JSONObject().put("tag_name", j.getString("tag"))
        .put("body", j.optString("notes")).put("assets", org.json.JSONArray().put(JSONObject()
            .put("name", "Folio-${j.getString("version")}.apk").put("browser_download_url", j.getString("url"))
            .put("size", j.getLong("bytes")).put("digest", "sha256:${j.getString("digest")}"))))
    fun validateIdentity(expectedPackage: String, installedCode: Long, actualPackage: String?, actualCode: Long,
        installedSigners: Set<String>, candidateSigners: Set<String>, expectedVersion: String, actualVersion: String?) {
        require(actualPackage == expectedPackage) { "This APK belongs to a different application." }
        require(actualCode > installedCode && actualVersion == expectedVersion) { "This APK is not the expected newer version." }
        require(installedSigners.isNotEmpty() && candidateSigners == installedSigners) { "This APK was not signed by Folio's installed signing key." }
    }
    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
internal data class UpdateState(val status: String = "Check for a new version when you choose.", val busy: Boolean = false,
    val downloading: Boolean = false, val progress: Float? = null, val release: GitHubRelease? = null, val ready: Boolean = false, val error: Boolean = false)

internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val prefs = application.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val downloads = application.getSystemService(DownloadManager::class.java)
    val state = MutableStateFlow(UpdateState())
    private var job: Job? = null
    private var revision = 0L
    private var downloadId = prefs.getLong("id", -1)
    private val installed get() = context.packageManager.getPackageInfo(context.packageName, 0)
    init {
        val restored = prefs.getString("release", null)?.let { runCatching { UpdatePolicy.restore(JSONObject(it)) }.getOrNull() }
        if (downloadId >= 0 && restored != null && UpdatePolicy.newer(restored.version, installed.versionName.orEmpty())) monitor(restored)
        else if (downloadId >= 0 && restored != null) { downloads.remove(downloadId); verifiedFile(restored).delete(); downloadId = -1; prefs.edit().clear().apply() }
        else if (downloadId >= 0) { downloads.remove(downloadId); downloadId = -1; prefs.edit().clear().apply() }
    }
    fun check() {
        if (state.value.downloading) return
        job?.cancel(); val ticket = ++revision; state.value = UpdateState("Checking GitHub…", busy = true)
        job = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    val connection = URL("https://api.github.com/repos/${UpdatePolicy.repository}/releases/latest").openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "Folio/${installed.versionName}")
                    try {
                        val code = connection.responseCode
                        require(code != 404) { "The repository or a published release is not available yet." }
                        require(code != 403 && code != 429) { "GitHub is limiting update checks. Try again later." }
                        require(code == 200) { "GitHub update check failed ($code)." }
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break
                                require(output.size() + n <= 2 * 1024 * 1024) { "The release response is too large." }; output.write(buffer, 0, n) }
                            output.toByteArray()
                        }
                        require(bytes.size <= 2 * 1024 * 1024) { "The release response is too large." }
                        UpdatePolicy.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
                    } finally { connection.disconnect() }
                }
                ensureActive(); if (ticket != revision) return@launch
                val available = UpdatePolicy.newer(release.version, installed.versionName.orEmpty())
                state.value = UpdateState(if (available) "Folio ${release.version} is available." else "You have the latest published version.", release = release.takeIf { available })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == revision) state.value = UpdateState(e.message ?: "Could not check updates. Check your connection and retry.", error = true) }
        }
    }
    private fun file(release: GitHubRelease) = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "Folio-${release.version}.apk")
    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        try {
            if (downloadId >= 0) downloads.remove(downloadId)
            file(release).delete()
            downloadId = downloads.enqueue(DownloadManager.Request(Uri.parse(release.url))
                .setTitle("Folio ${release.version}").setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file(release).name))
            prefs.edit().putLong("id", downloadId).putString("release", release.json().toString()).apply()
            monitor(release)
        } catch (e: Exception) { state.value = state.value.copy(status = e.message ?: "The APK download could not start.", error = true) }
    }
    private fun monitor(release: GitHubRelease) {
        job?.cancel()
        val ticket = ++revision; val acceptedId = downloadId
        job = viewModelScope.launch {
            state.value = UpdateState("Downloading APK…", busy = true, downloading = true, release = release)
            try {
                while (true) {
                    val row = withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(acceptedId)).use { c ->
                            require(c.moveToFirst()) { "The download is no longer available. Retry the download." }
                            Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }
                    require(row.second <= UpdatePolicy.maximumBytes && row.third <= UpdatePolicy.maximumBytes) { "The downloaded APK exceeds the size limit." }
                    if (row.first == DownloadManager.STATUS_FAILED) error("Download failed. Check your connection and storage, then retry.")
                    if (row.first == DownloadManager.STATUS_SUCCESSFUL) {
                        state.value = state.value.copy(status = "Verifying APK…", downloading = false, progress = null)
                        withContext(Dispatchers.IO) { prepareVerifiedCopy(release) }
                        ensureActive(); if (ticket != revision) return@launch
                        state.value = UpdateState("Verified update ready. Android will ask before installing.", release = release, ready = true)
                        break
                    }
                    state.value = state.value.copy(status = if (row.first == DownloadManager.STATUS_PAUSED) "Download waiting for a connection…" else "Downloading APK…",
                        progress = if (row.third > 0) (row.second.toDouble() / row.third).toFloat().coerceIn(0f, 1f) else null)
                    delay(750)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket != revision) return@launch
                downloads.remove(acceptedId); downloadId = -1; prefs.edit().clear().apply()
                state.value = UpdateState(e.message ?: "The update could not be verified.", release = release, error = true)
            }
        }
    }
    fun cancel() {
        revision++; job?.cancel(); job = null
        if (downloadId >= 0) downloads.remove(downloadId)
        downloadId = -1; prefs.edit().clear().apply()
        state.value = UpdateState("Update cancelled.", release = state.value.release)
    }
    private fun verifiedFile(release: GitHubRelease) = File(File(context.filesDir, "updates"), "Folio-${release.version}.apk")
    private suspend fun prepareVerifiedCopy(release: GitHubRelease) {
        val destination = verifiedFile(release)
        if (destination.isFile) { verify(destination, release); return }
        destination.parentFile!!.mkdirs()
        destination.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith("Folio-") && it.extension == "apk" && it != destination }.forEach { it.delete() }
        val temporary = File(destination.parentFile, "pending-${System.nanoTime()}.apk")
        try {
            var copied = 0L
            file(release).inputStream().buffered().use { input -> temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                    copied += count; require(copied <= release.bytes && copied <= UpdatePolicy.maximumBytes) { "The APK exceeds this release's size." }
                    output.write(buffer, 0, count)
                }
            } }
            require(copied == release.bytes && file(release).length() == release.bytes) { "The APK download is incomplete." }
            verify(temporary, release); currentCoroutineContext().ensureActive()
            require(temporary.renameTo(destination)) { "The verified APK could not be saved." }
        } finally { temporary.delete() }
    }
    @Suppress("DEPRECATION") private suspend fun verify(apk: File, release: GitHubRelease) {
        require(apk.isFile && apk.length() == release.bytes) { "The APK download is incomplete." }
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        apk.inputStream().buffered().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
            currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
            total += count; require(total <= release.bytes && total <= UpdatePolicy.maximumBytes) { "The APK exceeds this release's size." }
            hash.update(buffer, 0, count)
        } }
        require(total == release.bytes && apk.length() == release.bytes) { "The APK changed during verification." }
        require(hash.digest().joinToString("") { "%02x".format(it) } == release.digest) { "The APK checksum does not match this release." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)) { "Android could not read this APK." }
        fun code(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map { UpdatePolicy.sha256(it.toByteArray()) }.toSet()
        UpdatePolicy.validateIdentity(context.packageName, code(current), candidate.packageName, code(candidate), signers(current), signers(candidate), release.version, candidate.versionName)
    }
    fun install() {
        val release = state.value.release ?: return
        if (!state.value.ready || state.value.busy) return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                state.value = state.value.copy(status = "Allow installation for Folio, then return and tap Install update.")
                return
            }
            state.value = state.value.copy(busy = true, status = "Verifying APK before installation…")
            job = viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { prepareVerifiedCopy(release) }; ensureActive()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", verifiedFile(release))
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    state.value = state.value.copy(busy = false, status = "Confirm the update in Android's installer.")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.value = state.value.copy(busy = false, ready = false, error = true, status = e.message ?: "The update could not be installed.") }
            }
        } catch (e: Exception) { state.value = state.value.copy(status = e.message ?: "Could not open Android's installer.", error = true) }
    }
}
