package com.tornado.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = TornadoTeal,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = TornadoDark,
    surface = TornadoDark,
)

@Composable
fun TornadoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
