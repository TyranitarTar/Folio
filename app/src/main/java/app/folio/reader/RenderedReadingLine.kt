package app.folio.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

internal data class RenderedReadingLine(val first: Locator, val last: Locator)

/** Resolve a layout line through Readium's quote anchoring. Never infer a line from chapter percentages. */
private val lineResolution = Mutex()
internal suspend fun EpubNavigatorFragment.renderedReadingLine(anchor: Locator, direction: Int = 0): RenderedReadingLine? =
    lineResolution.withLock { resolveRenderedLine(anchor, direction) }

private suspend fun EpubNavigatorFragment.resolveRenderedLine(anchor: Locator, direction: Int): RenderedReadingLine? {
    if (currentLocator.value.href.removeFragment() != anchor.href.removeFragment()) return null
    commitDecorations(listOf(Decoration("line-anchor", anchor, Decoration.Style.Highlight(tint = 0x0177C1AA))), "line-anchor")
    try {
        delay(25)
        val result = evaluateJavascript(LINE_SCRIPT.replace("DIRECTION", direction.coerceIn(-1, 1).toString()))
        if (currentLocator.value.href.removeFragment() != anchor.href.removeFragment()) return null
        val data = runCatching { JSONObject(result ?: "null") }.getOrNull() ?: return null
        fun locator(quote: JSONObject): Locator? {
            val json = anchor.toJSON()
            val locations = json.getJSONObject("locations")
            locations.put("cssSelector", quote.getString("selector"))
            locations.put("folioOffset", quote.getInt("offset"))
            json.put("text", JSONObject().put("before", quote.getString("before"))
                .put("highlight", quote.getString("highlight")).put("after", quote.getString("after")))
            return Locator.fromJSON(json)
        }
        return RenderedReadingLine(locator(data.getJSONObject("first")) ?: return null,
            locator(data.getJSONObject("last")) ?: return null)
    } finally { withContext(NonCancellable) { commitDecorations(emptyList(), "line-anchor") } }
}

private val LINE_SCRIPT = """
(function(){
 const d=window.readium.getDecorations('line-anchor').items[0]; if(!d) return null;
 const range=d.range, start=range.startContainer, parent=start.nodeType===1?start:start.parentElement;
 const blocks='p,li,h1,h2,h3,h4,h5,h6,blockquote,td,figcaption';
 let block=parent.closest(blocks)||parent; if(!block) return null;
 function selector(el){ if(el.id)return '#'+CSS.escape(el.id); const parts=[];
   while(el&&el!==document.body){let n=1,s=el;while((s=s.previousElementSibling))if(s.tagName===el.tagName)n++;
     parts.unshift(el.tagName.toLowerCase()+':nth-of-type('+n+')');el=el.parentElement;}return 'body > '+parts.join(' > '); }
 function words(el){
   const walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT),nodes=[];let n,offset=0;
   while((n=walker.nextNode())){nodes.push({node:n,a:offset,b:offset+n.length});offset+=n.length; if(offset>100000)return [];}
   const text=el.textContent,items=[];
   function endpoint(at,end){let lo=0,hi=nodes.length-1;
     while(lo<=hi){const mid=(lo+hi)>>1,x=nodes[mid];
       if(end?at<=x.a:at<x.a)hi=mid-1;else if(end?at>x.b:at>=x.b)lo=mid+1;else return x;}return null;}
   for(const m of text.matchAll(/\S{1,256}/gu)){
     const a=m.index,b=a+m[0].length,x=endpoint(a,false),y=endpoint(b,true);if(!x||!y)continue;
     // Lay out only the anchor and neighboring line, rather than every word of a long paragraph.
     const item={a,b,text,el,cached:undefined};Object.defineProperty(item,'rect',{get(){
       if(this.cached===undefined){const r=document.createRange();r.setStart(x.node,a-x.a);r.setEnd(y.node,b-y.a);
         this.cached=Array.from(r.getClientRects()).find(v=>v.width>0&&v.height>0)||null;}return this.cached;}});
     items.push(item);if(items.length>20000)break;
   }return items;
 }
 let items=words(block);if(!items.length)return null;
 const preceding=document.createRange();preceding.selectNodeContents(block);
 try{preceding.setEnd(start,range.startOffset);}catch(e){return null;}
 const offset=preceding.toString().length;
 let i=items.findIndex(x=>x.a<=offset&&x.b>offset);if(i<0)i=items.findIndex(x=>x.a>=offset);if(i<0)return null;
 if(!items[i].rect)return null;
 const same=(a,b)=>a.rect&&b.rect&&Math.abs(a.rect.top-b.rect.top)<Math.min(a.rect.height,b.rect.height)*0.45 &&
   Math.abs(a.rect.left-b.rect.left)<innerWidth*0.85;
 let first=i,last=i;while(first>0&&same(items[first-1],items[i]))first--;
 while(last+1<items.length&&same(items[last+1],items[i]))last++;
 const direction=DIRECTION;
 if(direction){
   let target=direction>0?last+1:first-1;
   if(target<0||target>=items.length){
     const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_ELEMENT,{acceptNode:n=>
       n.matches(blocks)&&!n.querySelector(blocks)&&n.getClientRects().length?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP});
     let n,previous=null,found=false,adjacent=null;
     while((n=walker.nextNode())){if(n===block||n.contains(block)){found=true;if(direction<0){adjacent=previous;break;}}
       else if(found&&direction>0){adjacent=n;break;}previous=n;}
     if(!adjacent)return null;items=words(adjacent);if(!items.length)return null;target=direction>0?0:items.length-1;
   }
   while(target>=0&&target<items.length&&!items[target].rect)target+=direction;
   if(target<0||target>=items.length)return null;
   first=target;last=target;while(first>0&&same(items[first-1],items[target]))first--;
   while(last+1<items.length&&same(items[last+1],items[target]))last++;
 }else first=i; // Keep the dominant word after reflow; do not replay the beginning of its line.
 function quote(x){return {selector:selector(x.el),offset:x.text.slice(0,x.a).replace(/\s+/gu,' ').replace(/^\s+/u,'').length,
   before:x.text.slice(Math.max(0,x.a-160),x.a),highlight:x.text.slice(x.a,x.b),after:x.text.slice(x.b,x.b+160)};}
 return {first:quote(items[first]),last:quote(items[last])};
})()
""".trimIndent()
