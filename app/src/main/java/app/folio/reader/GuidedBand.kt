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
                // Anchors are always invisible. Only the persistent layer paints a whole window.
                "<div style=\"display:none !important\"/>"
            },stylesheet=""".folio-guided-band{pointer-events:none !important;border:0 !important;border-radius:2px;box-sizing:border-box;margin:0 !important;padding:0 !important;opacity:1 !important;}"""))
    }
    suspend fun apply(navigator: EpubNavigatorFragment,first: Locator,last: Locator,tint: Int) {
        val same=first==last
        try {
            if(!same) navigator.commitDecorations(listOf(Decoration("window-end",last,GuidedBandStyle(tint,true))),endpointGroup)
            withTimeout(1500) {
                while(navigator.evaluateJavascript(script(first,last,same,tint))!="true") delay(12)
            }
        } finally {
            if(!same) withContext(NonCancellable) { navigator.commitDecorations(emptyList(),endpointGroup) }
        }
    }
    internal fun anchorReady(locator: Locator): String = """(function(){const expected=${locator.toJSON()},a=expected.text||{},l=expected.locations||{};return window.readium.getDecorations('guided').items.some(x=>{const b=x.decoration.locator.text||{},m=x.decoration.locator.locations||{};return ['before','highlight','after'].every(k=>(a[k]||'')===(b[k]||''))&&(l.cssSelector||'')===(m.cssSelector||'')&&l.folioOffset===m.folioOffset;});})()""".replace("\u2028","\\u2028").replace("\u2029","\\u2029")
    /** Readium anchors both endpoints. A native DOM Range includes the actual intervening spaces/markup. */
    internal fun script(first: Locator,last: Locator,same: Boolean,tint: Int = 0xFF77C1AA.toInt()) = """
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
      // This layer belongs to the document, not a disposable Readium decoration item.
      let state=window.folioGuidedPaint;
      if(!state){
        const layer=document.createElement('div');layer.id='folio-guided-paint';layer.setAttribute('aria-hidden','true');
        layer.style.cssText='position:absolute !important;left:0 !important;top:0 !important;width:0 !important;height:0 !important;min-width:0 !important;min-height:0 !important;max-width:none !important;max-height:none !important;opacity:1 !important;visibility:visible !important;background:transparent !important;box-shadow:none !important;margin:0 !important;padding:0 !important;border:0 !important;overflow:visible !important;pointer-events:none !important;transform:none !important;';document.body.appendChild(layer);
        // A positioned body, column transform or publisher padding can move the layer's
        // containing block. Measure its real CSS coordinate basis instead of adding scroll twice.
        const probe=document.createElement('div');probe.setAttribute('aria-hidden','true');probe.style.cssText='position:absolute !important;left:0 !important;top:0 !important;width:1px !important;height:1px !important;min-width:0 !important;min-height:0 !important;max-width:none !important;max-height:none !important;padding:0 !important;margin:0 !important;border:0 !important;visibility:hidden !important;pointer-events:none !important;transform:none !important;';layer.appendChild(probe);
        state={layer,probe,nodes:[],signature:null,pending:null,range:null,segments:null,color:null,frame:0};window.folioGuidedPaint=state;
        state.paint=function(rects,color){
          const origin=state.probe.getBoundingClientRect(),scaleX=origin.width||1,scaleY=origin.height||1;
          for(let i=0;i<rects.length;i++){
            let node=state.nodes[i];if(!node){node=document.createElement('div');node.className='folio-guided-band';node.setAttribute('aria-hidden','true');state.nodes.push(node);layer.appendChild(node);}
            const r=rects[i];node.style.cssText='position:absolute !important;pointer-events:none !important;border:0 !important;border-radius:2px !important;box-shadow:none !important;visibility:visible !important;opacity:1 !important;box-sizing:border-box !important;margin:0 !important;padding:0 !important;min-width:0 !important;min-height:0 !important;max-width:none !important;max-height:none !important;transform:none !important;';
            node.style.setProperty('background-color',color,'important');node.style.setProperty('left',(r.left-origin.left)/scaleX+'px','important');node.style.setProperty('top',(r.top-origin.top)/scaleY+'px','important');node.style.setProperty('width',r.width/scaleX+'px','important');node.style.setProperty('height',r.height/scaleY+'px','important');
          }
          while(state.nodes.length>rects.length)state.nodes.pop().remove();
        };
        state.relayout=function(){if(state.frame||!state.range)return;state.frame=requestAnimationFrame(function(){state.frame=0;if(state.range&&state.segments){let rects=state.segments();if(rects.length)state.paint(rects,state.color);}});};
        window.addEventListener('resize',state.relayout);window.addEventListener('scroll',state.relayout,true);
        state.observer=new MutationObserver(state.relayout);state.observer.observe(document.documentElement,{attributes:true,attributeFilter:['style','class']});
        if(document.fonts)document.fonts.ready.then(state.relayout);
        state.stop=function(){state.pending=null;state.range=null;if(state.frame)cancelAnimationFrame(state.frame);window.removeEventListener('resize',state.relayout);window.removeEventListener('scroll',state.relayout,true);state.observer.disconnect();layer.remove();if(window.folioGuidedPaint===state)window.folioGuidedPaint=null;};
      }
      if(!group.folioPersistentClear){const original=group.clear;group.clear=function(){const current=window.folioGuidedPaint;if(current)current.stop();original.apply(group,arguments);};group.folioPersistentClear=true;}
      const key=JSON.stringify([${first.toJSON()},${last.toJSON()},$tint]);
      if(state.signature===key){item.folioWindowRange=state.range;return true;}
      if(state.pending===key)return false;
      state.pending=key;
      requestAnimationFrame(function(){
        if(window.folioGuidedPaint!==state||state.pending!==key||!group.items.includes(item))return;
        const rects=segments();if(!rects.length){state.pending=null;return;}
        // All segment changes happen together; the old complete band stays until this callback.
        state.paint(rects,'rgba(${Color.red(tint)},${Color.green(tint)},${Color.blue(tint)},0.38)');
        state.range=selected;state.segments=segments;state.color='rgba(${Color.red(tint)},${Color.green(tint)},${Color.blue(tint)},0.38)';state.signature=key;state.pending=null;
        item.folioWindowRange=selected;item.range.getClientRects=segments;
      });
      return false;
    })()
    """.trimIndent().replace("\u2028","\\u2028").replace("\u2029","\\u2029")
}
