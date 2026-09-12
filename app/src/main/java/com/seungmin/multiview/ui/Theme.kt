package com.seungmin.multiview.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF111111),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFDDDDDD),
    error = Color(0xFFFF6B6B)
)

@Composable
fun MultiViewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
