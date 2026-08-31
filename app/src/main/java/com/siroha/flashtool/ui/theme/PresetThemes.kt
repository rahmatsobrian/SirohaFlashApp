package com.siroha.flashtool.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.siroha.flashtool.core.PresetThemeId
import com.siroha.flashtool.ui.theme.presets.DarkAmberTheme
import com.siroha.flashtool.ui.theme.presets.DarkBlueGreyTheme
import com.siroha.flashtool.ui.theme.presets.DarkBlueTheme
import com.siroha.flashtool.ui.theme.presets.DarkBrownTheme
import com.siroha.flashtool.ui.theme.presets.DarkCyanTheme
import com.siroha.flashtool.ui.theme.presets.DarkDeepOrangeTheme
import com.siroha.flashtool.ui.theme.presets.DarkDeepPurpleTheme
import com.siroha.flashtool.ui.theme.presets.DarkGreenTheme
import com.siroha.flashtool.ui.theme.presets.DarkIndigoTheme
import com.siroha.flashtool.ui.theme.presets.DarkInkWashTheme
import com.siroha.flashtool.ui.theme.presets.DarkLightBlueTheme
import com.siroha.flashtool.ui.theme.presets.DarkLightGreenTheme
import com.siroha.flashtool.ui.theme.presets.DarkLimeTheme
import com.siroha.flashtool.ui.theme.presets.DarkOrangeTheme
import com.siroha.flashtool.ui.theme.presets.DarkPinkTheme
import com.siroha.flashtool.ui.theme.presets.DarkPurpleTheme
import com.siroha.flashtool.ui.theme.presets.DarkRedTheme
import com.siroha.flashtool.ui.theme.presets.DarkSakuraTheme
import com.siroha.flashtool.ui.theme.presets.DarkTealTheme
import com.siroha.flashtool.ui.theme.presets.DarkYellowTheme
import com.siroha.flashtool.ui.theme.presets.LightAmberTheme
import com.siroha.flashtool.ui.theme.presets.LightBlueGreyTheme
import com.siroha.flashtool.ui.theme.presets.LightBlueTheme
import com.siroha.flashtool.ui.theme.presets.LightBrownTheme
import com.siroha.flashtool.ui.theme.presets.LightCyanTheme
import com.siroha.flashtool.ui.theme.presets.LightDeepOrangeTheme
import com.siroha.flashtool.ui.theme.presets.LightDeepPurpleTheme
import com.siroha.flashtool.ui.theme.presets.LightGreenTheme
import com.siroha.flashtool.ui.theme.presets.LightIndigoTheme
import com.siroha.flashtool.ui.theme.presets.LightInkWashTheme
import com.siroha.flashtool.ui.theme.presets.LightLightBlueTheme
import com.siroha.flashtool.ui.theme.presets.LightLightGreenTheme
import com.siroha.flashtool.ui.theme.presets.LightLimeTheme
import com.siroha.flashtool.ui.theme.presets.LightOrangeTheme
import com.siroha.flashtool.ui.theme.presets.LightPinkTheme
import com.siroha.flashtool.ui.theme.presets.LightPurpleTheme
import com.siroha.flashtool.ui.theme.presets.LightRedTheme
import com.siroha.flashtool.ui.theme.presets.LightSakuraTheme
import com.siroha.flashtool.ui.theme.presets.LightTealTheme
import com.siroha.flashtool.ui.theme.presets.LightYellowTheme

/**
 * One preset's display name, picker swatch color, and its light/dark
 * [ColorScheme] pair. Ported from FolkPatch's 20 hand-tuned Material3
 * theme files (see [com.siroha.flashtool.ui.theme.presets]).
 */
data class PresetThemeEntry(
    val id: PresetThemeId,
    val label: String,
    val swatch: Color,
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** Central lookup used by the preset picker UI and by [SirohaFlashToolTheme] to resolve [PresetThemeId] -> [ColorScheme]. */
object PresetThemes {
    val entries: List<PresetThemeEntry> = listOf(
        PresetThemeEntry(PresetThemeId.RED, "Red", Color(0xFFBA1A1A), LightRedTheme, DarkRedTheme),
        PresetThemeEntry(PresetThemeId.PINK, "Pink", Color(0xFFC2185B), LightPinkTheme, DarkPinkTheme),
        PresetThemeEntry(PresetThemeId.SAKURA, "Sakura", Color(0xFFEB98B8), LightSakuraTheme, DarkSakuraTheme),
        PresetThemeEntry(PresetThemeId.PURPLE, "Purple", Color(0xFF7B1FA2), LightPurpleTheme, DarkPurpleTheme),
        PresetThemeEntry(PresetThemeId.DEEP_PURPLE, "Deep Purple", Color(0xFF512DA8), LightDeepPurpleTheme, DarkDeepPurpleTheme),
        PresetThemeEntry(PresetThemeId.INDIGO, "Indigo", Color(0xFF303F9F), LightIndigoTheme, DarkIndigoTheme),
        PresetThemeEntry(PresetThemeId.BLUE, "Blue", Color(0xFF0061A4), LightBlueTheme, DarkBlueTheme),
        PresetThemeEntry(PresetThemeId.LIGHT_BLUE, "Light Blue", Color(0xFF0288D1), LightLightBlueTheme, DarkLightBlueTheme),
        PresetThemeEntry(PresetThemeId.CYAN, "Cyan", Color(0xFF00838F), LightCyanTheme, DarkCyanTheme),
        PresetThemeEntry(PresetThemeId.TEAL, "Teal", Color(0xFF00695C), LightTealTheme, DarkTealTheme),
        PresetThemeEntry(PresetThemeId.GREEN, "Green", Color(0xFF2E7D32), LightGreenTheme, DarkGreenTheme),
        PresetThemeEntry(PresetThemeId.LIGHT_GREEN, "Light Green", Color(0xFF689F38), LightLightGreenTheme, DarkLightGreenTheme),
        PresetThemeEntry(PresetThemeId.LIME, "Lime", Color(0xFF9E9D24), LightLimeTheme, DarkLimeTheme),
        PresetThemeEntry(PresetThemeId.YELLOW, "Yellow", Color(0xFFF9A825), LightYellowTheme, DarkYellowTheme),
        PresetThemeEntry(PresetThemeId.AMBER, "Amber", Color(0xFFFF8F00), LightAmberTheme, DarkAmberTheme),
        PresetThemeEntry(PresetThemeId.ORANGE, "Orange", Color(0xFFEF6C00), LightOrangeTheme, DarkOrangeTheme),
        PresetThemeEntry(PresetThemeId.DEEP_ORANGE, "Deep Orange", Color(0xFFD84315), LightDeepOrangeTheme, DarkDeepOrangeTheme),
        PresetThemeEntry(PresetThemeId.BROWN, "Brown", Color(0xFF5D4037), LightBrownTheme, DarkBrownTheme),
        PresetThemeEntry(PresetThemeId.BLUE_GREY, "Blue Grey", Color(0xFF455A64), LightBlueGreyTheme, DarkBlueGreyTheme),
        PresetThemeEntry(PresetThemeId.INK_WASH, "Ink Wash", Color(0xFF37474F), LightInkWashTheme, DarkInkWashTheme),
    )

    private val byId = entries.associateBy { it.id }

    fun get(id: PresetThemeId): PresetThemeEntry = byId.getValue(id)
}
