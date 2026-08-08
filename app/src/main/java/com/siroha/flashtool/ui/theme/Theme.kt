package com.siroha.flashtool.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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
import com.siroha.flashtool.core.ThemeMode

// Static brand fallback palette used on Android 10/11 (API 29/30), where
// dynamic color (Material You) doesn't exist yet, or when the person turns
// dynamic color off in Settings.
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

/** Forces every surface/background tone in [scheme] to true black (#000000) for AMOLED. */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)

@Composable
fun SirohaFlashToolTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    var colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> SirohaDark
        else -> SirohaLight
    }
    if (themeMode == ThemeMode.AMOLED) {
        colorScheme = colorScheme.toAmoled()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as Activity).window
            // Explicit color setters still matter on API 29-34 (and are a
            // harmless no-op on 35+, where edge-to-edge makes both bars
            // transparent and the root Surface's own background — already
            // colorScheme.surface, including true black for AMOLED — shows
            // through instead). Setting BOTH bars, not just the status bar,
            // is what was missing before: the nav bar previously kept
            // whatever the OS default was instead of following the theme.
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SirohaTypography,
        content = content
    )
}
