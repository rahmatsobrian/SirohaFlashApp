package com.siroha.flashtool.ui.theme

import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.siroha.flashtool.core.ColorStyle

/**
 * Generates a [ColorScheme] from a person-picked seed color, for Settings >
 * Appearance > "Custom color" (Settings > Appearance > "Warna kustom").
 * Ported from FolkPatch's `ColorSchemeGenerator`, trimmed to the single
 * MD3-2021 color spec (FolkPatch also offers the newer M3E-2025 spec, which
 * this app doesn't expose as a separate setting).
 *
 * Small LRU cache so dragging the same seed color / style combo repeatedly
 * (e.g. scrubbing a color picker) doesn't recompute the full tonal palette
 * on every recomposition.
 */
object ColorSchemeGenerator {

    private val cache = LruCache<String, ColorScheme>(8)

    fun generate(seedColor: Color, isDark: Boolean, style: ColorStyle): ColorScheme {
        val key = "${seedColor.value}_${style.name}_$isDark"
        val cached = cache.get(key)
        if (cached != null) return cached
        val generated = dynamicColorScheme(
            seedColor = seedColor,
            isDark = isDark,
            isAmoled = false,
            style = style.toPaletteStyle(),
        )
        cache.put(key, generated)
        return generated
    }

    fun invalidateCache() {
        cache.evictAll()
    }
}

private fun ColorStyle.toPaletteStyle(): PaletteStyle = when (this) {
    ColorStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ColorStyle.VIBRANT -> PaletteStyle.Vibrant
    ColorStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ColorStyle.RAINBOW -> PaletteStyle.Rainbow
    ColorStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    ColorStyle.MONOCHROME -> PaletteStyle.Monochrome
    ColorStyle.NEUTRAL -> PaletteStyle.Neutral
    ColorStyle.CONTENT -> PaletteStyle.Content
    ColorStyle.FIDELITY -> PaletteStyle.Fidelity
}
