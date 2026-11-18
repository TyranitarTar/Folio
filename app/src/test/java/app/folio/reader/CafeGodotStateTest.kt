package app.folio.reader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CafeGodotStateTest {
    @get:Rule val temp=TemporaryFolder()
    private fun context():Context {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=temp.newFolder()
        val prefix=UUID.randomUUID().toString()
        return object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=root
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(prefix+name,mode)
        }
    }
    @Test fun firstEntryMigratesRoomSettingsAndCountsWithoutChangingOldPreferencesOrWallet() {
        val context=context()
        val old=context.getSharedPreferences("cafe-room-v1",0)
        old.edit().putString("mode","climbing").putString("pace","trot").putBoolean("ambient",false)
            .putBoolean("reduced",true).putString("counts","""{"tunnel":7,"eat":2}""").commit()
        val original=old.all.toMap()
        context.getSharedPreferences("cat-cafe",0).edit().putLong("balance",123).commit()
        val store=CafeGodotState(context);store.initialize()
        val loaded=CafeGodotState(context).load()
        assertEquals("climbing",loaded.getString("mode"));assertEquals("trot",loaded.getString("pace"))
        assertFalse(loaded.getBoolean("ambient"));assertTrue(loaded.getBoolean("reduced"))
        assertEquals(7,loaded.getJSONObject("counts").getInt("tunnel"))
        assertEquals(original,old.all)
        assertEquals(123L,context.getSharedPreferences("cat-cafe",0).getLong("balance",0))
    }
    @Test fun aFreshStoreReadsNewStateAndStaleWritersCannotEraseDiscoveries() {
        val context=context();val store=CafeGodotState(context)
        assertTrue(store.save("""{"version":1,"mode":"playful","pace":"sprint","density":96,"night":true,"counts":{"box":4}}"""))
        assertTrue(CafeGodotState(context).save("""{"mode":"manual","counts":{"box":1,"sleep":2}}"""))
        val reopened=CafeGodotState(context).load()
        assertEquals(4,reopened.getJSONObject("counts").getInt("box"))
        assertEquals(2,reopened.getJSONObject("counts").getInt("sleep"))
        assertEquals("manual",reopened.getString("mode"))
    }
    @Test fun invalidInputCannotPersistUnknownKeysUnsafeActorPositionsOrFractionalCounts() {
        val clean=CafeGodotState.sanitize("""{"mode":"teleport","pace":"warp","density":4096,"ambient":"false","counts":{"eat":2.1,"drink":-1,"box":"3","sleep":4,"balance":999},"at":[99,99],"support":"air","balance":999}""")
        assertEquals("random",clean.getString("mode"));assertEquals("walk",clean.getString("pace"))
        assertEquals(128,clean.getInt("density"));assertTrue(clean.getBoolean("ambient"))
        assertEquals(setOf("sleep"),clean.getJSONObject("counts").keys().asSequence().toSet())
        assertFalse(clean.has("at"));assertFalse(clean.has("balance"));assertFalse(clean.has("support"))
    }
    @Test fun malformedOversizedAndFutureVersionWritesPreserveTheLastDurableDocument() {
        val store=CafeGodotState(context());store.initialize()
        val before=store.file.readBytes()
        assertFalse(store.save("broken"))
        assertFalse(store.save(JSONObject().put("ignored","x".repeat(20000)).toString()))
        assertFalse(store.save("""{"version":2,"counts":{"box":9}}"""))
        assertArrayEquals(before,store.file.readBytes())
    }
}
