package com.siroha.flashtool.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Drop-in replacement for [Modifier.clickable] that also fires the app's
 * configured tap haptic ([LocalHapticTrigger]) on every tap, in addition to
 * [onClick]. Exists so every touchable surface across the app — menu rows,
 * cards, chips, swatches — gets consistent haptic feedback from one call
 * site instead of each screen wiring `LocalHapticTrigger.current` by hand
 * (which is exactly how most of the app ended up silent: only
 * SettingsScreen's rows called it directly). Deliberately doesn't pass a
 * custom `indication`/`interactionSource` — omitting them keeps `clickable`
 * on its own default ripple, so this stays a pure behavior addition and
 * never risks fighting whatever ripple each call site already had.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticTrigger.current
    this.clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        onClick = {
            haptic()
            onClick()
        }
    )
}

/**
 * Drop-in replacement for [androidx.compose.material3.IconButton] that also
 * fires [LocalHapticTrigger] on tap — used for the many bare icon buttons
 * (refresh, back, connect) scattered across tool screens that otherwise
 * have no shared row component to inherit haptics from.
 */
@Composable
fun HapticIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticTrigger.current
    IconButton(
        onClick = { haptic(); onClick() },
        modifier = modifier,
        enabled = enabled,
        content = content
    )
}

/**
 * Haptic-firing equivalents of Material3's core clickable controls —
 * [Button], [FilledTonalButton], [OutlinedButton], [TextButton], [Switch],
 * [FilterChip] — kept under the *same names* as their Material3
 * counterparts so a screen can opt in with a single import change
 * (`import com.siroha.flashtool.ui.theme.Button` instead of
 * `androidx.compose.material3.Button`) rather than rewriting every call
 * site. This is what let every tool screen's flash/erase/connect buttons
 * pick up haptics without hand-editing dozens of individual onClick blocks,
 * which is exactly how most of the app ended up silent in the first place.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.Button(
        onClick = { haptic(); onClick() },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.FilledTonalButton(
        onClick = { haptic(); onClick() },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: androidx.compose.foundation.BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = true),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.OutlinedButton(
        onClick = { haptic(); onClick() },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.TextButton(
        onClick = { haptic(); onClick() },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors()
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = if (onCheckedChange != null) {
            { newValue -> haptic(); onCheckedChange(newValue) }
        } else null,
        modifier = modifier,
        enabled = enabled,
        colors = colors
    )
}

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.CheckboxColors = androidx.compose.material3.CheckboxDefaults.colors()
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.Checkbox(
        checked = checked,
        onCheckedChange = if (onCheckedChange != null) {
            { newValue -> haptic(); onCheckedChange(newValue) }
        } else null,
        modifier = modifier,
        enabled = enabled,
        colors = colors
    )
}

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val haptic = LocalHapticTrigger.current
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = { haptic(); onClick() },
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon
    )
}
