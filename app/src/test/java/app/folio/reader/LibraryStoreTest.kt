package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
class LibraryStoreTest {
    private lateinit var context: Context
    private lateinit var store: LibraryStore
    @Before fun setup() {
        val base = ApplicationProvider.getApplicationContext<FolioApp>()
        val isolated = java.nio.file.Files.createTempDirectory(base.cacheDir.toPath(), "library-test-").toFile()
        context = object : ContextWrapper(base) { override fun getFilesDir() = isolated }
        store = LibraryStore(context, BookEngine(context))
    }
    @Test fun importDetectsDuplicatesAndPreservesOriginalMetadata() = runBlocking {
        val book = store.importSample()
        val again = store.importSample()
        assertEquals(book.id, again.id)
        assertEquals("The Quiet Hour", book.title)
        assertEquals("Folio", book.author)
        assertEquals(1, store.books.value.size)
        assertTrue(store.file(book.id).isFile)
        assertTrue(store.cover(book.id).isFile)
    }
    @Test fun progressBookmarksAndNotesSurviveReload() = runBlocking {
        val book = store.importSample()
        val publication = store.engine.open(store.file(book.id))
        val positions = publication.positions()
        val position = positions.last()
        store.progress(book.id, position)
        store.addMark(book.id, position, false)
        store.addMark(book.id, position, false)
        store.addMark(book.id, position, true, "Remember this passage")
        publication.close()
        val reloaded = LibraryStore(context, BookEngine(context))
        reloaded.load()
        val saved = reloaded.books.value.single()
        assertEquals(position, saved.position())
        assertEquals(1, saved.bookmarks.size)
        assertEquals("Remember this passage", saved.highlights.single().note)
        assertEquals(position, saved.highlights.single().position())
    }
    @Test fun invalidImportDoesNotDamageExistingLibrary() = runBlocking {
        val book = store.importSample()
        assertTrue(runCatching { store.importStream { ByteArrayInputStream("not an epub".toByteArray()) } }.isFailure)
        assertEquals(listOf(book.id), store.books.value.map { it.id })
        assertFalse(java.io.File(context.filesDir, "library").listFiles()!!.any { it.name.startsWith("import-") })
    }
    @Test fun removeDeletesOnlyTheImportedCopyAndMarks() = runBlocking {
        val book = store.importSample()
        store.removeBook(book.id)
        assertTrue(store.books.value.isEmpty())
        assertFalse(store.file(book.id).exists())
        assertFalse(store.cover(book.id).exists())
        val reloaded = LibraryStore(context, BookEngine(context))
        reloaded.load()
        assertTrue(reloaded.books.value.isEmpty())
        assertNotNull(context.assets.open("sample.epub"))
    }
    @Test fun searchFindsPassagesAndDanishLetters() = runBlocking {
        val book = store.importSample()
        val publication = store.engine.open(store.file(book.id))
        try {
            for (query in listOf("quiet page", "lænestol")) {
                val iterator = publication.search(query)!!
                try {
                    var found = false
                    while (true) {
                        val page = iterator.next().getOrElse { throw AssertionError(it.message) } ?: break
                        if (page.locators.isNotEmpty()) found = true
                    }
                    assertTrue("Missing query: $query", found)
                } finally { iterator.close() }
            }
        } finally { publication.close() }
    }
    @Test fun shelfAssignmentAndRemovalPreserveImportedContentAndProgress() = runBlocking {
        val store = LibraryStore(context, BookEngine(context))
        store.load()
        val book = store.importSample()
        store.update(book.id) { it.copy(progress = .8, lastRead = 42) }
        store.assignShelves(setOf(book.id), listOf("Dansk"))
        val reloaded = LibraryStore(context, BookEngine(context)); reloaded.load()
        assertEquals(listOf("Dansk"), reloaded.books.value.first().shelves)
        assertEquals(.8, reloaded.books.value.first().progress, 0.0)
        assertTrue(reloaded.file(book.id).exists())
        reloaded.deleteShelf("Dansk")
        assertTrue(reloaded.books.value.first().shelves.isEmpty())
        assertEquals(42L, reloaded.books.value.first().lastRead)
        assertTrue(reloaded.file(book.id).exists())
    }
    @Test fun batchRemovalOnlyRemovesSelectedBookRecords() = runBlocking {
        val store = LibraryStore(context, BookEngine(context)); store.load()
        val book = store.importSample()
        store.removeBooks(setOf("f".repeat(64)))
        assertEquals(listOf(book), store.books.value)
        assertTrue(store.file(book.id).exists())
        store.removeBooks(setOf(book.id))
        assertTrue(store.books.value.isEmpty())
        assertFalse(store.file(book.id).exists())
    }

}
