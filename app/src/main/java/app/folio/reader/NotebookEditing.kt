package app.folio.reader

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal object NotebookEditing {
    fun begin(text:String):TextFieldValue {
        // Near the storage limit, leave room for deleting existing text instead of an uneditable extra bullet.
        if(text.length>BookNotebookStore.MAX_CHARS-4)return TextFieldValue(text,TextRange(text.length))
        val ready=when{ text.isEmpty()->"- ";text.endsWith("\n- ")->text;text.endsWith("\n")->text+"- ";else->text+"\n- " }
        return TextFieldValue(ready,TextRange(ready.length))
    }
    fun document(text:String,original:String?=null):String=when {
        original!=null && text==begin(original).text->original
        text=="- "->""
        else->text.removeSuffix("\n- ")
    }
    fun edit(before:TextFieldValue,after:TextFieldValue):TextFieldValue {
        val at=before.selection.start
        if(!before.selection.collapsed || after.text.length!=before.text.length+1 || at>before.text.length ||
            after.text.getOrNull(at)!='\n' || after.text.removeRange(at,at+1)!=before.text)return after
        val start=before.text.lastIndexOf('\n',at-1)+1
        val line=before.text.substring(start,at)
        return when {
            line=="- "->TextFieldValue(after.text.removeRange(start,start+2),TextRange((at-1).coerceAtLeast(0)))
            line.startsWith("- ") && line.drop(2).isNotBlank()->TextFieldValue(after.text.substring(0,at+1)+"- "+after.text.substring(at+1),TextRange(at+3))
            else->after
        }
    }
}
