package com.humblesolutions.ainotetaker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.ainotetaker.ui.theme.Tas

/*
 * The logo mark, on a 100 x 100 grid (the same numbers as res/drawable/ic_logo_mark.xml):
 * a rounded square (radius 28) in primary, three white rounded bars of different heights
 * (a small waveform) and two short note lines beside them.
 */

/** Bars: centre x, top, bottom (round caps included), width 9. */
private val BARS = listOf(
    Triple(24.5f, 35f, 65f),
    Triple(37.5f, 26f, 74f),
    Triple(50.5f, 39f, 61f),
)
private const val BAR_WIDTH = 9f

/** Note lines: left, right (round caps included), centre y, thickness 8. */
private val LINES = listOf(
    Triple(63f, 80f, 43f),
    Triple(63f, 74f, 57f),
)
private const val LINE_WIDTH = 8f

/** Draws the white mark (bars and lines) into a square of [side] px at [origin]. */
fun DrawScope.drawNoteMark(origin: Offset, side: Float, color: Color) {
    val u = side / 100f
    for ((x, top, bottom) in BARS) {
        val r = BAR_WIDTH / 2
        drawLine(
            color = color,
            start = Offset(origin.x + x * u, origin.y + (top + r) * u),
            end = Offset(origin.x + x * u, origin.y + (bottom - r) * u),
            strokeWidth = BAR_WIDTH * u,
            cap = StrokeCap.Round,
        )
    }
    for ((left, right, y) in LINES) {
        val r = LINE_WIDTH / 2
        drawLine(
            color = color,
            start = Offset(origin.x + (left + r) * u, origin.y + y * u),
            end = Offset(origin.x + (right - r) * u, origin.y + y * u),
            strokeWidth = LINE_WIDTH * u,
            cap = StrokeCap.Round,
        )
    }
}

/** The app's logo mark: the rounded primary square with the white waveform and note lines. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, background: Color = Tas.colors.primary, mark: Color = Color.White) {
    Canvas(modifier.size(size)) {
        val side = this.size.minDimension
        drawRoundRect(color = background, cornerRadius = CornerRadius(side * 0.28f))
        drawNoteMark(Offset.Zero, side, mark)
    }
}

/** The mark and "AI Notebook" in Manrope 800. */
@Composable
fun LogoLockup(
    modifier: Modifier = Modifier,
    markSize: Dp = 32.dp,
    textStyle: TextStyle = Tas.type.heading.copy(fontWeight = FontWeight.ExtraBold),
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogoMark(markSize)
        Text("AI Notebook", style = textStyle, color = Tas.colors.foreground, maxLines = 1)
    }
}
