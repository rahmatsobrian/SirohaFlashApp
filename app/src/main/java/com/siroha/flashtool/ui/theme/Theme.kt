package com.siroha.flashtool.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Static brand fallback palette used on Android 10/11 (API 29/30), where
// dynamic color (Material You) doesn't exist yet.
private val SirohaDark = darkColorScheme(
    primary = Color(0xFF9FCBFF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBAC8DA),
    background = Color(0xFF10131A),
    surface = Color(0xFF10131A),
    error = Color(0xFFFFB4AB),
)

private val SirohaLight = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    background = Color(0xFFFAFCFF),
    surface = Color(0xFFFAFCFF),
    error = Color(0xFFBA1A1A),
)

@Composable
fun SirohaFlashToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: on by default when the OS supports it (Android 12+ / API 31+)
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> SirohaDark
        else -> SirohaLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SirohaTypography,
        content = content
    )
}
