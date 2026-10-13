@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Locator

/** A separate style keeps saved annotations and speech decorations unchanged. */
internal data class GuidedBandStyle(override val tint: Int,val hidden: Boolean=false) : Decoration.Style,Decoration.Style.Tinted {
    override fun describeContents()=0
    override fun writeToParcel(dest: Parcel,flags: Int) { dest.writeInt(tint);dest.writeInt(if(hidden) 1 else 0) }
    companion object {
        @JvmField val CREATOR=object:Parcelable.Creator<GuidedBandStyle> {
            override fun createFromParcel(source: Parcel)=GuidedBandStyle(source.readInt(),source.readInt()!=0)
            override fun newArray(size: Int)=arrayOfNulls<GuidedBandStyle>(size)
        }
    }
}

internal object GuidedBand {
    const val endpointGroup="folio-guided-endpoint"
    fun tint(context: Context): Int = when(context.getSharedPreferences("appearance",0).getString("theme","Paper")) {
        "Night" -> 0xFF67C9AA.toInt(); else -> 0xFF77C1AA.toInt()
    }
    fun templates()=HtmlDecorationTemplates.defaultTemplates().apply {
        set(GuidedBandStyle::class,HtmlDecorationTemplate(layout=HtmlDecorationTemplate.Layout.BOXES,
            element={ decoration ->
                val style=decoration.style as GuidedBandStyle
                if(style.hidden) "<div style=\"display:none !important\"/>"
                else """<div class="folio-guided-band" style="background-color:rgba(${Color.red(style.tint)},${Color.green(style.tint)},${Color.blue(style.tint)},0.38) !important;"/>"""
            },stylesheet=""".folio-guided-band{pointer-events:none !important;border:0 !important;border-radius:2px;box-sizing:border-box;margin:0 !important;padding:0 !important;opacity:1 !important;}"""))
    }
    suspend fun apply(navigator: EpubNavigatorFragment,first: Locator,last: Locator,tint: Int) {
        val same=first==last
        try {
            if(!same) navigator.commitDecorations(listOf(Decoration("window-end",last,GuidedBandStyle(tint,true))),endpointGroup)
            withTimeout(1500) {
                while(navigator.evaluateJavascript(script(first,last,same))!="true") delay(12)
            }
        } finally {
            if(!same) withContext(NonCancellable) { navigator.commitDecorations(emptyList(),endpointGroup) }
        }
    }
    /** Readium anchors both endpoints. A native DOM Range includes the actual intervening spaces/markup. */
    internal fun script(first: Locator,last: Locator,same: Boolean) = """
    (function(){
      const group=window.readium.getDecorations('guided'),item=group.items.find(x=>x.decoration.id==='current-word');
      const end=$same?item:window.readium.getDecorations('$endpointGroup').items.find(x=>x.decoration.id==='window-end');
      if(!item||!end)return false;
      function matches(a,b){const x=a.text||{},y=b.text||{},al=a.locations||{},bl=b.locations||{};
        return ['before','highlight','after'].every(k=>(x[k]||'')===(y[k]||''))&&(al.cssSelector||'')===(bl.cssSelector||'')&&al.folioOffset===bl.folioOffset;}
      if(!matches(item.decoration.locator,${first.toJSON()} )||!matches(end.decoration.locator,${last.toJSON()}))return false;
      const selected=document.createRange();
      try { selected.setStart(item.range.startContainer,item.range.startOffset);selected.setEnd(end.range.endContainer,end.range.endOffset); } catch(e){return false;}
      if(selected.collapsed)return false;
      const nativeRects=selected.getClientRects.bind(selected);
      const parent=item.range.startContainer.nodeType===1?item.range.startContainer:item.range.startContainer.parentElement;
      const vertical=getComputedStyle(parent).writingMode.startsWith('vertical');
      const pageWidth=Android.getViewportWidth()/devicePixelRatio;
      function segments(){
        const raw=Array.from(nativeRects()).filter(r=>r.width>0.1&&r.height>0.1);
        if(raw.length>512)return [];
        const lines=[];
        for(const r of raw){
          const page=Math.floor((r.left+window.scrollX+0.5)/pageWidth);
          const center=vertical?(r.left+r.right)/2:(r.top+r.bottom)/2;
          const extent=vertical?r.width:r.height;
          let line=lines.find(x=>x.page===page&&Math.abs(x.center-center)<Math.min(x.extent,extent)*0.48);
          if(!line){line={page,center,extent,left:r.left,right:r.right,top:r.top,bottom:r.bottom};lines.push(line);}
          else {line.left=Math.min(line.left,r.left);line.right=Math.max(line.right,r.right);line.top=Math.min(line.top,r.top);line.bottom=Math.max(line.bottom,r.bottom);}
        }
        return lines.map(r=>({left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.right-r.left,height:r.bottom-r.top}));
      }
      if(!segments().length)return false;
      // Keep the dominant word's bounding rect/toString for navigation/cursor checks. Only the
      // painted boxes use the contiguous range, recalculated when Readium reflows it.
      item.folioWindowRange=selected;item.range.getClientRects=segments;
      group.requestLayout();return true;
    })()
    """.trimIndent().replace("\u2028","\\u2028").replace("\u2029","\\u2029")
}
