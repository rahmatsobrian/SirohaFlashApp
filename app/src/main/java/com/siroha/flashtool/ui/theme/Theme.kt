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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.siroha.flashtool.core.ColorMode
import com.siroha.flashtool.core.ColorStyle
import com.siroha.flashtool.core.FontFamilyPreset
import com.siroha.flashtool.core.FontWeightPreset
import com.siroha.flashtool.core.PresetThemeId
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

/**
 * Makes every surface role translucent and the base `background` fully
 * transparent, for the "Clear" / "Custom wallpaper" Appearance modes. Since
 * essentially every card/bar in this app pulls its fill color from
 * `MaterialTheme.colorScheme.*` (see MenuListItem, SirohaBottomBar,
 * SettingsScreen), this one conversion is what makes the blurred wallpaper
 * show through everywhere at once, without touching each screen file.
 *
 * Also covers the `*Container` roles (primary/secondary/tertiary), not just
 * `surface*` — Material3's stock `NavigationBarItem` selected-tab indicator
 * (the "pill" behind the Home icon) and the Settings radio-row selected
 * state both pull their fill from `secondaryContainer`/`primaryContainer`,
 * so without this they stayed a solid opaque color even with everything
 * else turned translucent.
 */
private fun ColorScheme.toGlass(alpha: Float): ColorScheme = copy(
    background = Color.Transparent,
    surface = surface.copy(alpha = alpha),
    surfaceContainer = surfaceContainer.copy(alpha = alpha),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = alpha),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = alpha),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = alpha),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = alpha),
    primaryContainer = primaryContainer.copy(alpha = alpha),
    secondaryContainer = secondaryContainer.copy(alpha = alpha),
    tertiaryContainer = tertiaryContainer.copy(alpha = alpha),
    errorContainer = errorContainer.copy(alpha = alpha),
)

/** Overrides the "text on a surface" roles with a single custom font color (Settings > Appearance > "Warna font"). */
private fun ColorScheme.withFontColor(color: Color): ColorScheme = copy(
    onSurface = color,
    onSurfaceVariant = color.copy(alpha = 0.75f),
    onBackground = color,
)

@Composable
fun SirohaFlashToolTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorMode: ColorMode = ColorMode.DYNAMIC,
    presetTheme: PresetThemeId = PresetThemeId.BLUE,
    customSeedColor: Color = Color(0xFF5672CD),
    colorStyle: ColorStyle = ColorStyle.TONAL_SPOT,
    glassMode: Boolean = false,
    cardOpacity: Float = 0.75f,
    cardCornerRadius: Dp = 20.dp,
    fontWeightPreset: FontWeightPreset = FontWeightPreset.REGULAR,
    fontFamilyPreset: FontFamilyPreset = FontFamilyPreset.DEFAULT,
    customFontPath: String? = null,
    fontColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    var colorScheme = when (colorMode) {
        ColorMode.DYNAMIC -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> SirohaDark
            else -> SirohaLight
        }
        // Settings > Appearance > "Warna preset" — one of the 20 palettes ported from FolkPatch.
        ColorMode.PRESET -> {
            val entry = PresetThemes.get(presetTheme)
            if (darkTheme) entry.dark else entry.light
        }
        // Settings > Appearance > "Warna kustom" — generated from a picked seed color via MaterialKolor.
        ColorMode.CUSTOM -> ColorSchemeGenerator.generate(customSeedColor, darkTheme, colorStyle)
    }
    if (themeMode == ThemeMode.AMOLED) {
        colorScheme = colorScheme.toAmoled()
    }
    if (glassMode) {
        colorScheme = colorScheme.toGlass(cardOpacity)
    }
    if (fontColor != null) {
        colorScheme = colorScheme.withFontColor(fontColor)
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
            // In glass mode colorScheme.surface already carries alpha, so
            // these bars end up translucent too instead of a hard opaque
            // seam at the top/bottom of the screen.
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            // Without this, Android (API 29+) paints its own semi-opaque
            // black/white "contrast enforcement" scrim on top of whatever
            // color/alpha we just set — most visible with 3-button
            // navigation, where it renders as a solid dark bar no matter
            // how translucent colorScheme.surface actually is. Turning it
            // off is what lets glass mode's translucency (and the AMOLED
            // true-black) actually reach the screen instead of being
            // painted over.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val resolvedFontFamily: FontFamily = FontLoader.resolve(context, fontFamilyPreset, customFontPath)

    CompositionLocalProvider(
        LocalCardOpacity provides (if (glassMode) cardOpacity else 1f),
        LocalCardCornerRadius provides cardCornerRadius,
        // A flat black drop shadow is nearly invisible against this app's
        // dark surfaces and blurred glass backgrounds — there's not enough
        // luminance difference for the eye to pick up. Using a translucent
        // *light* shadow color instead (only in dark theme; unchanged in
        // light theme, where a normal dark shadow still reads fine) is what
        // actually makes elevation visible, the same way OS-level dark-mode
        // elevation overlays work. (LocalShadowsEnabled/LocalCardElevation
        // themselves are no longer overridden here — the "Shadow" setting
        // was removed, so every consumer's `if (LocalShadowsEnabled.current)
        // ... else 0.dp` now always resolves to 0.dp via that local's own
        // false default, i.e. no shadow, anywhere, ever.)
        LocalCardShadowColor provides (if (darkTheme) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.30f))
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = sirohaTypography(fontWeightPreset, resolvedFontFamily),
            content = content
        )
    }
}
