package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiListeningStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun backendLanguageAndVoiceHaveSeparateAudioKeysWithoutInvalidatingKokoro() {
        val phrase = "The rain stopped."
        val legacy = AiListeningStore.key(phrase, 21, 1f)
        assertEquals("debeb18ef8a0e774295904188f23d410eb0960de71d16ca41aac34f2d0264a58", legacy)
        val keys = listOf(legacy,
            AiListeningStore.key(phrase, 0, 1f, "supertonic", "en"),
            AiListeningStore.key(phrase, 0, 1f, "supertonic", "da"),
            AiListeningStore.key(phrase, 1, 1f, "supertonic", "en"),
            AiListeningStore.key(phrase, 0, 1.25f, "supertonic", "en"))
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(legacy, SpeechSettings(backend = "kokoro").audioKey(phrase))
    }
    @Test fun cacheEvictionAndCorruptionNeverDiscardTheListeningOffset() {
        val book = "b".repeat(64)
        val key = AiListeningStore.key("The rain stopped.", 21, 1f)
        val store = AiListeningStore(context)
        val samples = floatArrayOf(.1f, -.1f, .2f)
        store.saveClip(key, AiAudioClip(samples))
        store.write(book, AiListeningPoint(3, 42, key, 2))
        assertArrayEquals(samples, store.loadClip(key)!!.samples, 0f)
        File(context.cacheDir, "ai-audio/$key.pcm").writeText("broken")
        assertNull(store.loadClip(key))
        assertEquals(2, store.read(book)!!.playedSamples)
        assertEquals(42, store.read(book)!!.word)
        assertNotEquals(key, AiListeningStore.key("The rain stopped.", 3, 1f))
        assertNotEquals(key, AiListeningStore.key("The rain stopped.", 21, 1.25f))
        store.forget(book)
        assertNull(store.read(book))
    }
    @Test fun malformedSavedPositionIsRetainedAndReported() {
        val book = "c".repeat(64)
        val file = File(context.filesDir, "reader-state/$book-ai.json").apply { parentFile!!.mkdirs(); writeText("{broken") }
        val error = runCatching { AiListeningStore(context).read(book) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("{broken", file.readText())
    }
}
