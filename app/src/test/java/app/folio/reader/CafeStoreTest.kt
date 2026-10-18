package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CafeStoreTest {
    private lateinit var context: Context
    private lateinit var store: CafeStore
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext<FolioApp>()
        context.deleteDatabase("cat-cafe.db")
        store = CafeStore(context); store.load(); store.setEnabled(true)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase("cat-cafe.db") }
    @Test fun concurrentRetryChargesOnceAndSurvivesReopening() = runBlocking {
        coroutineScope { (0..7).map { async(Dispatchers.IO) { store.redeem(false, CafeAction.BOOKMARK, "same-purchase") } }.awaitAll().forEach { assertTrue(it) } }
        assertEquals(9, store.profiles.value.normal.balance)
        assertEquals(3, store.profiles.value.normal.spent)
        assertEquals(1, store.profiles.value.normal.redemptions)
        store.close(); store = CafeStore(context); store.load()
        assertEquals(9, store.profiles.value.normal.balance)
        assertTrue(store.redeem(false, CafeAction.BOOKMARK, "same-purchase"))
        assertEquals(1, store.profiles.value.normal.redemptions)
    }
    @Test fun unaffordablePurchaseDoesNotChangeProgressOrConsumeReceipt() = runBlocking {
        repeat(12) { assertTrue(store.redeem(false, CafeAction.TREAT, "purchase:$it")) }
        val before = store.profiles.value.normal
        assertFalse(store.redeem(false, CafeAction.TREAT, "retry-later"))
        assertEquals(before, store.profiles.value.normal)
        repeat(4) { store.reading("time", it + 1L, 15_000) }
        assertTrue(store.redeem(false, CafeAction.TREAT, "retry-later"))
        assertEquals(0, store.profiles.value.normal.balance)
    }
    @Test fun testToolsStoriesAndResetNeverChangeNormalSave() = runBlocking {
        val normal = store.export()
        store.testCounters(20, 25, 1000)
        assertEquals(63, store.profiles.value.test.unlocked)
        assertTrue(store.openScene(true, 5))
        store.page(true, 5, MisoStory.chapters[5].lastPage, 1)
        store.finishScene(true, 5)
        store.redeem(true, CafeAction.PAW, "test-purchase")
        store.resetTest()
        assertEquals(normal, store.export())
        assertEquals(1, store.profiles.value.test.unlocked)
        assertEquals(0, store.profiles.value.test.read)
    }
    @Test fun bookmarksAndChoiceSurvivePauseReplayCostsNothing() = runBlocking {
        assertTrue(store.openScene(false, 0))
        store.page(false, 0, 4, 1)
        store.close(); store = CafeStore(context); store.load()
        assertTrue(store.openScene(false, 0))
        assertEquals(4, store.profiles.value.normal.page)
        assertEquals(1, store.profiles.value.normal.choice)
        store.page(false, 0, MisoStory.chapters[0].lastPage, 1); store.finishScene(false, 0)
        assertTrue(store.profiles.value.normal.completed(0))
        val before = store.profiles.value.normal.balance
        store.openScene(false, 0, replay = true)
        assertEquals(0, store.profiles.value.normal.page)
        assertEquals(before, store.profiles.value.normal.balance)
        assertFalse(store.openScene(false, 5))
    }
    @Test fun spendingUnlocksPermanentlyWithoutUnspentBalanceRequirement() = runBlocking {
        repeat(10) { store.redeem(false, CafeAction.TREAT, "give:$it") }
        val state = store.profiles.value.normal
        assertEquals(2, state.balance)
        assertTrue(state.available(3))
        assertFalse(state.available(4))
        store.setEnabled(false); store.setEnabled(true)
        assertEquals(2, store.profiles.value.normal.balance)
        assertTrue(store.profiles.value.normal.available(3))
    }
    @Test fun rewardsUseCumulativeWallTimeAndRejectDuplicateOrOlderBatches() = runBlocking {
        store.reading("session", 1, 15_000)
        store.reading("session", 1, 15_000)
        store.reading("session", 2, 15_000)
        store.reading("session", 1, 15_000)
        store.reading("session", 3, 15_000)
        store.reading("session", 4, 15_000)
        assertEquals(60_000, store.profiles.value.normal.readingMillis)
        assertEquals(13, store.profiles.value.normal.balance)
        store.setEnabled(false); store.reading("session", 5, 15_000)
        store.setEnabled(true); store.reading("session", 5, 15_000)
        assertEquals(60_000, store.profiles.value.normal.readingMillis)
    }
    @Test fun restoreReplacesWalletAndRejectsMalformedStateBeforeMutation() = runBlocking {
        val backup = store.export()
        store.redeem(false, CafeAction.TREAT, "one")
        store.restore(backup); assertEquals(12, store.profiles.value.normal.balance)
        store.restore(backup); assertEquals(12, store.profiles.value.normal.balance)
        val invalid = JSONObject(backup).also { it.getJSONObject("normal").put("balance", -1) }.toString()
        try { store.restore(invalid); fail("Invalid backup was accepted") } catch(_: IllegalArgumentException) { }
        assertEquals(backup, store.export())
        val json = JSONObject(backup)
        assertFalse(json.has("test"))
    }
    @Test fun everyStoryBranchHasFiniteCompleteTextAndValidPageBoundaries() {
        assertEquals(6, MisoStory.chapters.size)
        MisoStory.chapters.forEach { chapter ->
            assertEquals(2, chapter.choices.size)
            chapter.choices.indices.forEach { choice ->
                (0..chapter.lastPage).forEach { page -> assertTrue(chapter.text(page, choice).isNotBlank()) }
                assertEquals(chapter.ending.last(), chapter.text(chapter.lastPage, choice))
            }
        }
    }
}
