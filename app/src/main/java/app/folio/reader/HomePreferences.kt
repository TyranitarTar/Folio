package app.folio.reader

import android.content.Context
import org.json.JSONArray

data class HomePreferences(val layout: String = "Comfortable", val columns: Int = 3,
    val sort: String = "Last read", val descending: Boolean = true, val status: String = "All",
    val annotated: Boolean = false, val showProgress: Boolean = true, val theme: String = "System",
    val shelves: List<String> = emptyList()) {
    fun save(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
        .putString("layout", layout).putInt("columns", columns.coerceIn(2, 6)).putString("sort", sort)
        .putBoolean("descending", descending).putString("status", status).putBoolean("annotated", annotated)
        .putBoolean("showProgress", showProgress).putString("theme", theme)
        .putString("shelves", JSONArray(shelves).toString()).apply()
    companion object {
        fun load(context: Context): HomePreferences {
            val p = context.getSharedPreferences("home", Context.MODE_PRIVATE)
            fun choice(key: String, fallback: String, values: List<String>) = p.getString(key, fallback).takeIf { it in values } ?: fallback
            val shelves = runCatching { JSONArray(p.getString("shelves", "[]")).let { a ->
                (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() && it.length <= 40 && it != "All books" }.distinct()
            } }.getOrDefault(emptyList())
            return HomePreferences(choice("layout", "Comfortable", listOf("Comfortable", "Compact", "List")),
                p.getInt("columns", 3).coerceIn(2, 6), choice("sort", "Last read", listOf("Last read", "Date added", "Title", "Author")),
                p.getBoolean("descending", true), choice("status", "All", listOf("All", "Unread", "Reading", "Finished")),
                p.getBoolean("annotated", false), p.getBoolean("showProgress", true),
                choice("theme", "System", listOf("System", "Light", "Dark")), shelves)
        }
    }
}

internal fun HomePreferences.visibleBooks(books: List<Book>, query: String, shelf: String): List<Book> {
    val selected = books.filter { book ->
        (query.isBlank() || "${book.title} ${book.author}".contains(query, true)) &&
            (shelf == "All books" || shelf in book.shelves) &&
            (!annotated || book.bookmarks.isNotEmpty() || book.highlights.isNotEmpty()) &&
            when (status) {
                "Unread" -> book.lastRead == 0L
                "Reading" -> book.lastRead > 0L && book.progress < .99
                "Finished" -> book.progress >= .99
                else -> true
            }
    }
    val sorted = when (sort) {
        "Title" -> selected.sortedWith(compareBy<Book> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
        "Author" -> selected.sortedWith(compareBy<Book> { it.author.lowercase(java.util.Locale.ROOT) }.thenBy { it.title }.thenBy { it.id })
        "Date added" -> selected.sortedWith(compareBy<Book> { it.added }.thenBy { it.id })
        else -> selected.sortedWith(compareBy<Book> { it.lastRead }.thenBy { it.added }.thenBy { it.id })
    }
    return if (descending) sorted.reversed() else sorted
}
