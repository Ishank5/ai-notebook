package com.humblesolutions.ainotetaker.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** The field icons the sign-in screen draws for itself. The logo mark is in ui/components/Logo.kt. */

@Composable
fun MailGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.22f),
            size = Size(w * 0.80f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = stroke,
        )
        drawPath(
            path = Path().apply {
                moveTo(w * 0.14f, h * 0.28f)
                lineTo(w * 0.50f, h * 0.54f)
                lineTo(w * 0.86f, h * 0.28f)
            },
            color = tint,
            style = stroke,
        )
    }
}

@Composable
fun LockGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        // The shackle, open at the bottom where the body meets it.
        drawPath(
            path = Path().apply {
                arcTo(
                    rect = Rect(
                        offset = Offset(w * 0.26f, h * 0.10f),
                        size = Size(w * 0.48f, h * 0.48f),
                    ),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = true,
                )
            },
            color = tint,
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.44f),
            size = Size(w * 0.68f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.14f),
        )
    }
}

@Composable
fun EyeGlyph(modifier: Modifier = Modifier, tint: Color, struck: Boolean) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        drawPath(
            path = Path().apply {
                moveTo(w * 0.06f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.12f, w * 0.94f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.88f, w * 0.06f, h * 0.5f)
                close()
            },
            color = tint,
            style = stroke,
        )
        drawCircle(color = tint, radius = w * 0.155f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
        // Struck through when the password is ALREADY showing, so the icon
        // says what tapping it does rather than what the state is.
        if (struck) {
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * 0.86f),
                end = Offset(w * 0.86f, h * 0.14f),
                strokeWidth = w * 0.085f,
                cap = StrokeCap.Round,
            )
        }
    }
}
