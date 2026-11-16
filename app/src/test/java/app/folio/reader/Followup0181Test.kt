package app.folio.reader

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class Followup0181Test {
    @Test fun translationEncodesSelectedTextAndTargetsEnglish() {
        val text="  æble & pære? #en bog  "
        val uri=ReaderLookup.english(text)
        assertEquals("https",uri.scheme);assertEquals("translate.google.com",uri.host)
        assertEquals("auto",uri.getQueryParameter("sl"));assertEquals("en",uri.getQueryParameter("tl"))
        assertEquals(text.trim(),uri.getQueryParameter("text"))
    }
    @Test fun newNotesUseChapterAndTimeWithoutSavingEmptyContext() {
        val prefix="[C2 · 03:42] "
        val draft=NotebookEditing.begin("Earlier",prefix)
        assertEquals("Earlier\n- $prefix",draft.text)
        assertEquals("Earlier",NotebookEditing.document(draft.text,"Earlier",prefix))
        val text=draft.text+"A thought"
        val before=TextFieldValue(text,TextRange(text.length))
        val next=NotebookEditing.edit(before,TextFieldValue(text+"\n",TextRange(text.length+1)),"[C2 · 04:01] ")
        assertTrue(next.text.endsWith("\n- [C2 · 04:01] "))
        assertEquals(text,NotebookEditing.document(next.text,"Earlier",prefix))
        val pasted="Two\nordinary paragraphs"
        assertEquals(pasted,NotebookEditing.edit(before,TextFieldValue(pasted),prefix).text)
    }
}
