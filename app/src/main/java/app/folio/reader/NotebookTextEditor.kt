package app.folio.reader

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
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
    private var dragging=false
    private var userScrolled=false
    private var downY=0f
    private var movedGesture=false
    override fun bringPointIntoView(offset:Int):Boolean {
        if(userScrolled || dragging)return false
        return super.bringPointIntoView(offset)
    }
    fun userEdited(){userScrolled=false}
    fun restoreViewport(y:Int){userScrolled=true;scrollTo(0,y)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){dragging=true;downY=event.y;movedGesture=false;parent?.requestDisallowInterceptTouchEvent(true)}
        if(event.actionMasked==MotionEvent.ACTION_MOVE && kotlin.math.abs(event.y-downY)>android.view.ViewConfiguration.get(context).scaledTouchSlop){userScrolled=true;movedGesture=true}
        val handled=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL){dragging=false;if(!movedGesture)userScrolled=false;parent?.requestDisallowInterceptTouchEvent(false)}
        return handled
    }
    override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){super.onScrollChanged(l,t,oldl,oldt);scrolled?.invoke(t)}
    override fun onSelectionChanged(start:Int,end:Int) {super.onSelectionChanged(start,end);if(!syncing){if(!dragging && start!=end)userScrolled=false;changed?.invoke()}}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(h>0 && hasFocus() && !userScrolled && !dragging)post{if(!userScrolled && !dragging)bringPointIntoView(selectionEnd.coerceAtLeast(0))}
    }
    fun bind(value:TextFieldValue) {
        syncing=true
        try {
            val changedText=text.toString()!=value.text
            val oldScroll=scrollY
            if(changedText)setText(value.text)
            val start=value.selection.start.coerceIn(0,length());val end=value.selection.end.coerceIn(0,length())
            val moved=selectionStart!=start || selectionEnd!=end
            if(moved)setSelection(start,end)
            if(changedText && userScrolled)scrollTo(0,oldScroll)
            if((moved || changedText) && !dragging) {
                userScrolled=false
                post{if(!dragging && !userScrolled)bringPointIntoView(end)}
            }
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
            isVerticalScrollBarEnabled=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
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
                override fun afterTextChanged(s:Editable?){if(!syncing){userEdited();changed?.invoke()}}
            })
            post {
                requestFocus();if(restore!=null)restoreViewport(restore) else bringPointIntoView(selectionEnd.coerceAtLeast(0))
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
