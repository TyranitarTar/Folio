package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BookStatisticsTest {
    private lateinit var context: Context
    @Before fun setup() {
        val app=ApplicationProvider.getApplicationContext<FolioApp>()
        // Keep native/file fixture paths short on Windows. Statistics do not exercise SQLite.
        val root=java.nio.file.Files.createTempDirectory("folio-stat-").toFile()
        context=object:ContextWrapper(app){override fun getFilesDir()=java.io.File(root,"files").apply { mkdirs() };override fun getCacheDir()=java.io.File(root,"cache").apply { mkdirs() }}
    }
    private suspend fun publication(): Pair<org.readium.r2.shared.publication.Publication,String> {
        // Open the exact bundled content directly: catalogue/WAL setup is unrelated to this test.
        val bytes=context.assets.open("sample.epub").use { it.readBytes() }
        val target=java.io.File(context.filesDir,"sample.epub").apply { writeBytes(bytes) }
        val id=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BookEngine(context).open(target) to id
    }
    @Test fun nestedSameResourceChaptersPartitionWordsOnceAndRetainFrontMatter()=runBlocking {
        val (pub,id)=publication()
        try {
            val text=BookText.load(pub);val section=text.sections.indexOfFirst { it.words.size>30 }
            fun entry(title:String,depth:Int,word:Int)=text.cursor(section,word)!!.let { ReadingChapter(title,depth,it.locator,it) }
            val entries=listOf(entry("First",0,2),entry("Nested",1,5),entry("Second",0,20))
            val info=BookStatistics.build(text.copy(chapterEntries=entries),pub)
            assertEquals(text.sections.sumOf { it.words.size },info.totalWords)
            assertEquals(info.totalWords,info.chapters.filter { it.depth==0 }.sumOf { it.words })
            assertEquals(18,info.chapters.single { it.title=="First" }.words)
            assertEquals(15,info.chapters.single { it.title=="Nested" }.words)
            assertTrue(info.chapters.any { it.title=="Front matter / other text"&&it.words>0 })
            val point=text.cursor(section,10)!!
            val before=info.wordOffset(text,point.locator)!!
            assertEquals(text.sections.take(section).sumOf { it.words.size }+10,before)
            assertEquals("First",info.currentChapter(before,point.locator.href.toString())!!.title)
            assertEquals(info.totalWords,BookStatistics.from(info.json()).totalWords)
        } finally { pub.close() }
    }
    @Test fun diskCacheIsCompactAndIndependentOfTypographyAndRejectsDamagedPartitions()=runBlocking {
        val (pub,id)=publication()
        try {
            val text=BookText.load(pub);val info=BookStatisticsStore.prepare(context,id,pub,text)
            val first=BookStatisticsStore.read(context,id)!!
            Appearance(fontSize=1.9f,theme="Night").save(context)
            assertEquals(first.json().toString(),BookStatisticsStore.read(context,id)!!.json().toString())
            assertNull(BookStatisticsStore.read(context,"b".repeat(64)))
            assertFalse(info.json().toString().contains("sentences"))
            val json=JSONObject(info.json().toString());json.put("total",info.totalWords+1)
            assertTrue(runCatching { BookStatistics.from(json) }.isFailure)
            val cache=java.io.File(context.cacheDir,"book-statistics/${id}-v1.json");cache.writeText("broken")
            assertNull(BookStatisticsStore.read(context,id))
        } finally { pub.close() }
    }
    @Test fun missingContentsStillAccountsForEveryWordAndEmptyBookHasZeroTime()=runBlocking {
        val (pub,id)=publication()
        try {
            val text=BookText.load(pub).copy(chapterEntries=emptyList());val info=BookStatistics.build(text,pub)
            assertEquals(1,info.chapters.count { it.words>0 });assertEquals(info.totalWords,info.chapters.single { it.words>0 }.words)
            assertEquals(0,info.remainingMinutes(info.totalWords,300.0))
            val empty=BookStatistics.build(BookText(emptyList()),pub);assertEquals(0,empty.totalWords);assertEquals(0,empty.remainingMinutes(0,300.0))
        } finally { pub.close() }
    }
    @Test fun cachedPageInformationNeverGuessesUnknownPrefixCountsOrBoundaries()=runBlocking {
        val (pub,id)=publication()
        try {
            val text=BookText.load(pub);val target=text.cursor(1,0)!!.locator
            val hrefs=text.sections.map { it.href.toString() };val key=pageBoundaryKey(target)
            assertNull(PageInformation(hrefs=hrefs,counts=List(hrefs.size){if(it==0)0 else 10},boundaries=mapOf(key to 2)).page(target))
            assertNull(PageInformation(hrefs=hrefs,counts=List(hrefs.size){10}).page(target))
            assertEquals(13L,PageInformation(hrefs=hrefs,counts=List(hrefs.size){10},boundaries=mapOf(key to 2)).page(target))
            assertEquals(key,pageBoundaryKey(checkNotNull(Locator.fromJSON(target.toJSON()))))
        } finally { pub.close() }
    }
}
