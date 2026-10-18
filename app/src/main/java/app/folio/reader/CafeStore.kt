package app.folio.reader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CafeState(
    val enabled: Boolean = false, val balance: Long = 12, val spent: Long = 0,
    val redemptions: Long = 0, val readingMillis: Long = 0, val unlocked: Int = 1,
    val read: Int = 0, val scene: Int = -1, val page: Int = 0, val choice: Int = 0,
    val choices: Map<Int, Int> = emptyMap(), val reducedMotion: Boolean = false
) {
    fun available(id: Int) = unlocked and (1 shl id) != 0
    fun completed(id: Int) = read and (1 shl id) != 0
    fun evaluate() = copy(unlocked = unlocked or MisoStory.chapters.filter {
        redemptions >= it.visits && spent >= it.treats
    }.fold(0) { mask, chapter -> mask or (1 shl chapter.id) })
    fun json() = JSONObject().put("enabled", enabled).put("balance", balance).put("spent", spent)
        .put("redemptions", redemptions).put("readingMillis", readingMillis).put("unlocked", unlocked)
        .put("read", read).put("scene", scene).put("page", page).put("choice", choice)
        .put("choices", JSONObject().also { json -> choices.forEach { (id, selected) -> json.put(id.toString(), selected) } })
        .put("reducedMotion", reducedMotion)
    companion object {
        fun from(json: JSONObject): CafeState {
            fun number(key: String, max: Long) = json.getLong(key).also { require(it in 0..max) { "Ugyldig caféværdi: $key" } }
            val scene = json.getInt("scene").also { require(it in -1..5) }
            val page = number("page", 100).toInt()
            require(scene == -1 || page <= MisoStory.chapters[scene].lastPage)
            val choices = json.getJSONObject("choices").let { source -> source.keys().asSequence().associate { key ->
                val id = key.toInt(); val selected = source.getInt(key)
                require(id in 0..5 && selected in 0..1); id to selected
            } }
            val result = CafeState(json.getBoolean("enabled"), number("balance", 10_000_000), number("spent", 10_000_000),
                number("redemptions", 10_000_000), number("readingMillis", 1_000_000_000_000),
                number("unlocked", 63).toInt(), number("read", 63).toInt(), scene, page,
                number("choice", 1).toInt(), choices, json.getBoolean("reducedMotion"))
            require(result.unlocked and 1 == 1 && result.read and result.unlocked == result.read)
            return result
        }
    }
}

internal data class CafeProfiles(val normal: CafeState = CafeState(), val test: CafeState = CafeState(balance = 1000, enabled = true), val ready: Boolean = false)

