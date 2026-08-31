package com.siroha.flashtool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import com.siroha.flashtool.ui.theme.LocalCardElevation
import com.siroha.flashtool.ui.theme.LocalShadowsEnabled
import com.siroha.flashtool.ui.theme.HapticIconButton
import com.siroha.flashtool.ui.theme.sirohaCardShadow

/**
 * Compact top bar used on every screen instead of Material3's default
 * TopAppBar, which reserves noticeably more vertical space (a taller
 * container plus titleLarge-sized text) than this app needs. 52dp content
 * height + a smaller title style keeps more of the screen for actual
 * content.
 */
@Composable
fun SirohaTopBar(
    title: String,
    icon: ImageVector? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.sirohaCardShadow(if (LocalShadowsEnabled.current) LocalCardElevation.current else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                HapticIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            }
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = actions)
        }
    }
}

/**
 * Small icon + label row used as a section heading throughout the app
 * (Home's category labels, card headers in About/Settings/tool screens) so
 * every section reads consistently instead of plain bold text everywhere.
 */
@Composable
fun SectionHeading(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
