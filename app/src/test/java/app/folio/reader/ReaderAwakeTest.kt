package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReaderAwakeTest {
    @Test fun oldExplicitTrueMigratesToAlwaysAndOtherReadersUseTenMinuteDefault() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("appearance",0)
        prefs.edit().clear().commit()
        assertEquals(ReaderAwakePolicy("timed",10),Appearance.load(context).awakePolicy)
        prefs.edit().putBoolean("awake",true).commit()
        assertEquals("always",Appearance.load(context).awakePolicy.mode)
        prefs.edit().putBoolean("awake",false).commit()
        assertEquals(ReaderAwakePolicy("timed",10),Appearance.load(context).awakePolicy)
        Appearance.load(context).withAwake(ReaderAwakePolicy("device",2)).save(context)
        assertEquals(ReaderAwakePolicy("device",2),Appearance.load(context).awakePolicy)
        prefs.edit().putString("awakeMode","unknown").putInt("awakeMinutes",999).commit()
        assertEquals(ReaderAwakePolicy("timed",10),Appearance.load(context).awakePolicy)
        prefs.edit().clear().commit()
    }
    @Test fun realInputExtendsDeadlineButLayoutRotationAndForegroundingDoNot() {
        val timed=ReaderAwakePolicy()
        val session=ReaderAwakeSession(1000,1000)
        assertTrue(session.protected(timed,600999,true,true,false))
        assertFalse(session.protected(timed,601000,true,true,false))
        session.interacted(500000)
        assertEquals(600000,session.remaining(timed,500000))
        val rotated=ReaderAwakeSession(session.lastInput,700000)
        assertEquals(400000,rotated.remaining(timed,700000))
        assertFalse(rotated.protected(timed,1100000,true,true,false))
        assertFalse(rotated.protected(timed,900000,false,true,true))
        assertFalse(rotated.protected(timed,900000,true,false,true))
        assertEquals(500000,rotated.lastInput)
    }
    @Test fun guidedOverrideExpiresBackToLastRealInputPolicyAndNarrationNeedsNoOverride() {
        val session=ReaderAwakeSession(0,0)
        val device=ReaderAwakePolicy("device")
        assertFalse(session.protected(device,0,true,true,false))
        assertTrue(session.protected(device,900000,true,true,true))
        assertFalse(session.protected(device,900000,true,true,false))
        assertTrue(session.protected(ReaderAwakePolicy(),900000,true,true,true))
        assertFalse(session.protected(ReaderAwakePolicy(),900000,true,true,false))
        assertTrue(session.protected(ReaderAwakePolicy("always"),900000,true,true,false))
        assertFalse(session.protected(ReaderAwakePolicy("always"),900000,false,true,true))
        assertEquals(0,session.lastInput)
    }
    @Test fun timeoutAndKeySettingEditsHaveIdenticalReflowPreferences() {
        val base=Appearance()
        for(mode in ReaderAwakePolicy.modes) for(minutes in ReaderAwakePolicy.presets) {
            val edited=base.withAwake(ReaderAwakePolicy(mode,minutes))
            assertEquals(base.preferences(),edited.preferences())
        }
        assertEquals(base.preferences(),base.copy(volumeKeys=true).preferences())
        assertNotEquals(base.preferences(),base.copy(fontSize=1.7f).preferences())
    }
    @Test fun invalidFutureElapsedTimeAfterRebootResetsRatherThanProtectingForever() {
        val session=ReaderAwakeSession(3000000,50)
        assertEquals(50,session.lastInput)
        assertEquals(600000,session.remaining(ReaderAwakePolicy(),50))
        session.interacted(10)
        assertEquals(50,session.lastInput)
    }
}
