package com.siroha.flashtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.BackgroundStyle
import com.siroha.flashtool.core.ColorMode
import com.siroha.flashtool.core.ColorStyle
import com.siroha.flashtool.core.FontFamilyPreset
import com.siroha.flashtool.core.FontWeightPreset
import com.siroha.flashtool.core.PresetThemeId
import com.siroha.flashtool.core.ThemeMode
import com.siroha.flashtool.ui.components.AppBackground
import com.siroha.flashtool.ui.navigation.SirohaNavGraph
import com.siroha.flashtool.ui.theme.LocalButtonIndicatorEnabled
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import com.siroha.flashtool.ui.theme.SirohaFlashToolTheme
import com.siroha.flashtool.util.VibrationManager

class MainActivity : ComponentActivity() {

    val app: SirohaApplication by lazy { application as SirohaApplication }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = app.themePreferences
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val colorMode by prefs.colorMode.collectAsState(initial = ColorMode.DYNAMIC)
            val presetTheme by prefs.presetTheme.collectAsState(initial = PresetThemeId.BLUE)
            val customSeedColor by prefs.customSeedColor.collectAsState(initial = Color(0xFF5672CD))
            val colorStyle by prefs.colorStyle.collectAsState(initial = ColorStyle.TONAL_SPOT)
            val backgroundStyle by prefs.backgroundStyle.collectAsState(initial = BackgroundStyle.NONE)
            val customBackgroundPath by prefs.customBackgroundPath.collectAsState(initial = null)
            val backgroundVideoEnabled by prefs.backgroundVideoEnabled.collectAsState(initial = false)
            val backgroundVideoPath by prefs.backgroundVideoPath.collectAsState(initial = null)
            val backgroundVideoSoundEnabled by prefs.backgroundVideoSoundEnabled.collectAsState(initial = false)
            val buttonIndicatorEnabled by prefs.buttonIndicatorEnabled.collectAsState(initial = false)
            val fontWeight by prefs.fontWeight.collectAsState(initial = FontWeightPreset.REGULAR)
            val fontFamily by prefs.fontFamily.collectAsState(initial = FontFamilyPreset.DEFAULT)
            val customFontPath by prefs.customFontPath.collectAsState(initial = null)
            val fontColor by prefs.fontColor.collectAsState(initial = null)

            // Each of these is "whatever's being live-dragged on the
            // Settings screen right now, or the persisted value if nothing's
            // being dragged" — see ThemePreferences' class doc for why the
            // live layer exists (instant slider preview vs. DataStore write
            // latency).
            val persistedBlur by prefs.blurRadius.collectAsState(initial = 20f)
            val liveBlur by prefs.liveBlurRadius.collectAsState()
            val blurRadius = liveBlur ?: persistedBlur

            val persistedCardOpacity by prefs.cardOpacity.collectAsState(initial = 0.75f)
            val liveCardOpacity by prefs.liveCardOpacity.collectAsState()
            val cardOpacity = liveCardOpacity ?: persistedCardOpacity

            val persistedDim by prefs.backgroundDim.collectAsState(initial = 0.28f)
            val liveDim by prefs.liveBackgroundDim.collectAsState()
            val backgroundDim = liveDim ?: persistedDim

            val persistedCorner by prefs.cardCornerRadius.collectAsState(initial = 20f)
            val liveCorner by prefs.liveCardCornerRadius.collectAsState()
            val cardCornerRadius = liveCorner ?: persistedCorner

            // Global tap haptic — read once here (not re-derived per screen)
            // so every touchable element app-wide gets the same tick,
            // instead of only rows inside SettingsScreen having one.
            val vibrationEnabled by prefs.vibrationEnabled.collectAsState(initial = false)
            val vibrationIntensity by prefs.vibrationIntensity.collectAsState(initial = 0.5f)
            val hapticContext = LocalContext.current
            val haptic: () -> Unit = {
                VibrationManager.vibrate(hapticContext, vibrationEnabled, vibrationIntensity)
            }

            val glassMode = backgroundStyle != BackgroundStyle.NONE

            SirohaFlashToolTheme(
                themeMode = themeMode,
                colorMode = colorMode,
                presetTheme = presetTheme,
                customSeedColor = customSeedColor,
                colorStyle = colorStyle,
                glassMode = glassMode,
                cardOpacity = cardOpacity,
                cardCornerRadius = cardCornerRadius.dp,
                fontWeightPreset = fontWeight,
                fontFamilyPreset = fontFamily,
                customFontPath = customFontPath,
                fontColor = fontColor
            ) {
                AppBackground(
                    style = backgroundStyle,
                    customImagePath = customBackgroundPath,
                    blurRadius = blurRadius.dp,
                    dim = backgroundDim,
                    videoEnabled = backgroundVideoEnabled,
                    videoPath = backgroundVideoPath,
                    soundEnabled = backgroundVideoSoundEnabled
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (glassMode) Color.Transparent else MaterialTheme.colorScheme.background
                    ) {
                        CompositionLocalProvider(
                            LocalHapticTrigger provides haptic,
                            LocalButtonIndicatorEnabled provides buttonIndicatorEnabled
                        ) {
                            SirohaNavGraph(
                                logRepository = app.logRepository,
                                themePreferences = app.themePreferences,
                                fastbootOperations = app.fastbootOperations,
                                adbOperations = app.adbOperations
                            )
                        }
                    }
                }
            }
        }
    }
}
