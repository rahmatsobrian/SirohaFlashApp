package com.siroha.flashtool.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Whether cards and bars should render a drop shadow (Settings > Appearance
 * > "Shadow"). Read by [com.siroha.flashtool.ui.components.MenuListItem]'s
 * rows, [com.siroha.flashtool.ui.components.DeviceStatusCard], and the top/
 * bottom bars. Defaults to false to match the app's original flat design —
 * turning it on is opt-in, not a silent visual change for people who never
 * touch the setting.
 */
val LocalShadowsEnabled = compositionLocalOf { false }

/**
 * The Appearance > "Card transparency" value (1f = fully opaque, 0f =
 * invisible), exposed so hand-tinted status colors — like the green/red
 * "Active"/"Not active" banners on Home, Requirements, and Settings — can
 * scale their own background alpha to match instead of staying at a fixed
 * alpha regardless of the slider. Those banners use a literal Color (not a
 * ColorScheme role), so [com.siroha.flashtool.ui.theme.SirohaFlashToolTheme]'s
 * blanket surface-alpha override in glass mode can't reach them automatically.
 * Defaults to 1f (fully opaque) outside glass mode.
 */
val LocalCardOpacity = compositionLocalOf { 1f }

/**
 * Outer corner radius for grouped list rows and cards app-wide (Settings >
 * Appearance > "Bentuk kartu"). Read by `groupRowShape()` in
 * [com.siroha.flashtool.ui.components.MenuListItem], so changing this one
 * value reshapes every clustered row list in the app at once — Home,
 * Settings, Requirements, etc. Defaults to 20dp, matching the app's
 * original hardcoded radius.
 */
val LocalCardCornerRadius = compositionLocalOf { 20.dp }

/**
 * Drop-shadow elevation applied when [LocalShadowsEnabled] is on (Settings >
 * Appearance > "Shadow"). Read by the same handful of shared components as
 * [LocalShadowsEnabled] — [com.siroha.flashtool.ui.components.SirohaTopBar],
 * `MenuListItem` rows, [com.siroha.flashtool.ui.components.DeviceStatusCard],
 * and [com.siroha.flashtool.ui.components.SirohaBottomBar] — so the shadow's
 * *strength*, not just its on/off state, is user-adjustable. Defaults to
 * 3dp, matching the app's previous fixed value.
 */
val LocalCardElevation = compositionLocalOf { 3.dp }

/**
 * The tint used for the shadow itself, not just its strength. A plain black
 * shadow (Compose's default) has almost no visible contrast against this
 * app's dark surfaces or a dark/blurred wallpaper background — the "Card
 * shadow strength" slider could be dragged all the way up and nothing would
 * visibly change. Set to a translucent light color in dark theme (mimicking
 * how OS-level dark-mode elevation overlays work) and a normal translucent
 * black in light theme. Provided once in [SirohaFlashToolTheme]; read by
 * `Modifier.sirohaCardShadow()` wherever a shadow needs to actually be
 * visible instead of relying on [androidx.compose.material3.Surface]'s
 * built-in shadowElevation, which always renders black regardless of theme.
 */
val LocalCardShadowColor = compositionLocalOf { Color.Black.copy(alpha = 0.30f) }

/**
 * Fires the person's configured tap haptic (Settings > Appearance >
 * "Vibration"), or does nothing if it's off/unavailable. Provided once at
 * the top of `SettingsScreen` (built from the current `vibrationEnabled`/
 * `vibrationIntensity` values) so every selectable row, switch, and slider
 * step underneath it gets consistent tap feedback without each call site
 * needing its own copy of those two values — the earlier version only
 * wired this up in one or two spots (a preset-theme grid, a slider's release
 * handler), so picking a theme mode or flipping most switches stayed silent.
 */
val LocalHapticTrigger = compositionLocalOf<() -> Unit> { {} }

/**
 * Whether switch rows should show a small check/X status icon inside the
 * thumb itself (Settings > Appearance > "Indikator Tombol", ported from
 * FolkPatch), on top of the usual on/off color change. Provided once at the
 * app root in MainActivity, same as [LocalHapticTrigger], so every
 * `SwitchRow` reflects it without each screen threading the value through
 * manually. Defaults to false — off, matching every switch's plain look
 * before this setting existed.
 */
val LocalButtonIndicatorEnabled = compositionLocalOf { false }
