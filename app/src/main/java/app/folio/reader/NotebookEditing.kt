package app.folio.reader

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal object NotebookEditing {
    fun begin(text:String,entry:String=""):TextFieldValue {
        // Near the storage limit, leave room for deleting existing text instead of an uneditable extra bullet.
        if(text.length>BookNotebookStore.MAX_CHARS-entry.length-4)return TextFieldValue(text,TextRange(text.length))
        val ready=when{ text.isEmpty()->"- $entry";text.endsWith("\n- ")->text+entry;text.endsWith("\n")->text+"- $entry";else->text+"\n- $entry" }
        return TextFieldValue(ready,TextRange(ready.length))
    }
    fun document(text:String,original:String?=null,entry:String=""):String=when {
        original!=null && text==begin(original,entry).text->original
        entry.isNotEmpty() && text=="- $entry"->""
        text=="- "->""
        else->text.removeSuffix("\n- ").replace(Regex("\\n- \\[C\\d+ · [\\d:]+] $"),"")
    }
    fun edit(before:TextFieldValue,after:TextFieldValue,entry:String=""):TextFieldValue {
        val at=before.selection.start
        if(!before.selection.collapsed || after.text.length!=before.text.length+1 || at>before.text.length ||
            after.text.getOrNull(at)!='\n' || after.text.removeRange(at,at+1)!=before.text)return after
        val start=before.text.lastIndexOf('\n',at-1)+1
        val line=before.text.substring(start,at)
        return when {
            line=="- " || (entry.isNotEmpty() && line.matches(Regex("- \\[C\\d+ · [\\d:]+] ")))->TextFieldValue(after.text.removeRange(start,at),TextRange(start+1))
            line.startsWith("- ") && line.drop(2).isNotBlank()->TextFieldValue(after.text.substring(0,at+1)+"- $entry"+after.text.substring(at+1),TextRange(at+3+entry.length))
            else->after
        }
    }
}
