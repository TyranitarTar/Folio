package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VoicePackTest {
    private val app=ApplicationProvider.getApplicationContext<FolioApp>()
    private fun isolated(): Context {
        val folder=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"voice-pack-test-").toFile()
        return object: ContextWrapper(app) {
            override fun getNoBackupFilesDir()=folder
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("${folder.name}-$name",mode)
        }
    }
    private fun sha(bytes:ByteArray)=SpeechPcm.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private suspend fun fixture(store:VoicePackStore, entries:Map<String,ByteArray> = mapOf("model.onnx" to "a model fixture".toByteArray())):Pair<VoicePack,File> {
        val file=File(store.transferFolder(store.catalog.first()),"fixture.zip")
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } }
        val specs=entries.map { (name,bytes)->SpeechModelFile(name,bytes.size.toLong(),sha(bytes)) }
        return store.catalog.first().copy(bytes=file.length(),sha256=sha(file.readBytes()),files=specs,
            expandedBytes=specs.sumOf { it.bytes },required=entries.keys) to file
    }
    @Test fun signedCatalogRejectsTraversalExternalHostsDuplicateIdentitiesAndOversizedSamples() {
        val raw=app.assets.open("voice-packs.json").bufferedReader().use { it.readText() }
        assertEquals(2,VoicePackCatalog.parse(raw).size)
        fun rejects(change:(JSONObject)->Unit) {
            val json=JSONObject(raw);change(json)
            assertTrue(runCatching { VoicePackCatalog.parse(json.toString()) }.isFailure)
        }
        rejects { it.getJSONArray("packs").getJSONObject(0).put("url","https://example.com/model.zip") }
        rejects { it.getJSONArray("packs").getJSONObject(0).getJSONArray("files").getJSONObject(0).put("name","../model.onnx") }
        rejects { val packs=it.getJSONArray("packs");packs.put(packs.getJSONObject(0)) }
        rejects { val voices=it.getJSONArray("packs").getJSONObject(0).getJSONArray("voices");voices.put(voices.getJSONObject(0)) }
        rejects { it.getJSONArray("packs").getJSONObject(0).getJSONArray("voices").getJSONObject(0).put("sample",
            JSONObject().put("url","https://github.com/TyranitarTar/Folio/releases/download/voice-packs-v1/sample.m4a")
                .put("bytes",2_000_001).put("sha256","a".repeat(64))) }
        for(url in listOf("http://github.com/TyranitarTar/Folio/releases/download/v/a","https://github.com/TyranitarTar/Folio/releases/download/v/%2e%2e/a","https://github.com@evil.test/TyranitarTar/Folio/releases/download/v/a"))
            assertTrue(runCatching { VoicePackCatalog.url(url) }.isFailure)
        assertTrue(runCatching { VoicePackHttp.checkedRedirect("https://evil.test/a") }.isFailure)
    }
    @Test fun boundedVerifiedExtractionRejectsModifiedOrUnexpectedContent()=runBlocking {
        val store=VoicePackStore(isolated());store.existingCheck.join()
        val (pack,archive)=fixture(store)
        val output=File(archive.parentFile,"extracted").apply { mkdirs() }
        store.extract(pack,archive,output)
        assertEquals("a model fixture",File(output,"model.onnx").readText())
        assertTrue(store.verify(output,pack))
        assertTrue(runCatching { store.extract(pack.copy(files=pack.files.map { it.copy(sha256="0".repeat(64)) }),archive,output) }.isFailure)
        assertTrue(runCatching { store.extract(pack.copy(expandedBytes=1),archive,output) }.isFailure)
        val malicious=fixture(store,mapOf("../escape" to byteArrayOf(1)))
        assertTrue(runCatching { store.extract(malicious.first,malicious.second,output) }.isFailure)
        assertFalse(File(output.parentFile,"escape").exists())
    }
    @Test fun failedOrCancelledInstallPreservesPreviousVoiceFilesAndOnlyVerifiedPackActivates()=runBlocking {
        val store=VoicePackStore(isolated());store.existingCheck.join()
        val (pack,archive)=fixture(store)
        val target=store.directory(pack).apply { mkdirs() };File(target,"old").writeText("previous valid voice")
        store.update(VoicePackState(pack.id,"Installing"))
        assertTrue(runCatching { store.install(pack.copy(sha256="0".repeat(64)),archive) { _,_-> } }.isFailure)
        assertEquals("previous valid voice",File(target,"old").readText())
        store.update(VoicePackState(pack.id,"Cancelled"))
        assertTrue(runCatching { store.install(pack,archive) { _,_-> } }.isFailure)
        assertEquals("previous valid voice",File(target,"old").readText())
        store.update(VoicePackState(pack.id,"Installing"),force=true)
        store.install(pack,archive) { _,_-> }
        assertEquals("Ready",store.state(pack.id).phase)
        assertEquals("a model fixture",File(target,"model.onnx").readText())
        assertFalse(File(target,"old").exists())
        assertEquals(pack.version,File(target,".ready").readText())
    }
    @Test fun cancelledTransferDoesNotRevertToBusyAndStaleReadyOrCheckingRecoversOnRestart()=runBlocking {
        val context=isolated();val store=VoicePackStore(context);store.existingCheck.join();val id=store.catalog.first().id
        store.update(VoicePackState(id,"Cancelled",45))
        store.update(VoicePackState(id,"Downloading",100))
        assertEquals("Cancelled",store.state(id).phase)
        store.update(VoicePackState(id,"Ready"),force=true)
        val reloaded=VoicePackStore(context);reloaded.existingCheck.join()
        assertEquals("Not downloaded",reloaded.state(id).phase)
        reloaded.update(VoicePackState(id,"Checking"))
        val checked=VoicePackStore(context);checked.existingCheck.join()
        assertFalse(checked.state(id).busy)
    }
    @Test fun removalDeletesOnlyVoiceDataAndDoesNotCancelTheCallingCoroutine()=runBlocking {
        val store=VoicePackStore(isolated());store.existingCheck.join();val pack=store.catalog.first()
        val target=store.directory(pack).apply { mkdirs() };File(target,"fixture").writeText("model data")
        store.update(VoicePackState(pack.id,"Ready"))
        store.remove(pack.id)
        assertFalse(target.exists());assertEquals("Not downloaded",store.state(pack.id).phase)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }
}
