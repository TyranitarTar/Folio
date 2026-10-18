package app.folio.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.sin

/** Original vector artwork: no downloaded pictures, video player or game engine. */
@Composable internal fun MisoArtwork(
    modifier: Modifier = Modifier, pose: String = "idle", progress: Float = 0f,
    room: Boolean = false, relaxed: Boolean = false
) {
    Canvas(modifier.semantics { contentDescription = if(room) "Misos hyggelige læsehjørne" else "Miso, en orange kat" }) {
        val scale = minOf(size.width / (if(room) 340f else 150f), size.height / (if(room) 220f else 150f))
        translate((size.width - scale * (if(room) 340 else 150)) / 2, (size.height - scale * (if(room) 220 else 150)) / 2) {
            scale(scale, scale, Offset.Zero) {
                if(room) {
                    drawRect(Color(0xFFF3EADC), size = Size(340f, 220f))
                    drawRect(Color(0xFFDCC9AE), Offset(0f, 169f), Size(340f, 51f))
                    drawRoundRect(Color(0xFFDFCDB4), Offset(20f, 18f), Size(82f, 110f), androidx.compose.ui.geometry.CornerRadius(18f))
                    drawRoundRect(Color(0xFFBBD6CC), Offset(26f, 24f), Size(70f, 98f), androidx.compose.ui.geometry.CornerRadius(12f))
                    drawCircle(Color(0xFFF9E5AB), 16f, Offset(75f, 46f))
                    drawLine(Color(0xFFFCF6EB), Offset(61f, 24f), Offset(61f, 122f), 5f)
                    drawLine(Color(0xFFFCF6EB), Offset(26f, 77f), Offset(96f, 77f), 5f)
                    drawRoundRect(Color(0xFF8A6649), Offset(205f, 63f), Size(114f, 8f), androidx.compose.ui.geometry.CornerRadius(3f))
                    listOf(0xFF567666, 0xFFC27D5D, 0xFFC4A06B, 0xFF71899A, 0xFF9B6E6B).forEachIndexed { i, c ->
                        drawRoundRect(Color(c), Offset(213f + i * 17, 30f + i % 2 * 5), Size(12f, 33f - i % 2 * 5), androidx.compose.ui.geometry.CornerRadius(2f))
                    }
                    drawOval(Color(0xFFB9C6AC), Offset(62f, 176f), Size(232f, 32f))
                    drawRoundRect(Color(0xFF68806B), Offset(97f, 89f), Size(133f, 96f), androidx.compose.ui.geometry.CornerRadius(30f))
                    drawRoundRect(Color(0xFF92A38A), Offset(105f, 138f), Size(117f, 44f), androidx.compose.ui.geometry.CornerRadius(20f))
                    drawRoundRect(Color(0xFFE4C89D), Offset(117f, 148f), Size(89f, 20f), androidx.compose.ui.geometry.CornerRadius(9f))
                    drawLine(Color(0xFF695140), Offset(114f, 182f), Offset(110f, 200f), 7f, StrokeCap.Round)
                    drawLine(Color(0xFF695140), Offset(217f, 182f), Offset(221f, 200f), 7f, StrokeCap.Round)
                    drawRect(Color(0xFF8A6649), Offset(270f, 161f), Size(49f, 7f))
                    drawLine(Color(0xFF8A6649), Offset(293f, 167f), Offset(293f, 195f), 6f)
                    drawRoundRect(Color(0xFF496A60), Offset(270f, 151f), Size(40f, 8f), androidx.compose.ui.geometry.CornerRadius(2f))
                    drawRect(Color(0xFFFFF4D8), Offset(275f, 153f), Size(34f, 3f))
                    translate(94f, 40f) { scale(.96f, .96f, Offset.Zero) { cat(pose, progress, relaxed) } }
                } else cat(pose, progress, relaxed)
            }
        }
    }
}

