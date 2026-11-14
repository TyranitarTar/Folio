package app.folio.reader

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView

/** Native editing keeps long text, selection, contextual copy/paste and cursor scrolling together. */
internal class NotebookEditText(context:Context):AppCompatEditText(context) {
    var syncing=false
    var changed:(()->Unit)?=null
    var scrolled:((Int)->Unit)?=null
    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);scrolled?.invoke(t)}
    override fun onSelectionChanged(start:Int,end:Int) {super.onSelectionChanged(start,end);if(!syncing)changed?.invoke()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(h>0 && hasFocus())post{bringPointIntoView(selectionEnd.coerceAtLeast(0))}
    }
    fun bind(value:TextFieldValue) {
        syncing=true
        try {
            if(text.toString()!=value.text)setText(value.text)
            val start=value.selection.start.coerceIn(0,length());val end=value.selection.end.coerceIn(0,length())
            if(selectionStart!=start || selectionEnd!=end){setSelection(start,end);post{bringPointIntoView(end)}}
        }finally{syncing=false}
    }
}

@Composable internal fun NotebookTextEditor(model:BookNotebookModel,color:Int,cursor:Int,modifier:Modifier) {
    AndroidView(modifier=modifier,factory={context->
        val restore=model.editorScroll
        NotebookEditText(context).apply {
            contentDescription="Book notebook"
            isSaveEnabled=false // The ViewModel/file owns large text; never put it into an Activity Bundle.
            importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            gravity=Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false);setBackgroundColor(Color.TRANSPARENT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,16f);setLineSpacing(0f,1.35f)
            val dp=resources.displayMetrics.density
            setPadding((20*dp).toInt(),(4*dp).toInt(),(20*dp).toInt(),(8*dp).toInt())
            bind(model.value)
            scrolled={model.editorScroll=it}
            changed={
                if(!syncing) {
                    model.edit(TextFieldValue(text.toString(),TextRange(selectionStart.coerceAtLeast(0),selectionEnd.coerceAtLeast(0))))
                    bind(model.value)
                }
            }
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
                override fun afterTextChanged(s:Editable?){if(!syncing)changed?.invoke()}
            })
            post {
                requestFocus();restore?.let{scrollTo(0,it)};bringPointIntoView(selectionEnd.coerceAtLeast(0))
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this,InputMethodManager.SHOW_IMPLICIT)
            }
        }
    },update={view->
        view.setTextColor(color)
        view.highlightColor=(cursor and 0x00ffffff) or 0x44000000
        if(android.os.Build.VERSION.SDK_INT>=29)view.textCursorDrawable?.mutate()?.setTint(cursor)
        view.bind(model.value)
    })
}
