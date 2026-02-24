package com.tornadone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = Gold,
    onPrimary        = WarmBlack,
    primaryContainer = Color(0xFF6B5010),
    secondary        = Crimson,
    onSecondary      = Cream,
    tertiary         = Cream,
    onTertiary       = WarmBlack,
    background       = WarmBlack,
    onBackground     = Cream,
    surface          = DarkCharcoal,
    onSurface        = Cream,
    surfaceVariant   = DeepCharcoal,
    onSurfaceVariant = WarmMuted,
    error            = Color(0xFFCF6679),
    onError          = WarmBlack,
)

@Composable
fun TornadoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
