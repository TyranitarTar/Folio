package app.folio.reader

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PaginationTest {
    @Test fun initialZeroViewportBindsOnceAfterLayoutAndLaterReflowIsRejected() {
        val viewport=PaginationViewport()
        assertEquals(PaginationViewport.Status.WAIT,viewport.inspect(0,0,false,true))
        assertEquals(PaginationViewport.Status.WAIT,viewport.inspect(1080,1720,false,false))
        assertEquals(PaginationViewport.Status.WAIT,viewport.inspect(1080,1720,true,true))
        assertEquals(0,viewport.width)
        assertEquals(PaginationViewport.Status.MATCH,viewport.inspect(1080,1720,true,false))
        assertEquals(1080,viewport.width); assertEquals(1720,viewport.height)
        assertEquals(PaginationViewport.Status.STALE,viewport.inspect(1080,1680,true,false))
        viewport.reset(1720,1080)
        assertEquals(PaginationViewport.Status.STALE,viewport.inspect(1080,1720,true,false))
        assertEquals(PaginationViewport.Status.MATCH,viewport.inspect(1720,1080,true,false))
        viewport.reset(0,1720)
        assertEquals(0,viewport.height)
        assertEquals(PaginationViewport.Status.MATCH,viewport.inspect(1080,1720,true,false))
    }
    @Test fun fullGeometryMustSettleAfterFontsAndImagesEvenWhenRoundedCountDoesNotChange() {
        val settle=PaginationSettler()
        val loaded=PaginationSample(8,0,true,"9600|1200|fontReady|imageReady")
        assertFalse(settle.accept(loaded.copy(ready=false),0))
        assertFalse(settle.accept(loaded,100))
        assertFalse(settle.accept(loaded,125))
        assertFalse(settle.accept(loaded,175))
        // Font reflow can change width while staying within the same rounded page count.
        val changed=loaded.copy(geometry="9595|1200|fontReady|imageReady")
        assertFalse(settle.accept(changed,180))
        assertFalse(settle.accept(changed,230))
        assertTrue(settle.accept(changed,260))
        assertFalse(settle.accept(changed.copy(ready=false),270))
        assertFalse(settle.accept(changed,300))
        assertTrue(settle.accept(changed,380))
    }
    @Test fun backgroundOrNeuralPauseInvalidatesOldSettlingEvidence() {
        val settle=PaginationSettler()
        val geometry=PaginationSample(7,2,true,"same-ready-layout")
        assertFalse(settle.accept(geometry,0))
        settle.reset()
        assertFalse(settle.accept(geometry,60000))
        assertTrue(settle.accept(geometry,60080))
    }
    @Test fun partialLabelsNeverPresentChapterCountsAsWholeBookTotals() {
        assertEquals("Page 2 of 8 · chapter · 2/3 counted",PaginationCounts.label(intArrayOf(3,8,0),1,1,false))
        assertEquals("Calculating pages…",PaginationCounts.label(intArrayOf(3,8,0),2,50,false))
        assertEquals("Page 5 of 13",PaginationCounts.label(intArrayOf(3,8,2),1,1,false))
        assertEquals("Screen 11 of 13",PaginationCounts.label(intArrayOf(3,8,2),1,99,true))
        assertEquals("Page 3 of 3",PaginationCounts.label(intArrayOf(1,1,1),2,999,false))
        assertEquals("Page 2 of 3000000000",PaginationCounts.label(IntArray(30000){100000},0,1,false))
    }
    @Test fun lateDiskCacheCannotOverwriteNewerVerifiedVisibleGeometry() {
        assertArrayEquals(intArrayOf(4,9,6),PaginationCounts.merge(intArrayOf(4,7,6),intArrayOf(0,9,0)))
        assertArrayEquals(intArrayOf(4,9,6),PaginationCounts.merge(intArrayOf(4,7,6),intArrayOf(-1,9,100001)))
    }
    private fun cache(): AtomicFile = AtomicFile(File(Files.createTempDirectory("folio-pagination").toFile(),"book-v3-layout.json"))
    @Test fun existingV3CacheIsReadWithoutSchemaMigrationAndRejectsCorruptCounts() = runBlocking {
        val file=cache()
        file.baseFile.writeText("""{"EPUB/a.xhtml":4,"EPUB/b.xhtml":9,"float.xhtml":2.5,"huge.xhtml":100001,"negative.xhtml":-1,"text.xhtml":"8"}""")
        assertArrayEquals(intArrayOf(4,9,0,0,0,0,0),PaginationCache.read(file,listOf("EPUB/a.xhtml","EPUB/b.xhtml","float.xhtml","huge.xhtml","negative.xhtml","text.xhtml","missing.xhtml")))
        assertFalse(File(file.baseFile.path+".new").exists())
    }
    @Test fun partialFlushPreservesPreviouslyCountedChaptersAndLatestGeometry() = runBlocking {
        val file=cache(); val hrefs=listOf("a.xhtml","b.xhtml","c.xhtml")
        PaginationCache.write(PaginationCacheSnapshot(file,hrefs,intArrayOf(3,7,0)))
        PaginationCache.write(PaginationCacheSnapshot(file,hrefs,intArrayOf(0,8,5)))
        assertArrayEquals(intArrayOf(3,8,5),PaginationCache.read(file,hrefs))
        assertEquals(setOf("a.xhtml","b.xhtml","c.xhtml"),org.json.JSONObject(file.baseFile.readText()).keys().asSequence().toSet())
    }
    @Test fun interruptedAtomicCacheAndOversizedCacheRemainRecoverable() = runBlocking {
        val file=cache()
        File(file.baseFile.path+".bak").writeText("""{"chapter.xhtml":6}""")
        file.baseFile.writeText("broken")
        assertArrayEquals(intArrayOf(6),PaginationCache.read(file,listOf("chapter.xhtml")))
        file.baseFile.writeText(" ".repeat(2_000_001))
        assertArrayEquals(intArrayOf(0),PaginationCache.read(file,listOf("chapter.xhtml")))
        PaginationCache.write(PaginationCacheSnapshot(file,listOf("chapter.xhtml"),intArrayOf(7)))
        assertArrayEquals(intArrayOf(7),PaginationCache.read(file,listOf("chapter.xhtml")))
    }
}