private fun DrawScope.cat(pose: String, progress: Float, relaxed: Boolean) {
    val fur = Color(0xFFD8A373); val dark = Color(0xFFAF7048); val cream = Color(0xFFF4D7B5); val ink = Color(0xFF374A3B)
    val pulse = sin(progress * Math.PI).toFloat().coerceAtLeast(0f)
    val bounce = if(pose == "bookmark") -22f * pulse else if(pose == "treat") 6f * pulse else 0f
    val rest = pose == "rest" || (relaxed && pose == "idle")
    drawOval(Color(0x18244C41), Offset(20f, 121f), Size(110f, 15f))
    translate(12f + if(pose == "bookmark") sin(progress * 2 * Math.PI).toFloat() * 18 else 0f, 10f + bounce) {
        val tail = Path().apply { moveTo(40f, 102f); cubicTo(8f, 108f, 7f, 72f, 27f, 70f - pulse * 15) }
        drawPath(tail, fur, style = Stroke(12f, cap = StrokeCap.Round))
        if(rest) drawOval(fur, Offset(31f, 75f), Size(78f, 40f)) else {
            drawPath(Path().apply { moveTo(37f, 106f); cubicTo(24f, 52f, 91f, 18f, 97f, 106f); close() }, fur)
        }
        val shy = if(pose == "shy") 9f else 0f
        translate(if(rest) -5f else 0f, if(rest) 26f else shy) {
            drawPath(Path().apply { moveTo(38f, 45f); lineTo(38f, 20f + shy); lineTo(56f, 32f); quadraticTo(65f, 29f, 74f, 32f); lineTo(93f, 20f + shy); lineTo(93f, 47f); cubicTo(93f, 86f, 38f, 87f, 38f, 45f); close() }, fur)
            drawPath(Path().apply { moveTo(42f, 27f + shy); lineTo(43f, 41f); lineTo(53f, 35f); close() }, dark)
            drawPath(Path().apply { moveTo(89f, 27f + shy); lineTo(87f, 41f); lineTo(78f, 35f); close() }, dark)
            drawOval(cream, Offset(49f, 52f), Size(34f, 23f))
            val eyes = Path().apply {
                moveTo(47f, 48f); quadraticTo(51f, if(pose == "shy") 43f else 54f, 55f, 48f)
                moveTo(76f, 48f); quadraticTo(80f, if(pose == "shy") 43f else 54f, 84f, 48f)
                moveTo(62f, 57f); lineTo(65f, 59f); lineTo(68f, 57f); moveTo(65f, 59f); lineTo(65f, 62f)
            }
            drawPath(eyes, ink, style = Stroke(2.6f, cap = StrokeCap.Round))
            drawLine(dark, Offset(59f, 34f), Offset(62f, 41f), 3.5f, StrokeCap.Round)
            drawLine(dark, Offset(70f, 34f), Offset(67f, 41f), 3.5f, StrokeCap.Round)
        }
        val pawRise = if(pose == "paw") sin(progress * Math.PI / 2).toFloat() * 35 else 0f
        drawLine(cream, Offset(51f, 95f - pawRise), Offset(51f, 107f - pawRise), 11f, StrokeCap.Round)
        drawLine(cream, Offset(79f, 95f), Offset(79f, 107f), 11f, StrokeCap.Round)
        if(pose == "treat") {
            drawCircle(dark, 5f * (1f - progress.coerceIn(0f, .95f)), Offset(68f, 84f - pulse * 16))
            if(progress > .6f) {
                drawCircle(Color(0xFF6E8C62), 3f, Offset(102f, 39f))
                drawCircle(Color(0xFF6E8C62), 2f, Offset(110f, 30f))
            }
        }
        if(pose == "bookmark") {
            drawPath(Path().apply { moveTo(85f, 92f); lineTo(105f, 92f); lineTo(105f, 119f); lineTo(95f, 114f); lineTo(85f, 119f); close() }, Color(0xFF567666))
        }
    }
}
