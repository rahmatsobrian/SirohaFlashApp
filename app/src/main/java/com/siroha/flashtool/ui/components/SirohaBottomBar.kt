package com.siroha.flashtool.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.navigation.Routes
import com.siroha.flashtool.ui.theme.LocalCardElevation
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import com.siroha.flashtool.ui.theme.LocalShadowsEnabled

private data class BottomTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomTab(Routes.TOOLS, "Tools", Icons.Filled.Build, Icons.Outlined.Build),
    BottomTab(Routes.GUIDE, "Guide", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    BottomTab(Routes.UTILITIES, "Utilities", Icons.Filled.Widgets, Icons.Outlined.Widgets),
)

/**
 * Bottom navigation bar, built directly on Material3's stock NavigationBar /
 * NavigationBarItem — same building blocks KernelSU Manager's own bottom bar
 * uses — instead of a hand-rolled Row/Column/Surface. This is what gives the
 * reference look for free: a flat bar flush with the screen, a filled icon
 * swapped in only on the selected tab, and Material's own small pill
 * indicator hugging just the icon (not the label) on the selected tab.
 */
@Composable
fun SirohaBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    val haptic = LocalHapticTrigger.current
    NavigationBar(
        // Match the screen background exactly (not surfaceContainer, which
        // is a visibly lighter tone) so the bar blends in with no seam.
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = if (LocalShadowsEnabled.current) LocalCardElevation.current else 0.dp,
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        )
    ) {
        bottomTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { haptic(); onNavigate(tab.route) },
                icon = {
                    Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label
                    )
                },
                label = {
                    Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            )
        }
    }
}
