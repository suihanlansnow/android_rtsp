package com.shiliu.stream.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun DrawScope.glyph(size: Dp, block: DrawScope.() -> Unit) {
    val factor = this.size.minDimension / 24f
    scale(factor, factor, pivot = Offset.Zero) { block() }
}

@Composable
fun CameraGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            val stroke = Stroke(1.7f)
            drawRoundRect(
                color = color,
                topLeft = Offset(3.5f, 7f),
                size = Size(17f, 13f),
                cornerRadius = CornerRadius(2f),
                style = stroke
            )
            drawLine(color, Offset(6.5f, 7f), Offset(8.5f, 4.5f), 1.7f, StrokeCap.Round)
            drawLine(color, Offset(8.5f, 4.5f), Offset(15.5f, 4.5f), 1.7f, StrokeCap.Round)
            drawLine(color, Offset(15.5f, 4.5f), Offset(17.5f, 7f), 1.7f, StrokeCap.Round)
            drawCircle(color, 3.2f, Offset(12f, 13.4f), style = stroke)
        }
    }
}

@Composable
fun DisplayGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            val stroke = Stroke(1.7f)
            drawRoundRect(
                color = color,
                topLeft = Offset(3f, 5f),
                size = Size(18f, 14f),
                cornerRadius = CornerRadius(2f),
                style = stroke
            )
            drawLine(color, Offset(3f, 9f), Offset(21f, 9f), 1.7f)
            drawLine(color, Offset(7f, 5f), Offset(7f, 9f), 1.7f)
        }
    }
}

@Composable
fun LockGlyph(color: Color, size: Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            val stroke = Stroke(1.7f)
            drawRoundRect(
                color = color,
                topLeft = Offset(4f, 10f),
                size = Size(16f, 11f),
                cornerRadius = CornerRadius(2f),
                style = stroke
            )
            drawArc(
                color = color,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(8f, 4f),
                size = Size(8f, 12f),
                style = Stroke(1.7f, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun CopyGlyph(color: Color, size: Dp = 13.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            val stroke = Stroke(1.7f)
            drawRoundRect(
                color = color,
                topLeft = Offset(9f, 9f),
                size = Size(11f, 11f),
                cornerRadius = CornerRadius(2f),
                style = stroke
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(4f, 4f),
                size = Size(11f, 11f),
                cornerRadius = CornerRadius(2f),
                style = stroke
            )
        }
    }
}

@Composable
fun EyeGlyph(color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            val path = Path().apply {
                moveTo(2f, 12f)
                cubicTo(5f, 7f, 9f, 6f, 12f, 6f)
                cubicTo(15f, 6f, 19f, 7f, 22f, 12f)
                cubicTo(19f, 17f, 15f, 18f, 12f, 18f)
                cubicTo(9f, 18f, 5f, 17f, 2f, 12f)
                close()
            }
            drawPath(path, color, style = Stroke(1.7f))
            drawCircle(color, 2.6f, Offset(12f, 12f), style = Stroke(1.7f))
        }
    }
}

@Composable
fun CheckGlyph(color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        glyph(size) {
            drawLine(color, Offset(5f, 12f), Offset(10f, 17f), 3f, StrokeCap.Round)
            drawLine(color, Offset(10f, 17f), Offset(19f, 7f), 3f, StrokeCap.Round)
        }
    }
}
