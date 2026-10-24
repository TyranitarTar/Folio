package app.folio.reader

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject

enum class TapAction { PREVIOUS, MENU, NEXT }

data class NavigationSettings(
    val layout: String = "Default", val inversion: String = "None", val direction: String = "Book",
    val animate: Boolean = true, val overlay: Boolean = true, val invertVolume: Boolean = false
) {
    fun action(x: Float, y: Float, rtl: Boolean = false): TapAction {
        val a = if (inversion == "Horizontal" || inversion == "Both") 1f - x else x
        val b = if (inversion == "Vertical" || inversion == "Both") 1f - y else y
        // Normalized zones follow the documented Mihon layouts. Default is Folio's side layout.
        return when (layout) {
            "Disabled" -> TapAction.MENU
            "L shaped" -> when { b < .33f || (a < .33f && b < .66f) -> TapAction.PREVIOUS; b >= .66f || a >= .66f -> TapAction.NEXT; else -> TapAction.MENU }
            "Kindle-ish" -> when { b < .33f -> TapAction.MENU; a < .33f -> TapAction.PREVIOUS; else -> TapAction.NEXT }
            "Edge" -> when { a < .33f || a >= .66f -> TapAction.NEXT; b >= .66f -> TapAction.PREVIOUS; else -> TapAction.MENU }
            else -> when { a < .33f -> if (rtl) TapAction.NEXT else TapAction.PREVIOUS; a >= .66f -> if (rtl) TapAction.PREVIOUS else TapAction.NEXT; else -> TapAction.MENU }
        }
    }
    fun json() = JSONObject().put("layout", layout).put("inversion", inversion).put("direction", direction)
        .put("animate", animate).put("overlay", overlay).put("invertVolume", invertVolume)
    companion object {
        val layouts = listOf("Default", "L shaped", "Kindle-ish", "Edge", "Right and Left", "Disabled")
        fun overridden(context: Context, id: String) = context.getSharedPreferences("navigation", 0).contains(id)
        fun load(context: Context, id: String): NavigationSettings {
            val p = context.getSharedPreferences("navigation", 0)
            val j = runCatching { JSONObject(p.getString(id, p.getString("global", "{}"))!!) }.getOrDefault(JSONObject())
            return NavigationSettings(j.optString("layout", "Default"), j.optString("inversion", "None"), j.optString("direction", "Book"),
                j.optBoolean("animate", true), j.optBoolean("overlay", true), j.optBoolean("invertVolume"))
        }
        fun save(context: Context, id: String, value: NavigationSettings, bookOnly: Boolean) {
            val edit = context.getSharedPreferences("navigation", 0).edit()
            if (bookOnly) edit.putString(id, value.json().toString())
            else { edit.remove(id); edit.putString("global", value.json().toString()) }
            edit.apply()
        }
    }
}

@Composable
fun TapPreview(value: NavigationSettings, rtl: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(135.dp)) {
            for (row in 0..2) for (col in 0..2) {
                val action = value.action((col + .5f) / 3, (row + .5f) / 3, rtl)
                val color = when (action) { TapAction.NEXT -> Color(0xFF609C82); TapAction.PREVIOUS -> Color(0xFFE5C684); TapAction.MENU -> Color(0xFFD8DFD8) }
                drawRect(color, Offset(col * size.width / 3 + 2, row * size.height / 3 + 2), Size(size.width / 3 - 4, size.height / 3 - 4))
            }
        }
        Text("Green: next · Gold: previous · Grey: menu", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
