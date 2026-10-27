package app.folio.reader

import android.content.Context
import org.json.JSONArray

data class HomePreferences(val layout: String = "Comfortable", val columns: Int = 3,
    val sort: String = "Last read", val descending: Boolean = true, val status: String = "All",
    val annotated: Boolean = false, val showProgress: Boolean = true, val theme: String = "System",
    val shelves: List<String> = emptyList()) {
    fun save(context: Context, preferenceName: String = "home") = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
        .putString("layout", layout).putInt("columns", columns.coerceIn(2, 4)).putString("sort", sort)
        .putBoolean("descending", descending).putString("status",libraryStatus(status)).putInt("statusSchema",3).putBoolean("annotated", annotated)
        .putBoolean("showProgress", showProgress).putString("theme", theme)
        .putString("shelves", JSONArray(shelves).toString()).apply()
    companion object {
        fun load(context: Context, preferenceName: String = "home"): HomePreferences {
            val p = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            fun choice(key: String, fallback: String, values: List<String>) = p.getString(key, fallback).takeIf { it in values } ?: fallback
            val shelves = runCatching { JSONArray(p.getString("shelves", "[]")).let { a ->
                (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() && it.length <= 40 && it != "All books" }.distinct()
            } }.getOrDefault(emptyList())
            return HomePreferences(choice("layout", "Comfortable", listOf("Comfortable", "Compact", "List")),
                p.getInt("columns", 3).coerceIn(2, 4), choice("sort", "Last read", listOf("Last read", "Date added", "Title", "Author")),
                p.getBoolean("descending", true), if(p.getInt("statusSchema",1)<2 && p.getString("status","All")=="Unread")"New" else libraryStatus(p.getString("status","All").orEmpty()),
                p.getBoolean("annotated", false), p.getBoolean("showProgress", true),
                choice("theme", "System", listOf("System", "Light", "Dark")), shelves)
        }
    }
}

internal fun HomePreferences.visibleAudiobooks(books: List<Audiobook>, query: String,shelf:String="All books"): List<Audiobook> {
    val selected=books.filter { book ->
        (shelf=="All books" || shelf in book.shelves) && (query.isBlank() || "${book.title} ${book.author}".contains(query,true)) && when(status) {
            "New" -> !book.started && !book.isRead
            "Unread" -> !book.isRead
            "Reading" -> book.started && !book.isRead
            "Read", "Finished" -> book.isRead
            else -> true
        }
    }
    val sorted=when(sort) {
        "Title" -> selected.sortedWith(compareBy<Audiobook>{it.title.lowercase(java.util.Locale.ROOT)}.thenBy{it.id})
        "Author" -> selected.sortedWith(compareBy<Audiobook>{it.author.lowercase(java.util.Locale.ROOT)}.thenBy{it.title}.thenBy{it.id})
        else -> selected.sortedWith(compareBy<Audiobook>{it.lastPlayed}.thenBy{it.id})
    }
    return if(descending)sorted.reversed() else sorted
}

internal val libraryStatuses=listOf("All","New","Reading","Finished")
internal fun libraryStatus(value:String):String=when(value){"Unread"->"Reading";"Read"->"Finished";in libraryStatuses->value;else->"All"}

internal fun HomePreferences.visibleBooks(books: List<Book>, query: String, shelf: String): List<Book> {
    val selected = books.filter { book ->
        (query.isBlank() || "${book.title} ${book.author}".contains(query, true)) &&
            (shelf == "All books" || shelf in book.shelves) &&
            (!annotated || book.bookmarks.isNotEmpty() || book.highlights.isNotEmpty()) &&
            when (status) {
                "New" -> book.lastRead == 0L && !book.isRead
                "Unread", "Unfinished" -> !book.isRead
                "Reading" -> book.lastRead > 0L && !book.isRead
                "Read", "Finished" -> book.isRead
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
