package com.tradelens.local.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF1D6F5A)
private val DeepForest = Color(0xFF101B1A)
private val Mist = Color(0xFFF4F7F4)
private val Ink = Color(0xFF14221F)
private val Clay = Color(0xFFB66A4A)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = Clay,
    background = Mist,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE2EBE5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85D5B7),
    onPrimary = DeepForest,
    secondary = Color(0xFFE3A183),
    background = DeepForest,
    surface = Color(0xFF192927),
    onBackground = Color(0xFFE5F0EA),
    onSurface = Color(0xFFE5F0EA),
    surfaceVariant = Color(0xFF263A35)
)

@Composable
fun TradeLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
