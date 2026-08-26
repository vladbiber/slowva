package com.slowva.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFB48EAD)
private val Accent2 = Color(0xFF8E88E0)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF14121A),
    secondary = Accent2,
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFE6E4EC),
    surface = Color(0xFF121219),
    onSurface = Color(0xFFE6E4EC),
    surfaceVariant = Color(0xFF1B1B25),
    onSurfaceVariant = Color(0xFFA7A4B5),
    outline = Color(0xFF2C2C3A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6B4E86),
    secondary = Accent2,
)

@Composable
fun SlowvaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Dark,   // always dark, it's a night app
        typography = Typography(),
        content = content,
    )
}
