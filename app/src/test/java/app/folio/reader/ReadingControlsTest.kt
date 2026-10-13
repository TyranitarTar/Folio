package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingControlsTest {
    @Test fun navigationZonesRespectDirectionAndInversion() {
        val settings = NavigationSettings()
        assertEquals(TapAction.PREVIOUS, settings.action(.1f, .5f))
        assertEquals(TapAction.NEXT, settings.action(.9f, .5f))
        assertEquals(TapAction.MENU, settings.action(.5f, .5f))
        assertEquals(TapAction.NEXT, settings.action(.1f, .5f, rtl = true))
        assertEquals(TapAction.NEXT, settings.copy(inversion = "Horizontal").action(.1f, .5f))
        assertEquals(TapAction.MENU, settings.copy(layout = "Disabled").action(.9f, .5f))
        val l = settings.copy(layout = "L shaped")
        assertEquals(TapAction.PREVIOUS, l.action(.9f, .1f))
        assertEquals(TapAction.NEXT, l.action(.1f, .9f))
        assertEquals(TapAction.PREVIOUS, l.copy(inversion = "Vertical").action(.1f, .9f))
        val kindle = settings.copy(layout = "Kindle-ish")
        assertEquals(TapAction.MENU, kindle.action(.9f, .1f))
        assertEquals(TapAction.NEXT, kindle.action(.5f, .5f))
        val edge = settings.copy(layout = "Edge")
        assertEquals(TapAction.NEXT, edge.action(.1f, .5f))
        assertEquals(TapAction.PREVIOUS, edge.action(.5f, .9f))
    }
    @Test fun timingUsesWordsOrActualCharactersRatherThanPixels() {
        assertEquals(300L, Pace("WPM", 200.0).interval(3))
        assertEquals(300L, Pace("WPM", 200.0).interval(20))
        assertEquals(200L, Pace("CPS", 20.0).interval(4))
        assertEquals(600L, Pace("CPS", 20.0).interval(12))
        assertEquals(100L, Pace("WPM", 600.0).interval(6))
    }
}
