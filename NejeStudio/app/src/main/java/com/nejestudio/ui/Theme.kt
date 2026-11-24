package com.nejestudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFD84315),
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),
    surfaceVariant = Color(0xFFECEFF1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3E1200),
    secondary = Color(0xFF90A4AE),
)

@Composable
fun NejeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
