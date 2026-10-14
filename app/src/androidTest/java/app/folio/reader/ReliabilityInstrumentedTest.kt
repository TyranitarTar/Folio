@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment

@RunWith(AndroidJUnit4::class)
class ReliabilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FolioApp
    private val device = UiDevice.getInstance(instrumentation)
    private fun isolated(): Context {
        val files = File(app.cacheDir, "v6-fixture-${System.nanoTime()}").apply { mkdirs() }
        return object : ContextWrapper(app) {
            override fun getFilesDir() = files
            override fun getCacheDir() = File(files, "cache").apply { mkdirs() }
        }
    }
    private fun navigator(activity: ReaderActivity) = ReaderActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }.get(activity) as EpubNavigatorFragment
    private suspend fun waitFor(test: () -> Boolean) { withTimeout(15000) { while (!test()) delay(100) } }
    @Test fun legacyMigrationAndPortableBackupRoundTripOnAndroid() = runBlocking {
        val original = app.library.importSample()
        val context = isolated(); val store = LibraryStore(context, BookEngine(context))
        store.file(original.id).writeBytes(app.library.file(original.id).readBytes())
        val publication = store.engine.open(store.file(original.id))
        val point = try { BookText.load(publication).cursor(0, 8)!! } finally { publication.close() }
        val book = original.copy(locator = point.locator.toJSON().toString(), progress = .8, lastRead = 1000)
        File(context.filesDir, "library/library.json").writeText(JSONObject().put("version", 1).put("books", JSONArray(listOf(book.json(), book.copy(id = "bad").json()))).toString())
        store.load(); assertEquals(book.id, store.books.value.single().id); assertNotNull(store.recovery.value)
        store.addMark(book.id, point.locator, true, "Portable annotation")
        store.assignShelves(setOf(book.id), listOf("English", "Study")); store.addShelf("Empty shelf")
        ReaderCursorStore(context).write(book.id, "speech", point); ReaderCursorStore(context).write(book.id, "guided", point)
        val ai = AiListeningPoint(point.sectionIndex, point.wordIndex, "a".repeat(64), 123)
        AiListeningStore(context).write(book.id, ai)
        GuidedOptions(mode = "Line", words = 6, punctuation = true, countdown = 3, practiceMinutes = 5).save(context)
        SpeechSettings(sentencePauseMillis = 100, paragraphPauseMillis = 300, navigationUnit = "line").save(context)
        context.getSharedPreferences("speech", 0).edit().putBoolean("follow", false).commit()
        val bytes = ByteArrayOutputStream(); LibraryBackup(context, store).exportTo(bytes, true)
        val destination = isolated(); val restored = LibraryStore(destination, BookEngine(destination))
        LibraryBackup(destination, restored).restoreFrom(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("Line", GuidedOptions.load(destination).mode)
        assertEquals(6, GuidedOptions.load(destination).words)
        assertEquals(300, SpeechSettings.load(destination).paragraphPauseMillis)
        assertTrue(destination.getSharedPreferences("speech", 0).getBoolean("follow", false))
        val saved = restored.books.value.single()
        assertEquals("Portable annotation", saved.highlights.single().note)
        assertEquals(listOf("English", "Study"), saved.shelves)
        assertTrue("Empty shelf" in restored.shelves.value)
        assertEquals(point, ReaderCursorStore(destination).read(book.id, "speech"))
        assertEquals(point, ReaderCursorStore(destination).read(book.id, "guided"))
        assertEquals(ai, AiListeningStore(destination).read(book.id))
        assertTrue(File(context.filesDir, "library/library-v1-preserved.json").isFile)
    }
    @Test fun draftAndRequestedWpmLevelsSurviveRotation() {
        val book = runBlocking { app.library.importSample() }
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            scenario.onActivity { activity ->
                val model = (ReaderActivity::class.java.getDeclaredField("model$" + "delegate").apply { isAccessible = true }.get(activity) as Lazy<*>).value as ReaderModel
                model.notePosition = model.location.value
                model.noteDraft = "Draft retained through rotation"
            }
            assertTrue(device.wait(Until.hasObject(By.text("Draft retained through rotation")), 10000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Draft retained through rotation")), 15000))
            device.findObject(By.text("Cancel")).click(); device.waitForIdle()
            device.findObject(By.text("Guided reading")).click()
            repeat(2) { device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN, .45f) }
            for (speed in listOf(150, 200, 225, 250, 275, 300, 350, 400, 450, 500)) assertTrue("Missing $speed", device.wait(Until.hasObject(By.text(speed.toString())), 10000))
            device.takeScreenshot(File(app.filesDir, "v6-guided-presets.png"))
        }
    }
    @Test fun visibleAnchorExcludesHiddenTextAndStorageFailurePausesOnce() = runBlocking {
        val book = app.library.importSample()
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java).putExtra("book", book.id)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Save bookmark")), 15000))
            lateinit var fragment: EpubNavigatorFragment
            lateinit var guide: GuidedReading
            scenario.onActivity { activity ->
                fragment = navigator(activity)
                guide = (ReaderActivity::class.java.getDeclaredField("guided$" + "delegate").apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>).value as GuidedReading
            }
            withContext(Dispatchers.Main) {
                fragment.evaluateJavascript("(function(){let p=document.createElement('p');p.id='hidden-audit';p.style.visibility='hidden';p.textContent='INVISIBLE_AUDIT_WORD';document.body.prepend(p);})()")
                val anchor = fragment.visibleReadingAnchor()
                assertNotEquals("INVISIBLE_AUDIT_WORD", anchor?.text?.highlight)
                guide.prepare()
            }
            waitFor { guide.ready.value }
            val stateFile = File(app.filesDir, "reader-state/${book.id}-guided.json")
            val saved = GuidedProgress.exclusive { stateFile.takeIf { it.isFile }?.readBytes() }
            try {
                GuidedProgress.exclusive { check(stateFile.parentFile!!.setWritable(false, false)) }
                withContext(Dispatchers.Main) { guide.start(fragment.currentLocator.value, fromHere = true) }
                waitFor { guide.storageFailed.value }
                assertFalse(guide.running.value)
                delay(1200)
                assertTrue(guide.storageFailed.value); assertFalse(guide.running.value)
            } finally {
                withContext(Dispatchers.Main) { guide.pause() }
                GuidedProgress.exclusive { check(stateFile.parentFile!!.setWritable(true, true)); stateFile.delete(); saved?.let { stateFile.writeBytes(it) } }
            }
        }
    }
}
