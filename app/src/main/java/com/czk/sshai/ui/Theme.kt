package com.czk.sshai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgColor = Color(0xFF0B0F14)
val SurfaceColor = Color(0xFF121820)
val AccentColor = Color(0xFF22D3A6)
val BlueColor = Color(0xFF7DD3FC)
val TermTextColor = Color(0xFFD7E1EA)

private val Scheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = Color(0xFF04140F),
    primaryContainer = Color(0xFF11362C),
    onPrimaryContainer = AccentColor,
    secondary = BlueColor,
    onSecondary = Color(0xFF04121A),
    background = BgColor,
    onBackground = TermTextColor,
    surface = SurfaceColor,
    onSurface = TermTextColor,
    surfaceVariant = Color(0xFF1B2530),
    onSurfaceVariant = Color(0xFF9FB0C0),
    outline = Color(0xFF33414F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0505),
)

@Composable
fun SshaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
