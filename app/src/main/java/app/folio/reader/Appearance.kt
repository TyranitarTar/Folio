@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package app.folio.reader

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

data class Appearance(
    val theme: String = "Paper", val fontSize: Float = 1.1f, val lineHeight: Float = 1.55f,
    val margins: Float = 1.0f, val font: String = "Original", val scroll: Boolean = false,
    val publisher: Boolean = true, val keepAwake: Boolean = false, val volumeKeys: Boolean = false,
    val awakeMode: String = "timed", val awakeMinutes: Int = 10
) {
    val awakePolicy: ReaderAwakePolicy get() = ReaderAwakePolicy(if(keepAwake) "always" else awakeMode,awakeMinutes).normalized()
    fun withAwake(policy: ReaderAwakePolicy): Appearance = policy.normalized().let { copy(keepAwake=it.mode=="always",awakeMode=it.mode,awakeMinutes=it.minutes) }
    fun preferences(direction: String = "Book") = EpubPreferences(
        readingProgression = when (direction) { "LTR" -> org.readium.r2.navigator.preferences.ReadingProgression.LTR; "RTL" -> org.readium.r2.navigator.preferences.ReadingProgression.RTL; else -> null },
        theme = when (theme) { "Night" -> Theme.DARK; "Sepia" -> Theme.SEPIA; else -> Theme.LIGHT },
        fontSize = fontSize.toDouble(), lineHeight = lineHeight.toDouble(), pageMargins = margins.toDouble(),
        fontFamily = if (font == "Original") null else FontFamily(if (font == "Serif") "serif" else "sans-serif"),
        scroll = scroll, publisherStyles = publisher, columnCount = ColumnCount.ONE,
        textAlign = if (publisher) null else TextAlign.START,
        backgroundColor = org.readium.r2.navigator.preferences.Color(when (theme) {
            "Night" -> android.graphics.Color.rgb(23, 29, 28)
            "Sepia" -> android.graphics.Color.rgb(243, 232, 211)
            else -> android.graphics.Color.rgb(248, 246, 241)
        })
    )
    fun save(context: Context) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
            .putString("theme", theme).putFloat("fontSize", fontSize).putFloat("lineHeight", lineHeight)
            .putFloat("margins", margins).putString("font", font).putBoolean("scroll", scroll)
            .putBoolean("publisher", publisher).putBoolean("awake", awakePolicy.mode=="always").putBoolean("volume", volumeKeys)
            .putString("awakeMode",awakePolicy.mode).putInt("awakeMinutes",awakePolicy.minutes).apply()
    }
    companion object {
        fun load(context: Context): Appearance {
            val p = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
            val awake=ReaderAwakePolicy(if(p.contains("awakeMode")) p.getString("awakeMode","timed").orEmpty() else if(p.getBoolean("awake",false)) "always" else "timed",
                p.getInt("awakeMinutes",10)).normalized()
            return Appearance(p.getString("theme", "Paper") ?: "Paper", p.getFloat("fontSize", 1.1f),
                p.getFloat("lineHeight", 1.55f), p.getFloat("margins", 1f), p.getString("font", "Original") ?: "Original",
                p.getBoolean("scroll", false), p.getBoolean("publisher", true), awake.mode=="always", p.getBoolean("volume", false),awake.mode,awake.minutes)
        }
    }
}

@Composable
fun FolioTheme(night: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (night) darkColorScheme(primary = Color(0xFFA0D1B8), primaryContainer = Color(0xFF25483D), onPrimaryContainer = Color(0xFFD1EBDE), secondary = Color(0xFFB9CEBF), secondaryContainer = Color(0xFF304338), background = Color(0xFF171D1C),
        surface = Color(0xFF171D1C), surfaceVariant = Color(0xFF29322F), onSurfaceVariant = Color(0xFFB8C3BD))
    else lightColorScheme(primary = Color(0xFF2C6155), onPrimary = Color.White, primaryContainer = Color(0xFFD8E6DB), onPrimaryContainer = Color(0xFF173D30), secondary = Color(0xFF586D5E), secondaryContainer = Color(0xFFE4E8DE), background = Color(0xFFF8F6F1),
        surface = Color(0xFFF8F6F1), surfaceVariant = Color(0xFFEDEAE2), onSurface = Color(0xFF222D29),
        onSurfaceVariant = Color(0xFF65716B), outlineVariant = Color(0xFFDCDDD3))
    MaterialTheme(colorScheme = colors, content = content)
}
