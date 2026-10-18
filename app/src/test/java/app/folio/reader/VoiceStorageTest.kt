package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK 35's POSIX rename-over-existing AtomicFile implementation cannot run on a Windows host.
// The device storage test exercises repeated registry commits on real Android.
@Config(sdk=[28])
class VoiceStorageTest {
    private lateinit var context:Context
    private lateinit var store:VoicePackStore
    private lateinit var storage:VoiceStorage
    private fun isolated():Context {
        val app=ApplicationProvider.getApplicationContext<FolioApp>();val root=java.nio.file.Files.createTempDirectory("v-store-").toFile()
        return object:ContextWrapper(app) {
            override fun getNoBackupFilesDir()=File(root,"private").apply { mkdirs() }
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(root.name+name,mode)
        }
    }
    @Before fun setup()=runBlocking { context=isolated();store=VoicePackStore(context);store.existingCheck.join();storage=VoiceStorage(context,store,cancelTransfers={}) }
    private fun model(version:String,id:String=store.catalog.first().id,components:Set<String> = setOf(version)):Pair<VoicePack,File> {
        val bytes="owned model $version".toByteArray();val base=store.pack(id)
        val pack=base.copy(version=version,files=listOf(SpeechModelFile("model.bin",bytes.size.toLong(),SpeechPcm.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))),required=setOf("model.bin"),expandedBytes=bytes.size.toLong())
        val folder=store.directory(pack).apply { mkdirs() };File(folder,"model.bin").writeBytes(bytes);File(folder,".ready").writeText(version)
        storage.recordVerified(pack,components);return pack to folder
    }
    @Test fun cleanupOnlyDeletesOwnedSupersededModelsAndKeepsCurrentUnknownCachesAndBookData()=runBlocking {
        val current=store.catalog.first();val currentDir=store.directory(current).apply { mkdirs() };File(currentDir,"current").writeText("keep current")
        val (_,old)=model("known-old-v1")
        val unknown=File(context.noBackupFilesDir,"local-voices/kokoro-obsolete-looking").apply { mkdirs() };File(unknown,"private").writeText("keep unknown")
        val checkpoint=File(context.filesDir,"reader-state/bookmarks.json").apply { parentFile!!.mkdirs();writeText("bookmarks + progress") }
        val cache=File(context.cacheDir,"ai-audio/retained.pcm").apply { parentFile!!.mkdirs();writeText("pinned or cached") }
        val inventory=storage.inventory();val selected=inventory.reclaimable(VoiceCleanup.OLD_VERSIONS)
        assertEquals(listOf("local-voices/known-old-v1"),selected.map { it.key });assertTrue(selected.single().bytes>0)
        val result=storage.clean(VoiceCleanup.OLD_VERSIONS,selected.map { it.key }.toSet())
        assertEquals(1,result.removed);assertTrue(result.bytes>0);assertFalse(old.exists());assertTrue(currentDir.isDirectory);assertTrue(unknown.isDirectory)
        assertEquals("bookmarks + progress",checkpoint.readText());assertEquals("pinned or cached",cache.readText())
        assertEquals(0,storage.clean(VoiceCleanup.OLD_VERSIONS,selected.map { it.key }.toSet()).removed)
    }
    @Test fun currentSharedComponentsAndRollbackAreProtectedTransitively()=runBlocking {
        val (leaf,leafDir)=model("shared-leaf-v1")
        val (middle,middleDir)=model("shared-middle-v1",components=setOf("shared-middle-v1",leaf.version))
        val current=store.catalog.first();model(current.version,current.id,setOf(current.version,middle.version))
        val (rollback,rollbackDir)=model("rollback-old-v1")
        val previous=File(store.transferFolder(current),"previous").apply { mkdirs() };File(previous,".ready").writeText(rollback.version);File(previous,"model.bin").writeText("rollback backup")
        val inventory=storage.inventory();assertTrue(inventory.reclaimable(VoiceCleanup.OLD_VERSIONS).isEmpty())
        storage.clean(VoiceCleanup.OLD_VERSIONS,setOf("local-voices/${leaf.version}","local-voices/${middle.version}","local-voices/${rollback.version}"))
        assertTrue(leafDir.exists());assertTrue(middleDir.exists());assertTrue(rollbackDir.exists());assertTrue(previous.exists())
    }
    @Test fun selectedModelThatBecomesLoadedWhileNativeDrainWaitsIsRecheckedAndKept()=runBlocking {
        val (old,folder)=model("loaded-race-v1");val plan=storage.inventory().reclaimable(VoiceCleanup.OLD_VERSIONS).map { it.key }.toSet()
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        LocalVoiceWorker.executor.execute { entered.complete(Unit);release.await(5,TimeUnit.SECONDS) };entered.await()
        var lease:java.io.Closeable?=null
        try {
            val cleanup=async(Dispatchers.IO){storage.clean(VoiceCleanup.OLD_VERSIONS,plan)}
            delay(80);lease=VoiceModelUse.acquire(context,old.version);release.countDown()
            val result=withTimeout(5000){cleanup.await()};assertEquals(0,result.removed);assertTrue(folder.exists())
        } finally { release.countDown();lease?.close() }
    }
    @Test fun transferDiscardCancelsAndDrainsThenKeepsRollbackAndUnknownEntries()=runBlocking {
        val pack=store.catalog.first();val folder=store.transferFolder(pack)
        val part=File(folder,"archive.part").apply { writeText("resumable partial") }
        val previous=File(folder,"previous").apply { mkdirs() };File(previous,"saved").writeText("rollback")
        val unknown=File(folder,"unknown-folder").apply { mkdirs() };File(unknown,"notes").writeText("unrecognized")
        val events=mutableListOf<String>()
        val cleaner=VoiceStorage(context,store,cancelTransfers={ ids->events+="cancel:${ids.single()}";assertEquals("Cleaning",store.state(pack.id).phase);store.update(VoicePackState(pack.id,"Downloading",99));assertEquals("Cleaning",store.state(pack.id).phase) })
        store.update(VoicePackState(pack.id,"Cancelled"),force=true)
        val plan=cleaner.inventory().reclaimable(VoiceCleanup.DOWNLOADS).map { it.key }.toSet();assertTrue(plan.contains("voice-transfers/${pack.id}/archive.part"))
        val result=cleaner.clean(VoiceCleanup.DOWNLOADS,plan)
        assertEquals(listOf("cancel:${pack.id}"),events);assertTrue(result.removed>=1);assertFalse(part.exists());assertTrue(previous.exists());assertTrue(unknown.exists())
        assertTrue(cleaner.inventory().reclaimable(VoiceCleanup.DOWNLOADS).isEmpty());assertFalse(store.state(pack.id).busy)
    }
    @Test fun cancelledWorkerMustLeaveTransferCriticalSectionBeforeFilesAreDeleted()=runBlocking {
        val pack=store.catalog.first();val part=File(store.transferFolder(pack),"archive.part").apply { writeText("still open") }
        val held=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val worker=launch(Dispatchers.IO){VoicePackWorker.exclusiveTransfers { held.complete(Unit);release.await() }};held.await()
        val cleaner=VoiceStorage(context,store,cancelTransfers={cancelled.complete(Unit)})
        val plan=cleaner.inventory().reclaimable(VoiceCleanup.DOWNLOADS).map { it.key }.toSet()
        val cleanup=async(Dispatchers.IO){cleaner.clean(VoiceCleanup.DOWNLOADS,plan)}
        try {
            cancelled.await();delay(50);assertTrue(part.exists());assertFalse(cleanup.isCompleted)
            release.complete(Unit);worker.join();assertTrue(withTimeout(5000){cleanup.await()}.removed>=1);assertFalse(part.exists())
        } finally { release.complete(Unit);worker.cancelAndJoin();cleanup.cancelAndJoin() }
    }
    @Test fun explicitPackRemovalCannotDeleteModelHeldOutsideSpeechService()=runBlocking {
        val current=store.catalog.first();val (_,folder)=model(current.version,current.id)
        store.update(VoicePackState(current.id,"Ready"),force=true)
        val lease=VoiceModelUse.acquire(context,current.version)
        try {
            assertTrue(runCatching { storage.removePack(current.id) }.isFailure)
            assertTrue(folder.exists());assertEquals("Ready",store.state(current.id).phase)
        } finally { lease.close() }
    }
    @Test fun cancellationBeforeTransferDrainKeepsFilesAndReleasesMaintenanceState()=runBlocking {
        val pack=store.catalog.first();val part=File(store.transferFolder(pack),"archive.part").apply { writeText("partial") }
        store.update(VoicePackState(pack.id,"Cancelled"),force=true)
        val cleaner=VoiceStorage(context,store,cancelTransfers={throw CancellationException("cancelled fixture")})
        val keys=cleaner.inventory().reclaimable(VoiceCleanup.DOWNLOADS).map { it.key }.toSet()
        assertTrue(runCatching { cleaner.clean(VoiceCleanup.DOWNLOADS,keys) }.exceptionOrNull() is CancellationException)
        currentCoroutineContext().ensureActive();assertTrue(part.exists());assertFalse(store.state(pack.id).busy)
    }
    @Test fun unknownContentsCorruptRegistryAndLinkedPathsNeverBecomeCleanupCandidates()=runBlocking {
        val (_,old)=model("old-with-unknown-v1");File(old,"unrecognized").writeText("keep")
        assertTrue(storage.inventory().reclaimable(VoiceCleanup.OLD_VERSIONS).isEmpty())
        File(old,"unrecognized").delete();File(context.noBackupFilesDir,"voice-storage-owned.json").writeText("broken registry")
        assertTrue(storage.inventory().reclaimable(VoiceCleanup.OLD_VERSIONS).isEmpty());assertTrue(old.exists())
    }
    @Test fun leasesAreReferenceCountedIsolatedAndExcludeRacingAcquisition() {
        val other=isolated();val first=VoiceModelUse.acquire(context,"same-version");val second=VoiceModelUse.acquire(context,"same-version")
        try {
            first.close();assertTrue("same-version" in VoiceModelUse.active(context))
            VoiceModelUse.reserve(other,setOf("same-version")).use { assertTrue(runCatching { VoiceModelUse.acquire(other,"same-version") }.isFailure) }
            assertTrue(runCatching { VoiceModelUse.reserve(context,setOf("same-version")) }.isFailure)
        } finally {first.close();second.close()}
        VoiceModelUse.reserve(context,setOf("same-version")).use { assertTrue(runCatching { VoiceModelUse.acquire(context,"same-version") }.isFailure) }
        VoiceModelUse.acquire(context,"same-version").close();assertTrue(VoiceModelUse.active(context).isEmpty())
    }
    @Test fun symlinkCannotEscapePrivateVoiceRoot()=runBlocking {
        val (old,folder)=model("linked-old-v1");folder.deleteRecursively()
        val outside=java.nio.file.Files.createTempDirectory("voice-outside-").toFile();val bookmark=File(outside,"bookmark").apply { writeText("external") }
        assumeTrue(runCatching { java.nio.file.Files.createSymbolicLink(folder.toPath(),outside.toPath()) }.isSuccess)
        val result=storage.clean(VoiceCleanup.OLD_VERSIONS,setOf("local-voices/${old.version}"))
        assertEquals(0,result.removed);assertEquals("external",bookmark.readText());assertTrue(java.nio.file.Files.isSymbolicLink(folder.toPath()))
    }
}
