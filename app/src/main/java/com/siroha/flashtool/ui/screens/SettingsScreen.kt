package com.siroha.flashtool.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.BackgroundStyle
import com.siroha.flashtool.core.ColorMode
import com.siroha.flashtool.core.ColorStyle
import com.siroha.flashtool.core.FontFamilyPreset
import com.siroha.flashtool.core.FontWeightPreset
import com.siroha.flashtool.core.PresetThemeId
import com.siroha.flashtool.core.ThemeMode
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.core.WallpaperUtils
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ColorStyleRow
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.PresetThemeGrid
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SeedColorRow
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.groupRowShape
import com.siroha.flashtool.ui.theme.LocalCardCornerRadius
import com.siroha.flashtool.ui.theme.LocalCardElevation
import com.siroha.flashtool.ui.theme.LocalButtonIndicatorEnabled
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import com.siroha.flashtool.ui.theme.LocalShadowsEnabled
import com.siroha.flashtool.ui.theme.PresetThemes
import com.siroha.flashtool.util.VibrationManager
import kotlinx.coroutines.launch

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}

private fun BackgroundStyle.label() = when (this) {
    BackgroundStyle.NONE -> "Normal"
    BackgroundStyle.CUSTOM -> "Custom"
}

private fun FontWeightPreset.label() = when (this) {
    FontWeightPreset.LIGHT -> "Light"
    FontWeightPreset.REGULAR -> "Regular"
    FontWeightPreset.MEDIUM -> "Medium"
    FontWeightPreset.SEMIBOLD -> "Semibold"
    FontWeightPreset.BOLD -> "Bold"
}

private fun ColorMode.label() = when (this) {
    ColorMode.DYNAMIC -> "Dynamic"
    ColorMode.PRESET -> "Preset"
    ColorMode.CUSTOM -> "Custom"
}

private fun ColorMode.subtitle() = when (this) {
    ColorMode.DYNAMIC -> "Match your phone wallpaper (Android 12+)"
    ColorMode.PRESET -> "Pick from 20 hand-tuned palettes"
    ColorMode.CUSTOM -> "Generate a palette from any color you pick"
}

private fun FontFamilyPreset.label() = when (this) {
    FontFamilyPreset.DEFAULT -> "Default"
    FontFamilyPreset.SERIF -> "Serif"
    FontFamilyPreset.MONOSPACE -> "Monospace"
    FontFamilyPreset.CUSTOM -> "Custom"
}

// Same accent used for the Home tab's "Active" banner, so a granted backend
// reads as the same "good state" color everywhere in the app.
private val ActiveGreen = Color(0xFF84D996)

// Swatches offered for Settings > Appearance > "Font color", alongside the
// custom hex input. Kept small and hand-picked (not a full color wheel) so
// picking a legible font color stays mostly a one-tap thing.
private val fontColorSwatches = listOf(
    Color(0xFF212121), // near-black
    Color(0xFFFFFFFF), // white
    Color(0xFF5672CD), // brand blue
    Color(0xFFE53935), // red
    Color(0xFFFFB300), // amber
    Color(0xFF43A047), // green
    Color(0xFF8E24AA), // purple
    Color(0xFF00ACC1), // cyan
)

/** One selectable row (radio-button list item pattern - m3.material.io/components/radio-button). */
@Composable
private fun SelectableRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    shape: Shape,
    onClick: () -> Unit
) {
    val haptic = LocalHapticTrigger.current
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = { haptic(); onClick() }, role = Role.RadioButton)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The standard Settings card container: reads corner radius from
 * [LocalCardCornerRadius] (the "Card corner radius" slider below) so every
 * card in this screen - not just the top bar and grouped radio-button lists
 * - actually responds to it. Shadow elevation always resolves to 0dp: the
 * "Shadow" setting was removed, and [LocalShadowsEnabled] defaults to false
 * with nothing overriding it anymore.
 */
@Composable
private fun SettingsSurface(
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(LocalCardCornerRadius.current)
    val elevation = if (LocalShadowsEnabled.current) LocalCardElevation.current else 0.dp
    if (onClick != null) {
        Surface(shape = shape, color = color, shadowElevation = elevation, onClick = onClick, content = content)
    } else {
        Surface(shape = shape, color = color, shadowElevation = elevation, content = content)
    }
}

