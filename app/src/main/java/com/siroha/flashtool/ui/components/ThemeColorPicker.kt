package com.siroha.flashtool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.ColorStyle
import com.siroha.flashtool.core.PresetThemeId
import com.siroha.flashtool.ui.theme.PresetThemeEntry
import com.siroha.flashtool.ui.theme.PresetThemes
import com.siroha.flashtool.ui.theme.LocalHapticTrigger

private fun Color.readableLuminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

/** One tappable swatch, shared by the preset grid and the custom seed-color row. */
@Composable
private fun ColorSwatch(color: Color, label: String?, selected: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticTrigger.current
    Surface(
        shape = CircleShape,
        color = color,
        onClick = { haptic(); onClick() },
        modifier = Modifier.size(44.dp)
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = label ?: "Selected",
                    tint = if (color.readableLuminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Grid of the 20 preset theme swatches (Settings > Appearance > "Warna
 * preset"), ported from FolkPatch's theme picker. Two scrollable rows so
 * all 20 fit without a huge vertical footprint.
 */
@Composable
fun PresetThemeGrid(selected: PresetThemeId, onSelect: (PresetThemeId) -> Unit) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(108.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
    ) {
        items(PresetThemes.entries) { entry: PresetThemeEntry ->
            ColorSwatch(
                color = entry.swatch,
                label = entry.label,
                selected = selected == entry.id,
                onClick = { onSelect(entry.id) }
            )
        }
    }
}

// Seed colors offered as quick-pick swatches for Settings > Appearance >
// "Warna kustom", alongside free-form hex entry (reusing the app's existing
// CustomFontColorInput pattern from SettingsScreen).
private val seedColorSwatches = listOf(
    Color(0xFF5672CD), Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF43A047),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF3949AB),
)

@Composable
fun SeedColorRow(selected: Color, onSelect: (Color) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        seedColorSwatches.forEach { swatch ->
            ColorSwatch(color = swatch, label = null, selected = selected == swatch, onClick = { onSelect(swatch) })
        }
    }
}

private fun ColorStyle.label(): String = when (this) {
    ColorStyle.TONAL_SPOT -> "Tonal Spot"
    ColorStyle.VIBRANT -> "Vibrant"
    ColorStyle.EXPRESSIVE -> "Expressive"
    ColorStyle.RAINBOW -> "Rainbow"
    ColorStyle.FRUIT_SALAD -> "Fruit Salad"
    ColorStyle.MONOCHROME -> "Monochrome"
    ColorStyle.NEUTRAL -> "Neutral"
    ColorStyle.CONTENT -> "Content"
    ColorStyle.FIDELITY -> "Fidelity"
}

/** Chip row for picking the MaterialKolor [ColorStyle] used to expand a custom seed color into a full palette. */
@Composable
fun ColorStyleRow(selected: ColorStyle, onSelect: (ColorStyle) -> Unit) {
    val haptic = LocalHapticTrigger.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ColorStyle.entries.forEach { style ->
            val isSelected = style == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { haptic(); onSelect(style) }
            ) {
                Text(
                    style.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}
