package com.lucid.auto.appstore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LucidBlue = Color(0xFF0B3D5C)
private val LucidAccent = Color(0xFF35A7C2)

private val LightColors = lightColorScheme(
    primary = LucidBlue,
    secondary = LucidAccent,
)

private val DarkColors = darkColorScheme(
    primary = LucidAccent,
    secondary = LucidAccent,
)

@Composable
fun LucidAppStoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
