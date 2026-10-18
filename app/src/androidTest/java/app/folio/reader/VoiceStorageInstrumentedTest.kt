package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceStorageInstrumentedTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FolioApp
    @Test fun nativeFilesystemCleanupKeepsBookmarksUnknownFoldersAndLoadedModel()=runBlocking {
        val root=File(app.cacheDir,"voice-storage-device-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app){
            override fun getNoBackupFilesDir()=File(root,"models").apply { mkdirs() }
            override fun getFilesDir()=File(root,"reading").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(root.name+name,mode)
        }
        val store=VoicePackStore(context);store.existingCheck.join();val storage=VoiceStorage(context,store,cancelTransfers={})
        val bytes="native storage fixture".toByteArray();val version="test-owned-superseded-v1"
        val pack=store.catalog.first().copy(version=version,files=listOf(SpeechModelFile("model.bin",bytes.size.toLong(),SpeechPcm.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))),required=setOf("model.bin"),expandedBytes=bytes.size.toLong())
        val folder=store.directory(pack).apply { mkdirs() };File(folder,"model.bin").writeBytes(bytes);File(folder,".ready").writeText(version);storage.recordVerified(pack)
        storage.recordVerified(pack) // A second atomic commit replaces an existing registry on Android.
        val unknown=File(context.noBackupFilesDir,"local-voices/unidentified-old-name").apply { mkdirs() };File(unknown,"keep").writeText("unknown")
        val bookmark=File(context.filesDir,"bookmarks.json").apply { writeText("saved bookmark and checkpoint") }
        val selected=setOf("local-voices/$version")
        VoiceModelUse.acquire(context,version).use {
            val kept=storage.clean(VoiceCleanup.OLD_VERSIONS,selected);assertEquals(0,kept.removed);assertTrue(folder.exists())
        }
        val result=storage.clean(VoiceCleanup.OLD_VERSIONS,selected)
        assertEquals(1,result.removed);assertFalse(folder.exists());assertTrue(unknown.exists());assertEquals("saved bookmark and checkpoint",bookmark.readText())
        val outside=File(root,"outside-models").apply { mkdirs() }
        val sentinel=File(outside,"model.bin").apply { writeBytes(bytes) }
        val linkedVersion="test-linked-owned-v1"
        val link=File(context.noBackupFilesDir,"local-voices/$linkedVersion")
        java.nio.file.Files.createSymbolicLink(link.toPath(),outside.toPath())
        try {
            storage.recordVerified(pack.copy(version=linkedVersion))
            assertEquals(0,storage.clean(VoiceCleanup.OLD_VERSIONS,setOf("local-voices/$linkedVersion")).removed)
            assertArrayEquals(bytes,sentinel.readBytes())
        } finally { java.nio.file.Files.delete(link.toPath()) }
        root.deleteRecursively()
        Unit
    }
    @Test fun voicePackUiShowsStorageTotalsAndExplicitCleanupActions() {
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)).use {
            val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("More")),10000));device.findObject(By.text("More")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Voice packs")),10000));device.findObject(By.text("Voice packs")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Voice storage")),15000))
            assertTrue(device.wait(Until.hasObject(By.textContains("Reclaimable")),30000))
            assertNotNull(device.findObject(By.text("Remove old versions")));assertNotNull(device.findObject(By.text("Discard downloads")))
            device.pressBack()
        }
    }
}
