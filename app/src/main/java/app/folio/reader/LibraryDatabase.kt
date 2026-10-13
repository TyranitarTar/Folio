package app.folio.reader

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.DatabaseErrorHandler
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Metadata, annotations and frequently changing progress have independent transactional rows. */
internal class LibraryDatabase(file: File) {
    private val db = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null, DatabaseErrorHandler { damaged ->
        // Android's default handler deletes corrupt databases. Retain them for explicit recovery instead.
        runCatching { damaged.close() }
    })
    init {
        db.execSQL("PRAGMA foreign_keys=ON")
        require(db.version <= 1) { "This library needs a newer version of Folio." }
        if (db.version == 0) transaction {
            db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY, metadata TEXT NOT NULL)")
            db.execSQL("CREATE TABLE progress (book TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE, locator TEXT, fraction REAL NOT NULL, visited INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE marks (book TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE, id TEXT NOT NULL, highlight INTEGER NOT NULL, ordinal INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(book,id))")
            db.execSQL("CREATE TABLE shelves (name TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.version = 1
        }
    }
    fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    fun state(key: String): String? = db.rawQuery("SELECT value FROM state WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun putState(key: String, value: String?) {
        if (value == null) db.delete("state", "key=?", arrayOf(key))
        else db.insertWithOnConflict("state", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun migrated() = db.rawQuery("SELECT value FROM state WHERE key='migration'", null).use { it.moveToFirst() }
    fun finishMigration() { db.execSQL("INSERT OR REPLACE INTO state VALUES ('migration','1')") }
    fun read(rejected: (String) -> Unit): List<Book> {
        val books = mutableListOf<Book>()
        db.rawQuery("SELECT b.id,b.metadata,p.locator,p.fraction,p.visited FROM books b LEFT JOIN progress p ON b.id=p.book ORDER BY b.rowid", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                runCatching {
                    val json = JSONObject(cursor.getString(1)).put("id", id)
                    if (!cursor.isNull(2)) json.put("locator", JSONObject(cursor.getString(2)))
                    json.put("progress", cursor.getDouble(3)).put("lastRead", cursor.getLong(4))
                    val bookmarks = JSONArray(); val highlights = JSONArray()
                    db.rawQuery("SELECT highlight,data FROM marks WHERE book=? ORDER BY ordinal", arrayOf(id)).use { marks ->
                        while (marks.moveToNext()) runCatching {
                            val mark = Mark.from(JSONObject(marks.getString(1)))
                            require(mark.position() != null)
                            (if (marks.getInt(0) == 1) highlights else bookmarks).put(mark.json())
                        }.onFailure { rejected("An annotation in $id could not be read.") }
                    }
                    json.put("bookmarks", bookmarks).put("highlights", highlights)
                    books.add(Book.from(json))
                }.onFailure { rejected("Book record $id could not be read.") }
            }
        }
        return books
    }
    fun put(book: Book, previous: Book? = null) {
        if (previous == null || book.copy(locator = previous.locator, progress = previous.progress, lastRead = previous.lastRead,
                bookmarks = previous.bookmarks, highlights = previous.highlights) != previous) {
            val meta = book.json().apply { remove("locator"); remove("progress"); remove("lastRead"); remove("bookmarks"); remove("highlights") }
            // UPDATE preserves foreign-key children; REPLACE would delete them.
            val values = ContentValues().apply { put("id", book.id); put("metadata", meta.toString()) }
            if (db.update("books", values, "id=?", arrayOf(book.id)) == 0) db.insertOrThrow("books", null, values)
        }
        if (previous == null || book.locator != previous.locator || book.progress != previous.progress || book.lastRead != previous.lastRead) {
            val values = ContentValues().apply { put("book", book.id); put("locator", book.locator); put("fraction", book.progress); put("visited", book.lastRead) }
            db.insertWithOnConflict("progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        if (previous == null || book.bookmarks != previous.bookmarks || book.highlights != previous.highlights) {
            db.delete("marks", "book=?", arrayOf(book.id))
            listOf(false to book.bookmarks, true to book.highlights).forEach { (highlight, marks) -> marks.forEachIndexed { index, mark ->
                db.insertOrThrow("marks", null, ContentValues().apply {
                    put("book", book.id); put("id", mark.id); put("highlight", if (highlight) 1 else 0); put("ordinal", index); put("data", mark.json().toString())
                })
            } }
        }
        book.shelves.forEach(::addShelf)
    }
    fun remove(id: String) { db.delete("books", "id=?", arrayOf(id)) }
    fun shelves(): List<String> = db.rawQuery("SELECT name FROM shelves ORDER BY name COLLATE NOCASE", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    fun addShelf(name: String) { db.insertWithOnConflict("shelves", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun removeShelf(name: String) { db.delete("shelves", "name=?", arrayOf(name)) }
    fun close() = db.close()
}
