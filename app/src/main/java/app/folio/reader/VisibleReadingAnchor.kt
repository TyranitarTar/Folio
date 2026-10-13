package app.folio.reader

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** A paragraph selector alone points to its beginning, even when that beginning is off-page. */
internal suspend fun EpubNavigatorFragment.visibleReadingAnchor(): Locator? {
    val base = firstVisibleElementLocator() ?: currentLocator.value
    val requestedHref = base.href.removeFragment()
    val result = evaluateJavascript("""
        (function() {
            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
            let node; let examined = 0;
            while ((node = walker.nextNode())) {
                const parent = node.parentElement;
                if (!parent || parent.closest('script,style,noscript,[aria-hidden="true"],[hidden]')) continue;
                let hidden = false;
                for (let ancestor = parent; ancestor; ancestor = ancestor.parentElement) {
                    const style = getComputedStyle(ancestor);
                    if (style.visibility === 'hidden' || style.visibility === 'collapse' || style.display === 'none' || Number(style.opacity) === 0) { hidden = true; break; }
                }
                if (hidden) continue;
                const whole = document.createRange(); whole.selectNodeContents(node);
                const visible = r => r.width > 0 && r.height > 0 && r.right > 2 && r.left < innerWidth - 2 && r.bottom > 2 && r.top < innerHeight - 2;
                if (!Array.from(whole.getClientRects()).some(visible)) continue;
                const matches = node.textContent.matchAll(/\S+/gu);
                for (const match of matches) {
                    if (++examined > 30000) return null;
                    const range = document.createRange();
                    range.setStart(node, match.index); range.setEnd(node, match.index + match[0].length);
                    if (!Array.from(range.getClientRects()).some(visible)) continue;
                    const block = parent.closest('p,li,h1,h2,h3,h4,h5,h6,blockquote,td,figcaption') || parent;
                    const preceding = document.createRange(); preceding.selectNodeContents(block); preceding.setEnd(node, match.index);
                    const text = block.textContent;
                    let a = preceding.toString().length, b = a + match[0].length;
                    while (a > 0 && /\S/u.test(text[a - 1])) a--;
                    while (b < text.length && /\S/u.test(text[b])) b++;
                    return {offset: text.slice(0,a).replace(/\s+/gu,' ').replace(/^\s+/u,'').length, selector: block.id ? "#" + CSS.escape(block.id) : null, before: text.slice(Math.max(0, a - 160), a), highlight: text.slice(a, b), after: text.slice(b, b + 160)};
                }
            }
            return null;
        })()
    """.trimIndent())
    if (currentLocator.value.href.removeFragment() != requestedHref) return null
    if (result == null || result == "null") return base
    val quote = runCatching { JSONObject(result) }.getOrNull() ?: return base
    val json = base.toJSON()
    if (!quote.isNull("selector")) json.getJSONObject("locations").put("cssSelector", quote.getString("selector"))
    json.getJSONObject("locations").put("folioOffset", quote.getInt("offset"))
    json.put("text", org.json.JSONObject().put("before", quote.optString("before")).put("highlight", quote.getString("highlight")).put("after", quote.optString("after")))
    return Locator.fromJSON(json)
}
