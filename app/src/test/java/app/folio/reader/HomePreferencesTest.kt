package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun displaySortingFiltersShelvesAndThemeSurviveReload() {
        val prefs = HomePreferences(layout = "List", columns = 4, sort = "Author", descending = false,
            status = "Reading", annotated = true, showProgress = false, theme = "Dark", shelves = listOf("Favourites", "Dansk"))
        prefs.save(context)
        assertEquals(prefs, HomePreferences.load(context))
    }
    @Test fun legacyBookWithoutShelvesKeepsPositionsAndAnnotations() {
        val legacy = JSONObject("""{"id":"${"a".repeat(64)}","title":"Legacy","author":"Writer","added":1,"lastRead":2,"progress":0.8,"bookmarks":[],"highlights":[]}""")
        val book = Book.from(legacy)
        assertTrue(book.shelves.isEmpty())
        assertEquals(.8, book.progress, 0.0)
        assertEquals(book, Book.from(book.json()))
    }
    @Test fun shelfStatusAnnotationAndSearchFiltersComposeWithoutDroppingOtherBooks() {
        val unread = Book("a".repeat(64), "Alpha", "Writer", 1)
        val reading = Book("b".repeat(64), "Beta", "Author", 2, lastRead = 3, progress = .4, shelves = listOf("Favourites"))
        val finished = Book("c".repeat(64), "Gamma", "Author", 3, lastRead = 4, progress = 1.0)
        val all = listOf(unread, reading, finished)
        assertEquals(listOf(reading), HomePreferences(status = "Reading").visibleBooks(all, "author", "Favourites"))
        assertEquals(listOf(unread), HomePreferences(status = "Unread").visibleBooks(all, "", "All books"))
        assertTrue(HomePreferences(annotated = true).visibleBooks(all, "", "All books").isEmpty())
        assertEquals(all, HomePreferences(sort = "Title", descending = false).visibleBooks(all, "", "All books"))
        assertEquals(3, all.size)
    }
}
