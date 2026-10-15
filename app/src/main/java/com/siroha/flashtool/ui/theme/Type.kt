package com.siroha.flashtool.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.siroha.flashtool.core.FontWeightPreset

/**
 * Builds [Typography] for a given [FontWeightPreset] — Settings > Appearance
 * > "Ketebalan font". Titles stay two steps bolder and labels one step
 * bolder than body text at every preset, so the preset shifts the whole
 * app's weight up or down while keeping the same visual hierarchy between
 * headings and body copy.
 */
fun sirohaTypography(preset: FontWeightPreset = FontWeightPreset.REGULAR, fontFamily: FontFamily = FontFamily.Default): Typography {
    val bodyWeight = FontWeight(preset.bodyWeight)
    val titleWeight = FontWeight((preset.bodyWeight + 200).coerceAtMost(900))
    val labelWeight = FontWeight((preset.bodyWeight + 100).coerceAtMost(900))
    // Every style that is NOT redefined below (display*, headline*, titleMedium/Small, bodySmall,
    // labelMedium/Small) used to fall back to Material's defaults, which have fontFamily = Default.
    // Those are the styles LargeTopAppBar (headlineMedium), NavigationBar labels (labelMedium),
    // dialog titles (headlineSmall) and all the bodySmall/titleSmall texts use, so the chosen
    // font never reached them. Keep Material's size/weight for them, only swap the family.
    // (Named `family` on purpose: inside TextStyle.() a bare `fontFamily` would be the receiver's own.)
    val family = fontFamily
    val base = Typography()
    fun TextStyle.withFamily() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.withFamily(),
        displayMedium = base.displayMedium.withFamily(),
        displaySmall = base.displaySmall.withFamily(),
        headlineLarge = base.headlineLarge.withFamily(),
        headlineMedium = base.headlineMedium.withFamily(),
        headlineSmall = base.headlineSmall.withFamily(),
        titleMedium = base.titleMedium.withFamily(),
        titleSmall = base.titleSmall.withFamily(),
        bodySmall = base.bodySmall.withFamily(),
        labelMedium = base.labelMedium.withFamily(),
        labelSmall = base.labelSmall.withFamily(),
        titleLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = titleWeight,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = bodyWeight,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.5.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = bodyWeight,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp
        ),
        labelLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = labelWeight,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        )
    )
}

/** Default-weight typography — kept as a top-level val since a couple of call sites reference it directly instead of going through [sirohaTypography]. */
val SirohaTypography = sirohaTypography(FontWeightPreset.REGULAR)
