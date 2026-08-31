package com.siroha.flashtool.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drop shadow using [LocalCardShadowColor] instead of Compose's hardcoded
 * black — the actual fix for "shadow strength does nothing" (see
 * [LocalCardShadowColor]'s doc comment). [Surface]'s `shadowElevation`
 * param can't be recolored, only turned on/off and sized, so components
 * that need a shadow that's actually visible in dark theme apply this
 * modifier instead and pass `shadowElevation = 0.dp` to their Surface/Card
 * to avoid stacking two shadows.
 *
 * No-ops (renders no shadow at all) when [elevation] is 0dp, so callers can
 * pass `LocalCardElevation.current` unconditionally without checking
 * [LocalShadowsEnabled] themselves.
 */
@Composable
fun Modifier.sirohaCardShadow(
    elevation: Dp,
    shape: Shape = RoundedCornerShape(0.dp)
): Modifier {
    if (elevation <= 0.dp) return this
    val color = LocalCardShadowColor.current
    return this.shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = color,
        spotColor = color
    )
}
