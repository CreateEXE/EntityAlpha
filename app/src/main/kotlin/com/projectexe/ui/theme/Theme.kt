package com.projectexe.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Brand colours ─────────────────────────────────────────────
val Cyan      = Color(0xFF00E8FF)
val Magenta   = Color(0xFFFF1F8E)
val Green     = Color(0xFF39FF14)
val Amber     = Color(0xFFFFB700)
val Purple    = Color(0xFFA855F7)
val BgDeep    = Color(0xFF020408)
val BgPanel   = Color(0xFF050810)
val BgCard    = Color(0xFF070B16)
val Border    = Color(0xFF0D1525)
val TextMain  = Color(0xFF7AA0BC)
val TextDim   = Color(0xFF3A5570)
val TextDimMid= Color(0xFF4A6580)

private val DarkColors = darkColorScheme(
    primary          = Cyan,
    onPrimary        = BgDeep,
    primaryContainer = Color(0xFF001A1F),
    secondary        = Magenta,
    onSecondary      = BgDeep,
    tertiary         = Purple,
    background       = BgDeep,
    surface          = BgPanel,
    surfaceVariant   = BgCard,
    onBackground     = TextMain,
    onSurface        = TextMain,
    onSurfaceVariant = TextDim,
    outline          = Border,
    error            = Magenta,
)

@Composable
fun ProjectEXETheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = Typography(),
        content     = content,
    )
}
