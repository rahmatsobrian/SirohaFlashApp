package com.siroha.flashtool.core

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * The app's background layer, set from Settings > Appearance.
 * - [NONE]: normal opaque screens (the app's original look).
 * - [CUSTOM]: translucent cards over a person-picked image (or looping
 *   video) instead of an opaque background.
 */
enum class BackgroundStyle { NONE, CUSTOM }

/** Font weight preset for Settings > Appearance > "Ketebalan font". */
enum class FontWeightPreset(val bodyWeight: Int) {
    LIGHT(300), REGULAR(400), MEDIUM(500), SEMIBOLD(600), BOLD(700)
}

/**
 * How the app's color scheme is chosen (Settings > Appearance > "Warna").
 * - [DYNAMIC]: Material You, generated from the device wallpaper (Android 12+).
 * - [PRESET]: one of the 20 hand-tuned palettes ported from FolkPatch — see
 *   [com.siroha.flashtool.ui.theme.PresetThemes].
 * - [CUSTOM]: generated from a person-picked seed color, via
 *   [com.siroha.flashtool.ui.theme.ColorSchemeGenerator] (MaterialKolor).
 */
enum class ColorMode { DYNAMIC, PRESET, CUSTOM }

/** The 20 preset palettes ported from FolkPatch. Display name/swatch/scheme live in [com.siroha.flashtool.ui.theme.PresetThemes]. */
enum class PresetThemeId {
    RED, PINK, SAKURA, PURPLE, DEEP_PURPLE, INDIGO, BLUE, LIGHT_BLUE, CYAN, TEAL,
    GREEN, LIGHT_GREEN, LIME, YELLOW, AMBER, ORANGE, DEEP_ORANGE, BROWN, BLUE_GREY, INK_WASH
}

/** MaterialKolor palette style used when [ColorMode.CUSTOM] is active — how far the generated tones spread from the seed color. */
enum class ColorStyle {
    TONAL_SPOT, VIBRANT, EXPRESSIVE, RAINBOW, FRUIT_SALAD, MONOCHROME, NEUTRAL, CONTENT, FIDELITY
}

/** Font family preset for Settings > Appearance > "Font". [CUSTOM] loads a person-picked .ttf/.otf file — see [customFontPath]. */
enum class FontFamilyPreset { DEFAULT, SERIF, MONOSPACE, CUSTOM }

private val Context.themeDataStore by preferencesDataStore(name = "siroha_theme_prefs")

/**
 * Persists the user's theme choice: System / Light / Dark / AMOLED (pure
 * black), Material You dynamic color, and the Appearance settings — clear/
 * custom wallpaper background, blur radius, and font weight/color. Read by
 * [com.siroha.flashtool.ui.theme.SirohaFlashToolTheme] and
 * [com.siroha.flashtool.ui.components.AppBackground], written from the
 * Settings screen.
 */
class ThemePreferences(private val context: Context) {

    // ---- In-memory "while dragging" preview state ----
    // The Slider rows on the Settings screen read/write these directly on
    // every frame of a drag (see `SliderRow`'s onValueChange), completely
    // bypassing DataStore. That's what makes the blur/dim/corner-radius/
    // shadow preview track the finger continuously instead of jumping once
    // at release: a DataStore write is a suspend disk operation, so driving
    // the live visual off it (as this used to work) meant every value
    // change queued through a coroutine + Preferences-file write before the
    // UI could react — slow and stuttery. A plain in-memory MutableStateFlow
    // has none of that latency. `previewX()` updates the live value
    // immediately; the real persisted setter (`setX()`) is still called
    // separately once the drag finishes, so the choice survives an app
    // restart. Each live flow is nullable — null means "nothing being
    // dragged right now, use the persisted value" (see MainActivity, which
    // combines `liveX ?: persistedX`).
    private val _liveBlurRadius = MutableStateFlow<Float?>(null)
    val liveBlurRadius: StateFlow<Float?> = _liveBlurRadius
    fun previewBlurRadius(dp: Float?) { _liveBlurRadius.value = dp }

    private val _liveCardOpacity = MutableStateFlow<Float?>(null)
    val liveCardOpacity: StateFlow<Float?> = _liveCardOpacity
    fun previewCardOpacity(opacity: Float?) { _liveCardOpacity.value = opacity }

    private val _liveBackgroundDim = MutableStateFlow<Float?>(null)
    val liveBackgroundDim: StateFlow<Float?> = _liveBackgroundDim
    fun previewBackgroundDim(dim: Float?) { _liveBackgroundDim.value = dim }

    private val _liveCardCornerRadius = MutableStateFlow<Float?>(null)
    val liveCardCornerRadius: StateFlow<Float?> = _liveCardCornerRadius
    fun previewCardCornerRadius(dp: Float?) { _liveCardCornerRadius.value = dp }

    companion object {
        private val KEY_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_BACKGROUND_STYLE = stringPreferencesKey("background_style")
        private val KEY_CUSTOM_BACKGROUND_PATH = stringPreferencesKey("custom_background_path")
        private val KEY_BLUR_RADIUS = floatPreferencesKey("blur_radius")
        private val KEY_CARD_OPACITY = floatPreferencesKey("card_opacity")
        private val KEY_FONT_WEIGHT = stringPreferencesKey("font_weight")
        private val KEY_FONT_COLOR = longPreferencesKey("font_color_argb")

        // Sentinel meaning "no custom font color set — use the theme default".
        // (0L would collide with a legitimate fully-transparent-black color,
        // but nobody would ever intentionally pick that as a font color, so
        // it's a safe, simple sentinel here rather than reaching for a
        // second boolean key just to mark "is this present".)
        private const val NO_FONT_COLOR = 0L

        // --- Color mode (dynamic / preset / custom) ---
        private val KEY_COLOR_MODE = stringPreferencesKey("color_mode")
        private val KEY_PRESET_THEME = stringPreferencesKey("preset_theme")
        private val KEY_SEED_COLOR = longPreferencesKey("custom_seed_color_argb")
        private val KEY_COLOR_STYLE = stringPreferencesKey("color_style")

        // --- Advanced background ---
        private val KEY_BG_DIM = floatPreferencesKey("background_dim")
        private val KEY_BG_VIDEO_ENABLED = booleanPreferencesKey("background_video_enabled")
        private val KEY_BG_VIDEO_PATH = stringPreferencesKey("background_video_path")
        private val KEY_BG_VIDEO_SOUND_ENABLED = booleanPreferencesKey("background_video_sound_enabled")

        // --- Button indicator (FolkPatch-style status icon on switch rows) ---
        private val KEY_BUTTON_INDICATOR_ENABLED = booleanPreferencesKey("button_indicator_enabled")

        // --- Card styling ---
        private val KEY_CARD_CORNER_RADIUS = floatPreferencesKey("card_corner_radius")

        // --- Font family ---
        private val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        private val KEY_CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")

        // --- Vibration feedback ---
        private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val KEY_VIBRATION_INTENSITY = floatPreferencesKey("vibration_intensity")
    }

    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[KEY_MODE] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColorEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: true
    }

    val backgroundStyle: Flow<BackgroundStyle> = context.themeDataStore.data.map { prefs ->
        runCatching { BackgroundStyle.valueOf(prefs[KEY_BACKGROUND_STYLE] ?: BackgroundStyle.NONE.name) }
            .getOrDefault(BackgroundStyle.NONE)
    }

    val customBackgroundPath: Flow<String?> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_BACKGROUND_PATH]
    }

    /** Blur radius in dp, 0-40. Only visible/meaningful when [backgroundStyle] isn't [BackgroundStyle.NONE]. */
    val blurRadius: Flow<Float> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_BLUR_RADIUS] ?: 20f
    }

    /** Card/surface opacity, 0-1, used only when [backgroundStyle] isn't [BackgroundStyle.NONE] — controls how much of the blurred background shows through cards. */
    val cardOpacity: Flow<Float> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_CARD_OPACITY] ?: 0.75f
    }

    val fontWeight: Flow<FontWeightPreset> = context.themeDataStore.data.map { prefs ->
        runCatching { FontWeightPreset.valueOf(prefs[KEY_FONT_WEIGHT] ?: FontWeightPreset.REGULAR.name) }
            .getOrDefault(FontWeightPreset.REGULAR)
    }

    /** null = use the theme's default text color for the current mode/scheme. */
    val fontColor: Flow<Color?> = context.themeDataStore.data.map { prefs ->
        val argb = prefs[KEY_FONT_COLOR] ?: NO_FONT_COLOR
        if (argb == NO_FONT_COLOR) null else Color(argb.toInt())
    }

    val colorMode: Flow<ColorMode> = context.themeDataStore.data.map { prefs ->
        runCatching { ColorMode.valueOf(prefs[KEY_COLOR_MODE] ?: ColorMode.DYNAMIC.name) }.getOrDefault(ColorMode.DYNAMIC)
    }

    val presetTheme: Flow<PresetThemeId> = context.themeDataStore.data.map { prefs ->
        runCatching { PresetThemeId.valueOf(prefs[KEY_PRESET_THEME] ?: PresetThemeId.BLUE.name) }.getOrDefault(PresetThemeId.BLUE)
    }

    /** Seed color for [ColorMode.CUSTOM], defaulting to the app's own brand blue. */
    val customSeedColor: Flow<Color> = context.themeDataStore.data.map { prefs ->
        Color(prefs[KEY_SEED_COLOR]?.toInt() ?: 0xFF5672CD.toInt())
    }

    val colorStyle: Flow<ColorStyle> = context.themeDataStore.data.map { prefs ->
        runCatching { ColorStyle.valueOf(prefs[KEY_COLOR_STYLE] ?: ColorStyle.TONAL_SPOT.name) }.getOrDefault(ColorStyle.TONAL_SPOT)
    }

    /** Darkening overlay strength (0-1) drawn over the background image. */
    val backgroundDim: Flow<Float> = context.themeDataStore.data.map { prefs -> prefs[KEY_BG_DIM] ?: 0.28f }

    /** When on, [backgroundVideoPath] plays as a looping video background instead of a static image. Only meaningful when [backgroundStyle] is [BackgroundStyle.CUSTOM]. */
    val backgroundVideoEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs -> prefs[KEY_BG_VIDEO_ENABLED] ?: false }

    /** Path to a person-picked video file, used when [backgroundVideoEnabled] is on. */
    val backgroundVideoPath: Flow<String?> = context.themeDataStore.data.map { prefs -> prefs[KEY_BG_VIDEO_PATH] }

    /**
     * When on, the video background plays with its original audio instead
     * of muted. Off by default — a background clip suddenly playing sound
     * is surprising, so this is an explicit opt-in separate from just
     * turning the video background on. Only meaningful when
     * [backgroundVideoEnabled] is also on.
     */
    val backgroundVideoSoundEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs -> prefs[KEY_BG_VIDEO_SOUND_ENABLED] ?: false }

    /** When on, switch rows show a small check/X icon inside the thumb itself (FolkPatch's "Indikator Tombol"), on top of the usual on/off color change. */
    val buttonIndicatorEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs -> prefs[KEY_BUTTON_INDICATOR_ENABLED] ?: false }

    /** Corner radius (dp) for grouped list rows and cards app-wide — see `groupRowShape` in MenuListItem.kt. */
    val cardCornerRadius: Flow<Float> = context.themeDataStore.data.map { prefs -> prefs[KEY_CARD_CORNER_RADIUS] ?: 20f }

    val fontFamily: Flow<FontFamilyPreset> = context.themeDataStore.data.map { prefs ->
        runCatching { FontFamilyPreset.valueOf(prefs[KEY_FONT_FAMILY] ?: FontFamilyPreset.DEFAULT.name) }.getOrDefault(FontFamilyPreset.DEFAULT)
    }

    /** Path to a person-picked .ttf/.otf file, used when [fontFamily] is [FontFamilyPreset.CUSTOM]. */
    val customFontPath: Flow<String?> = context.themeDataStore.data.map { prefs -> prefs[KEY_CUSTOM_FONT_PATH] }

    val vibrationEnabled: Flow<Boolean> = context.themeDataStore.data.map { prefs -> prefs[KEY_VIBRATION_ENABLED] ?: false }

    /** Haptic amplitude, 0-1, mapped to 1-255 by [com.siroha.flashtool.util.VibrationManager]. */
    val vibrationIntensity: Flow<Float> = context.themeDataStore.data.map { prefs -> prefs[KEY_VIBRATION_INTENSITY] ?: 0.5f }

    suspend fun setColorMode(mode: ColorMode) {
        context.themeDataStore.edit { it[KEY_COLOR_MODE] = mode.name }
    }

    suspend fun setPresetTheme(id: PresetThemeId) {
        context.themeDataStore.edit { it[KEY_PRESET_THEME] = id.name }
    }

    suspend fun setCustomSeedColor(color: Color) {
        context.themeDataStore.edit { it[KEY_SEED_COLOR] = color.toArgbLong() }
    }

    suspend fun setColorStyle(style: ColorStyle) {
        context.themeDataStore.edit { it[KEY_COLOR_STYLE] = style.name }
    }

    suspend fun setBackgroundDim(dim: Float) {
        context.themeDataStore.edit { it[KEY_BG_DIM] = dim.coerceIn(0f, 1f) }
    }

    suspend fun setBackgroundVideoEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_BG_VIDEO_ENABLED] = enabled }
    }

    suspend fun setBackgroundVideoPath(path: String?) {
        context.themeDataStore.edit {
            if (path == null) it.remove(KEY_BG_VIDEO_PATH) else it[KEY_BG_VIDEO_PATH] = path
        }
    }

    suspend fun setBackgroundVideoSoundEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_BG_VIDEO_SOUND_ENABLED] = enabled }
    }

    suspend fun setButtonIndicatorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_BUTTON_INDICATOR_ENABLED] = enabled }
    }

    suspend fun setCardCornerRadius(dp: Float) {
        context.themeDataStore.edit { it[KEY_CARD_CORNER_RADIUS] = dp.coerceIn(0f, 32f) }
    }

    suspend fun setFontFamily(preset: FontFamilyPreset) {
        context.themeDataStore.edit { it[KEY_FONT_FAMILY] = preset.name }
    }

    suspend fun setCustomFontPath(path: String?) {
        context.themeDataStore.edit {
            if (path == null) it.remove(KEY_CUSTOM_FONT_PATH) else it[KEY_CUSTOM_FONT_PATH] = path
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_VIBRATION_ENABLED] = enabled }
    }

    suspend fun setVibrationIntensity(intensity: Float) {
        context.themeDataStore.edit { it[KEY_VIBRATION_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setBackgroundStyle(style: BackgroundStyle) {
        context.themeDataStore.edit { it[KEY_BACKGROUND_STYLE] = style.name }
    }

    suspend fun setCustomBackgroundPath(path: String?) {
        context.themeDataStore.edit {
            if (path == null) it.remove(KEY_CUSTOM_BACKGROUND_PATH) else it[KEY_CUSTOM_BACKGROUND_PATH] = path
        }
    }

    suspend fun setBlurRadius(dp: Float) {
        context.themeDataStore.edit { it[KEY_BLUR_RADIUS] = dp.coerceIn(0f, 50f) }
    }

    suspend fun setCardOpacity(opacity: Float) {
        context.themeDataStore.edit { it[KEY_CARD_OPACITY] = opacity.coerceIn(0f, 1f) }
    }

    suspend fun setFontWeight(preset: FontWeightPreset) {
        context.themeDataStore.edit { it[KEY_FONT_WEIGHT] = preset.name }
    }

    /** Pass null to reset to the theme default. */
    suspend fun setFontColor(color: Color?) {
        context.themeDataStore.edit {
            if (color == null) it[KEY_FONT_COLOR] = NO_FONT_COLOR else it[KEY_FONT_COLOR] = color.toArgbLong()
        }
    }
}

private fun Color.toArgbLong(): Long = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
).toLong()