/** Leading-icon list row with a trailing switch (Material You toggle) - matches [com.siroha.flashtool.ui.components.MenuListRow]'s look so it reads as part of the same design system. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticTrigger.current
    SettingsSurface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            val showIndicator = LocalButtonIndicatorEnabled.current
            Switch(
                checked = checked,
                onCheckedChange = { haptic(); onCheckedChange(it) },
                thumbContent = if (showIndicator) {
                    {
                        Icon(
                            imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else null
            )
        }
    }
}

/**
 * A labeled slider row. [steps] adds discrete stops (e.g. 9 = 11 positions,
 * a "level 0-10" feel) - combined with a short haptic tick each time the
 * drag crosses into a new step, so the vibration follows the finger's
 * movement instead of firing once on release. That tick is normally the
 * person's standard configured tap haptic (via [LocalHapticTrigger]); pass
 * [onStep] to fire something else per step instead - used by the
 * "Vibration strength" slider itself, so each step vibrates at the
 * intensity being dragged to rather than the old, not-yet-committed one.
 */
@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 9,
    onStep: ((Float) -> Unit)? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val haptic = LocalHapticTrigger.current
    var lastStep by remember { mutableStateOf(-1) }
    SettingsSurface {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = value,
                onValueChange = {
                    val fraction = ((it - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    val currentStep = kotlin.math.round(fraction * steps).toInt().coerceIn(0, steps)
                    if (currentStep != lastStep) {
                        lastStep = currentStep
                        if (onStep != null) onStep(it) else haptic()
                    }
                    onValueChange(it)
                },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                steps = steps
            )
        }
    }
}

