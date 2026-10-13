package app.folio.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GuidedNavigationTest {
    @Test fun sentenceAndParagraphSkipAcrossRealExtractedTextAndFocusModes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FolioApp>()
        val store = LibraryStore(app, BookEngine(app)); val book = store.importSample(); val pub = store.engine.open(store.file(book.id))
        val text = try { BookText.load(pub, book.id) } finally { pub.close() }
        for (unit in listOf("sentence", "paragraph")) {
            val point = text.sections.indices.firstNotNullOf { section -> text.sections[section].words.indices.firstNotNullOfOrNull { word ->
                text.cursor(section, word)?.takeIf { c -> val r = SpeechNavigation.range(text, c, unit)!!; r.end-r.first > 3 && r.end < text.sections[section].words.size }
            } }
            val bounds = SpeechNavigation.range(text, point, unit)!!
            val middle = text.cursor(point.sectionIndex, bounds.first+2)!!
            val next = SpeechNavigation.target(text,middle,1,unit,false)!!
            assertEquals(bounds.end,next.wordIndex)
            val replay = SpeechNavigation.target(text,middle,-1,unit,true)!!; assertEquals(bounds.first,replay.wordIndex)
            val previous = SpeechNavigation.target(text,next,-1,unit,false)!!; assertEquals(bounds.first,previous.wordIndex)
            for (mode in listOf("Window","Groups","Line")) assertEquals(unit,GuidedOptions(mode=mode,navigationUnit=unit).normalized().navigationUnit)
        }
        Unit
    }
    @Test fun skipUnitIsRememberedSeparatelyFromHighlightModeAndLegacySettingsDefaultToSentence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pace",0).edit().clear().commit()
        assertEquals("sentence",GuidedOptions.load(context).navigationUnit)
        GuidedOptions(mode="Line",navigationUnit="paragraph").save(context)
        assertEquals("paragraph",GuidedOptions.load(context).navigationUnit)
        assertEquals("Line",GuidedOptions.load(context).mode)
        assertEquals("sentence",GuidedOptions(navigationUnit="word").normalized().navigationUnit)
    }
}