/** Separate database; every purchase and its progression commit together. No book data is written. */
internal class CafeStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "cat-cafe.db", null, 1) {
    private val lock = Any()
    private val mutable = MutableStateFlow(CafeProfiles())
    val profiles = mutable.asStateFlow()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE receipts (profile TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY(profile,token))")
        db.execSQL("CREATE TABLE reading_sessions (id TEXT PRIMARY KEY, sequence INTEGER NOT NULL)")
        listOf("normal" to CafeState(), "test" to CafeState(enabled = true, balance = 1000)).forEach { (id, state) ->
            db.insertOrThrow("profiles", null, ContentValues().apply { put("id", id); put("data", state.json().toString()) })
        }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun read(db: SQLiteDatabase, id: String): CafeState = db.rawQuery("SELECT data FROM profiles WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst()); CafeState.from(JSONObject(it.getString(0)))
    }
    private fun write(db: SQLiteDatabase, id: String, value: CafeState) {
        check(db.update("profiles", ContentValues().apply { put("data", value.json().toString()) }, "id=?", arrayOf(id)) == 1)
    }
    private fun publish(db: SQLiteDatabase) { mutable.value = CafeProfiles(read(db, "normal"), read(db, "test"), true) }
    suspend fun load() = withContext(Dispatchers.IO) { synchronized(lock) { publish(writableDatabase) } }
    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val db = writableDatabase
            val result = db.run {
                beginTransaction()
                try { block(this).also { setTransactionSuccessful() } } finally { endTransaction() }
            }
            publish(db); result
        }
    }
    private fun id(test: Boolean) = if (test) "test" else "normal"
    suspend fun setEnabled(enabled: Boolean) = transaction { db -> write(db, "normal", read(db, "normal").copy(enabled = enabled)) }
    suspend fun reducedMotion(test: Boolean, enabled: Boolean) = transaction { db ->
        write(db, id(test), read(db, id(test)).copy(reducedMotion = enabled))
    }
    /** Caller retains a token for an animation. Retrying a saved purchase cannot charge twice. */
    suspend fun redeem(test: Boolean, action: CafeAction, token: String): Boolean = transaction { db ->
        require(token.length in 1..100)
        val profile = id(test); val state = read(db, profile)
        val exists = db.rawQuery("SELECT 1 FROM receipts WHERE profile=? AND token=?", arrayOf(profile, token)).use { it.moveToFirst() }
        when {
            exists -> true
            !state.enabled || state.balance < action.cost -> false
            else -> {
                db.insertOrThrow("receipts", null, ContentValues().apply { put("profile", profile); put("token", token) })
                write(db, profile, state.copy(balance = state.balance - action.cost, spent = state.spent + action.cost,
                    redemptions = state.redemptions + 1).evaluate()); true
            }
        }
    }
    /** Wall time only. One session sequence prevents retries from awarding the same interval twice. */
    suspend fun reading(session: String, sequence: Long, millis: Long) = transaction { db ->
        require(millis in 1..15_000 && sequence > 0 && session.length in 1..100)
        val previous = db.rawQuery("SELECT sequence FROM reading_sessions WHERE id=?", arrayOf(session)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
        if (sequence > previous) {
            val state = read(db, "normal")
            if (state.enabled) {
                val total = state.readingMillis + millis
                val earned = total / 60_000 - state.readingMillis / 60_000
                write(db, "normal", state.copy(readingMillis = total, balance = state.balance + earned))
            }
            db.insertWithOnConflict("reading_sessions", null, ContentValues().apply { put("id", session); put("sequence", sequence) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
    suspend fun openScene(test: Boolean, chapter: Int, replay: Boolean = false): Boolean = transaction { db ->
        require(chapter in 0..5)
        val state = read(db, id(test))
        if ((!test && !state.available(chapter)) || !state.enabled) false
        else {
            if (replay || state.scene != chapter) write(db, id(test), state.copy(scene = chapter, page = 0, choice = state.choices[chapter] ?: 0))
            true
        }
    }
    suspend fun page(test: Boolean, chapter: Int, page: Int, choice: Int) = transaction { db ->
        val story = MisoStory.chapters[chapter]
        require(page in 0..story.lastPage && choice in 0..1)
        val state = read(db, id(test)); check(state.scene == chapter)
        write(db, id(test), state.copy(page = page, choice = choice, choices = state.choices + (chapter to choice)))
    }
    suspend fun finishScene(test: Boolean, chapter: Int) = transaction { db ->
        val state = read(db, id(test)); check(state.scene == chapter && state.page == MisoStory.chapters[chapter].lastPage)
        write(db, id(test), state.copy(scene = -1, page = 0, read = state.read or (1 shl chapter), unlocked = state.unlocked or (1 shl chapter)))
    }
    suspend fun testCounters(visits: Long, spent: Long, balance: Long) = transaction { db ->
        require(visits in 0..10000 && spent in 0..10000 && balance in 0..10000)
        write(db, "test", read(db, "test").copy(redemptions = visits, spent = spent, balance = balance, unlocked = 1, read = 0, scene = -1, page = 0).evaluate())
    }
    suspend fun resetTest() = transaction { db ->
        write(db, "test", CafeState(enabled = true, balance = 1000)); db.delete("receipts", "profile=?", arrayOf("test"))
    }
    suspend fun testReadAll() = transaction { db ->
        write(db, "test", read(db, "test").copy(redemptions = 20, spent = 25, unlocked = 63, read = 63, scene = -1, page = 0))
    }
    suspend fun export(): String = withContext(Dispatchers.IO) { synchronized(lock) {
        JSONObject().put("format", "FolioCafe").put("version", 1).put("normal", read(writableDatabase, "normal").json()).toString(2)
    } }
    suspend fun restore(serialized: String) {
        require(serialized.toByteArray(Charsets.UTF_8).size <= 16_384)
        BoundedJson.check(serialized, 8, 128)
        val json = JSONObject(serialized)
        require(json.getString("format") == "FolioCafe" && json.getInt("version") == 1)
        val replacement = CafeState.from(json.getJSONObject("normal"))
        transaction { db -> write(db, "normal", replacement); db.delete("receipts", "profile=?", arrayOf("normal")) }
    }
}