/** Horizontal row of tappable pills - used for the font weight preset picker. */
@Composable
private fun <T> ChipSelectorRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                onClick = { onSelect(option) }
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Row of tappable color swatches - used for the custom font color picker, plus a "reset to default" pill. */
@Composable
private fun FontColorRow(selected: Color?, onSelect: (Color?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = { onSelect(null) }
        ) {
            Text(
                "Default",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        fontColorSwatches.forEach { swatch ->
            val isSelected = selected == swatch
            Surface(
                shape = CircleShape,
                color = swatch,
                onClick = { onSelect(swatch) },
                modifier = Modifier.size(36.dp)
            ) {
                if (isSelected) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = if (swatch.luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Parses a "#RRGGBB" or "RRGGBB" hex string into a [Color], or null if it isn't valid 6-digit hex. */
private fun parseHexColor(hex: String): Color? {
    val cleaned = hex.removePrefix("#").trim()
    if (cleaned.length != 6 || cleaned.any { it !in "0123456789abcdefABCDEF" }) return null
    return runCatching { Color(("FF$cleaned").toLong(16)) }.getOrNull()
}

private fun Color.toHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return "%02X%02X%02X".format(r, g, b)
}

/** Free-form hex code entry for a font color, with a live preview swatch - for anyone who wants an exact color instead of one of the [fontColorSwatches]. */
@Composable
private fun CustomFontColorInput(selected: Color?, onApply: (Color) -> Unit) {
    var text by remember(selected) { mutableStateOf(selected?.toHex() ?: "") }
    val parsed = parseHexColor(text)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = parsed ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp)
        ) {}
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(7) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Hex, e.g. #5672CD") },
            isError = text.isNotEmpty() && parsed == null
        )
        com.siroha.flashtool.ui.theme.FilledTonalButton(
            onClick = { parsed?.let(onApply) },
            enabled = parsed != null
        ) {
            Text("Apply")
        }
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

@Composable
fun SettingsScreen(
    themePreferences: ThemePreferences,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val themeMode by themePreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val colorMode by themePreferences.colorMode.collectAsState(initial = ColorMode.DYNAMIC)
    val presetTheme by themePreferences.presetTheme.collectAsState(initial = PresetThemeId.BLUE)
    val customSeedColor by themePreferences.customSeedColor.collectAsState(initial = Color(0xFF5672CD))
    val colorStyle by themePreferences.colorStyle.collectAsState(initial = ColorStyle.TONAL_SPOT)
    val backgroundStyle by themePreferences.backgroundStyle.collectAsState(initial = BackgroundStyle.NONE)
    val customBackgroundPath by themePreferences.customBackgroundPath.collectAsState(initial = null)
    val blurRadius by themePreferences.blurRadius.collectAsState(initial = 20f)
    val cardOpacity by themePreferences.cardOpacity.collectAsState(initial = 0.75f)
    val backgroundDim by themePreferences.backgroundDim.collectAsState(initial = 0.28f)
    val backgroundVideoEnabled by themePreferences.backgroundVideoEnabled.collectAsState(initial = false)
    val backgroundVideoPath by themePreferences.backgroundVideoPath.collectAsState(initial = null)
    val backgroundVideoSoundEnabled by themePreferences.backgroundVideoSoundEnabled.collectAsState(initial = false)
    val buttonIndicatorEnabled by themePreferences.buttonIndicatorEnabled.collectAsState(initial = false)
    val cardCornerRadius by themePreferences.cardCornerRadius.collectAsState(initial = 20f)
    val fontWeight by themePreferences.fontWeight.collectAsState(initial = FontWeightPreset.REGULAR)
    val fontColor by themePreferences.fontColor.collectAsState(initial = null)
    val fontFamily by themePreferences.fontFamily.collectAsState(initial = FontFamilyPreset.DEFAULT)
    val customFontPath by themePreferences.customFontPath.collectAsState(initial = null)
    val vibrationEnabled by themePreferences.vibrationEnabled.collectAsState(initial = false)
    val vibrationIntensity by themePreferences.vibrationIntensity.collectAsState(initial = 0.5f)

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    WallpaperUtils.persistPickedVideo(context, uri)
                }
                if (path != null) {
                    themePreferences.setBackgroundVideoPath(path)
                    snackbarHostState.showSnackbar("Background video updated")
                } else {
                    snackbarHostState.showSnackbar("Couldn't load that video")
                }
            }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.siroha.flashtool.ui.theme.FontLoader.persistPickedFont(context, uri)
                }
                if (path != null) {
                    themePreferences.setCustomFontPath(path)
                    themePreferences.setFontFamily(FontFamilyPreset.CUSTOM)
                    snackbarHostState.showSnackbar("Font updated")
                } else {
                    snackbarHostState.showSnackbar("Couldn't load that font file")
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WallpaperUtils.persistPickedImage(context, uri)
                }
                if (path != null) {
                    themePreferences.setCustomBackgroundPath(path)
                    if (backgroundStyle == BackgroundStyle.NONE) {
                        themePreferences.setBackgroundStyle(BackgroundStyle.CUSTOM)
                    }
                    snackbarHostState.showSnackbar("Wallpaper updated")
                } else {
                    snackbarHostState.showSnackbar("Couldn't load that image")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { SirohaTopBar("Settings", icon = Icons.Filled.Settings, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ---- Appearance ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Palette, "Appearance", modifier = Modifier.padding(start = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SelectableRow(
                                title = mode.label(),
                                selected = themeMode == mode,
                                shape = groupRowShape(index, ThemeMode.entries.size),
                                onClick = { scope.launch { themePreferences.setThemeMode(mode) } }
                            )
                        }
                    }

                    Text(
                        "Color",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        ColorMode.entries.forEachIndexed { index, mode ->
                            SelectableRow(
                                title = mode.label(),
                                subtitle = mode.subtitle(),
                                selected = colorMode == mode,
                                shape = groupRowShape(index, ColorMode.entries.size),
                                onClick = { scope.launch { themePreferences.setColorMode(mode) } }
                            )
                        }
                    }

                    if (colorMode == ColorMode.PRESET) {
                        SettingsSurface {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    PresetThemes.get(presetTheme).label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                PresetThemeGrid(
                                    selected = presetTheme,
                                    onSelect = {
                                        scope.launch { themePreferences.setPresetTheme(it) }
                                        VibrationManager.vibrate(context, vibrationEnabled, vibrationIntensity)
                                    }
                                )
                            }
                        }
                    }

                    if (colorMode == ColorMode.CUSTOM) {
                        SettingsSurface {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Seed color", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                SeedColorRow(
                                    selected = customSeedColor,
                                    onSelect = { scope.launch { themePreferences.setCustomSeedColor(it) } }
                                )
                                Text(
                                    "Or enter an exact color:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                CustomFontColorInput(
                                    selected = customSeedColor,
                                    onApply = { scope.launch { themePreferences.setCustomSeedColor(it) } }
                                )
                                Text("Palette style", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                ColorStyleRow(
                                    selected = colorStyle,
                                    onSelect = { scope.launch { themePreferences.setColorStyle(it) } }
                                )
                            }
                        }
                    }
                }
            }

            // ---- Background / wallpaper ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Image, "Background", modifier = Modifier.padding(start = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        SelectableRow(
                            title = BackgroundStyle.NONE.label(),
                            subtitle = "Solid screens, like before",
                            selected = backgroundStyle == BackgroundStyle.NONE,
                            shape = groupRowShape(0, 2),
                            onClick = { scope.launch { themePreferences.setBackgroundStyle(BackgroundStyle.NONE) } }
                        )
                        SelectableRow(
                            title = BackgroundStyle.CUSTOM.label(),
                            subtitle = "Pick your own image as the app's background",
                            selected = backgroundStyle == BackgroundStyle.CUSTOM,
                            shape = groupRowShape(1, 2),
                            onClick = {
                                scope.launch { themePreferences.setBackgroundStyle(BackgroundStyle.CUSTOM) }
                            }
                        )
                    }

                    if (backgroundStyle == BackgroundStyle.CUSTOM) {
                        SettingsSurface(
                            onClick = {
                                imagePickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (customBackgroundPath == null) "Pilih wallpaper Kustom" else "Ganti wallpaper Kustom",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    if (backgroundStyle != BackgroundStyle.NONE) {
                        var draggedBlur by remember(blurRadius) { mutableStateOf(blurRadius) }
                        SliderRow(
                            title = "Blur",
                            valueLabel = "${draggedBlur.toInt()}dp",
                            value = draggedBlur,
                            range = 0f..50f,
                            steps = 24, // 2dp per step across the 0..50 range (26 stops: 0,2,4...50)
                            onValueChange = { draggedBlur = it; themePreferences.previewBlurRadius(it) },
                            onValueChangeFinished = { scope.launch { themePreferences.setBlurRadius(draggedBlur) } }
                        )

                        var draggedTransparency by remember(cardOpacity) { mutableStateOf(1f - cardOpacity) }
                        SliderRow(
                            title = "Card transparency",
                            valueLabel = "${(draggedTransparency * 100).toInt()}%",
                            value = draggedTransparency,
                            range = 0f..1f,
                            steps = 19, // 5% per step (0,5,10...100 = 21 stops)
                            onValueChange = { draggedTransparency = it; themePreferences.previewCardOpacity(1f - it) },
                            onValueChangeFinished = { scope.launch { themePreferences.setCardOpacity(1f - draggedTransparency) } }
                        )

                        var draggedDim by remember(backgroundDim) { mutableStateOf(backgroundDim) }
                        SliderRow(
                            title = "Dim",
                            valueLabel = "${(draggedDim * 100).toInt()}%",
                            value = draggedDim,
                            range = 0f..1f,
                            steps = 19, // 5% per step (0,5,10...100 = 21 stops)
                            onValueChange = { draggedDim = it; themePreferences.previewBackgroundDim(it) },
                            onValueChangeFinished = { scope.launch { themePreferences.setBackgroundDim(draggedDim) } }
                        )

                        if (backgroundStyle == BackgroundStyle.CUSTOM) {
                            SwitchRow(
                                title = "Latar Belakang Video",
                                subtitle = "Gunakan video sebagai latar belakang",
                                icon = Icons.Filled.Videocam,
                                checked = backgroundVideoEnabled,
                                onCheckedChange = { scope.launch { themePreferences.setBackgroundVideoEnabled(it) } }
                            )
                            if (backgroundVideoEnabled) {
                                SettingsSurface(
                                    onClick = {
                                        videoPickerLauncher.launch(arrayOf("video/*"))
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            if (backgroundVideoPath == null) "Pilih video latar belakang" else "Ganti video latar belakang",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                if (backgroundVideoPath != null) {
                                    SwitchRow(
                                        title = "Suara Video",
                                        subtitle = "Putar audio asli video, bukan bisu",
                                        icon = if (backgroundVideoSoundEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                        checked = backgroundVideoSoundEnabled,
                                        onCheckedChange = { scope.launch { themePreferences.setBackgroundVideoSoundEnabled(it) } }
                                    )
                                }
                            }
                        }
                    }

                    var draggedCorner by remember(cardCornerRadius) { mutableStateOf(cardCornerRadius) }
                    SliderRow(
                        title = "Card corner radius",
                        valueLabel = "${draggedCorner.toInt()}dp",
                        value = draggedCorner,
                        range = 0f..32f,
                        steps = 31, // 1dp per step across the 0..32 range
                        onValueChange = { draggedCorner = it; themePreferences.previewCardCornerRadius(it) },
                        onValueChangeFinished = { scope.launch { themePreferences.setCardCornerRadius(draggedCorner) } }
                    )

                    SwitchRow(
                        title = "Indikator Tombol",
                        subtitle = "Tampilkan ikon status pada tombol saklar",
                        icon = Icons.Filled.CheckCircle,
                        checked = buttonIndicatorEnabled,
                        onCheckedChange = { scope.launch { themePreferences.setButtonIndicatorEnabled(it) } }
                    )
                }
            }

            // ---- Font ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.TextFields, "Font", modifier = Modifier.padding(start = 4.dp))
                    SettingsSurface {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Font family", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            ChipSelectorRow(
                                options = FontFamilyPreset.entries,
                                selected = fontFamily,
                                label = { it.label() },
                                onSelect = {
                                    if (it == FontFamilyPreset.CUSTOM) {
                                        fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "*/*"))
                                    } else {
                                        scope.launch { themePreferences.setFontFamily(it) }
                                    }
                                }
                            )
                            if (fontFamily == FontFamilyPreset.CUSTOM) {
                                Text(
                                    if (customFontPath == null) "No font file picked yet" else "Custom font loaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    SettingsSurface {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Font weight", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            ChipSelectorRow(
                                options = FontWeightPreset.entries,
                                selected = fontWeight,
                                label = { it.label() },
                                onSelect = { scope.launch { themePreferences.setFontWeight(it) } }
                            )
                        }
                    }
                    SettingsSurface {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Font color", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            FontColorRow(
                                selected = fontColor,
                                onSelect = { scope.launch { themePreferences.setFontColor(it) } }
                            )
                            Text(
                                "Or enter an exact color:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CustomFontColorInput(
                                selected = fontColor,
                                onApply = { scope.launch { themePreferences.setFontColor(it) } }
                            )
                        }
                    }
                }
            }

            // ---- Vibration ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Vibration, "Vibration", modifier = Modifier.padding(start = 4.dp))
                    SwitchRow(
                        title = "Vibration feedback",
                        subtitle = "Short haptic tick when picking a theme or option",
                        icon = Icons.Filled.Vibration,
                        checked = vibrationEnabled,
                        onCheckedChange = { scope.launch { themePreferences.setVibrationEnabled(it) } }
                    )
                    if (vibrationEnabled) {
                        var draggedIntensity by remember(vibrationIntensity) { mutableStateOf(vibrationIntensity) }
                        SliderRow(
                            title = "Vibration strength",
                            valueLabel = "${(draggedIntensity * 100).toInt()}%",
                            value = draggedIntensity,
                            range = 0.1f..1f,
                            steps = 17, // 5% per step across the 10%..100% range (19 stops)
                            // Each step ticks at the intensity being dragged
                            // to (not the old committed one), so the person
                            // can feel exactly what they're about to set.
                            onStep = { VibrationManager.vibrate(context, true, it) },
                            onValueChange = { draggedIntensity = it },
                            onValueChangeFinished = { scope.launch { themePreferences.setVibrationIntensity(draggedIntensity) } }
                        )
                    }
                }
            }

            // ---- Data ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Storage, "Data", modifier = Modifier.padding(start = 4.dp))
                    Surface(
                        shape = RoundedCornerShape(LocalCardCornerRadius.current),
                        color = MaterialTheme.colorScheme.errorContainer,
                        onClick = {
                            logRepository.clear()
                            scope.launch { snackbarHostState.showSnackbar("Session logs cleared") }
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "Clear session logs",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
