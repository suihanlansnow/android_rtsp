package com.shiliu.stream.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 从 data/camera-streaming.html 提取的设计 token（OKLCH 已换算为 sRGB）。 */
object Tok {
    val Bg = Color(0xFF060E15)
    val BgTop = Color(0xFF0E171F)
    val BgDeep = Color(0xFF02060D)
    val Surface = Color(0xFF111921)
    val Surface2 = Color(0xFF1C252D)
    val Fg = Color(0xFFEFF2F5)
    val Muted = Color(0xFF88949D)
    val Border = Color(0xFF27323A)
    val HoverBorder = Color(0xFF59656E)
    val Accent = Color(0xFF3FC168)
    val AccentHover = Color(0xFF4FCE74)
    val AccentSoft = Color(0x293FC168)
    val Danger = Color(0xFFED4A49)
    val DangerHover = Color(0xFFFF5B58)
    val OnAccent = Color(0xFF060E07)
    val OnDanger = Color(0xFFF8F4F3)

    val Radius = 14.dp
    val RadiusLarge = 22.dp

    val Mono = FontFamily.Monospace
}

@Composable
fun ShiLiuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tok.Accent,
            onPrimary = Tok.OnAccent,
            background = Tok.Bg,
            onBackground = Tok.Fg,
            surface = Tok.Surface,
            onSurface = Tok.Fg,
            surfaceVariant = Tok.Surface2,
            onSurfaceVariant = Tok.Muted,
            outline = Tok.Border,
            error = Tok.Danger
        ),
        content = content
    )
}
