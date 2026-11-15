package app.folio.reader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CafeRoomPreferencesTest {
    @Test fun malformedAndUnknownDiscoveriesCannotPolluteRoomOrWallet() {
        val counts = CafeRoomPreferences.sanitize("""{"eat":2,"drink":-1,"box":1.2,"sofa":100001,"perch":"7","balance":99999,"sleep":4}""")
        assertEquals(setOf("eat", "sleep"), counts.keys().asSequence().toSet())
        assertEquals(2, counts.getInt("eat"))
        assertEquals(0, CafeRoomPreferences.sanitize("broken").length())
        assertEquals(0, CafeRoomPreferences.sanitize(" ".repeat(4097)).length())
    }
    @Test fun settingsAndDiscoveriesHaveTheirOwnNamespaceAndValidateEnums() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = context.getSharedPreferences("cat-cafe", 0)
        legacy.edit().putLong("balance", 123).commit()
        val prefs = CafeRoomPreferences(context)
        prefs.mode = "climbing"; prefs.pace = "sprint"; prefs.ambient = false; prefs.reduced = true
        prefs.mode = "unknown"; prefs.pace = "teleport"
        prefs.saveCounts("""{"tunnel":3,"balance":0}""")
        val reopened = CafeRoomPreferences(context)
        assertEquals("climbing", reopened.mode); assertEquals("sprint", reopened.pace)
        assertFalse(reopened.ambient); assertTrue(reopened.reduced)
        assertEquals(3, reopened.counts().getInt("tunnel")); assertEquals(1, reopened.counts().length())
        assertEquals(123L, legacy.getLong("balance", 0))
    }
    @Test fun onlyPackagedRoomAssetsCanBeReadByTheWebView() {
        val view = CafeRoomView(ApplicationProvider.getApplicationContext())
        assertEquals("text/html", view.asset(Uri.parse("https://folio.local/cafe/index.html")).mimeType)
        listOf("file:///data/data/app.folio.reader/files/library.json", "content://books/one",
            "https://example.com/cafe/index.html", "https://folio.local/cafe/../voice-packs.json",
            "https://folio.local/cafe/%2e%2e/voice-packs.json", "https://folio.local/voice-packs.json",
            "https://folio.local:444/cafe/index.html").forEach {
            assertEquals(it, 404, view.asset(Uri.parse(it)).statusCode)
        }
        assertFalse(view.settings.allowContentAccess); assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.domStorageEnabled); assertTrue(view.settings.blockNetworkLoads)
        view.disposeRoom(); view.disposeRoom()
    }
}
