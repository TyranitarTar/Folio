package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun upgradingRetainsTheOldVoiceAndAddsSafeSupertonicDefaults() {
        context.getSharedPreferences("speech", Context.MODE_PRIVATE).edit().clear()
            .putString("backend", "kokoro").putInt("aiVoice", 3).putFloat("rate", 1.25f).commit()
        val settings = SpeechSettings.load(context)
        assertEquals("kokoro", settings.backend)
        assertEquals(3, settings.aiVoice)
        assertEquals(1.25f, settings.rate, 0f)
        assertEquals(0, settings.supertonicVoice)
        assertEquals("en", settings.supertonicLanguage)
    }
    @Test fun switchingBackendsRetainsEachVoiceAndLanguage() {
        val settings = SpeechSettings(backend = "supertonic", aiVoice = 3, supertonicVoice = 9, supertonicLanguage = "da")
        settings.save(context)
        assertEquals(settings, SpeechSettings.load(context))
        settings.copy(backend = "kokoro").save(context)
        assertEquals("kokoro:3", SpeechSettings.load(context).localVoiceIdentity)
        settings.save(context)
        assertEquals("supertonic:da:9", SpeechSettings.load(context).localVoiceIdentity)
    }
}
