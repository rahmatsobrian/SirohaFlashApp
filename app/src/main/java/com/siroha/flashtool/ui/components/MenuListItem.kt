package com.siroha.flashtool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.siroha.flashtool.ui.theme.LocalCardCornerRadius
import com.siroha.flashtool.ui.theme.LocalCardElevation
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import com.siroha.flashtool.ui.theme.LocalShadowsEnabled
import com.siroha.flashtool.ui.theme.sirohaCardShadow

/** Outer corner radius for the first/last row of a [MenuListGroup] (and single-row groups). Reused by other screens (e.g. About) that build their own clustered row lists. Falls back to 20dp outside a composition (e.g. constant reuse in non-@Composable code). */
internal val GroupOuterRadius = 20.dp

/** Inner corner radius for rows sandwiched between others — just enough to read as "part of the same group". Scales down with [LocalCardCornerRadius] via [groupRowShape] so a smaller/larger card radius still looks proportionate. */
internal val GroupInnerRadius = 4.dp

/** Gap between rows in a [MenuListGroup] — tight enough that the group still reads as one cluster. */
internal val GroupRowSpacing = 2.dp

/**
 * The clustered-list corner shape (Android 12+ Settings style): big radius
 * on the outward edges, small radius where rows meet. The outer radius
 * comes from [LocalCardCornerRadius] (Settings > Appearance > "Bentuk
 * kartu"); the inner radius scales proportionally so tighter/looser corner
 * settings still read as one visually consistent group.
 */
@Composable
internal fun groupRowShape(index: Int, count: Int): Shape {
    val outer = LocalCardCornerRadius.current
    val inner = (outer * 0.2f).coerceIn(2.dp, 8.dp)
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(
            topStart = outer, topEnd = outer,
            bottomStart = inner, bottomEnd = inner
        )
        index == count - 1 -> RoundedCornerShape(
            topStart = inner, topEnd = inner,
            bottomStart = outer, bottomEnd = outer
        )
        else -> RoundedCornerShape(inner)
    }
}

/** A single, navigable (or informational) entry shown as a row within a [MenuListGroup]. */
data class MenuEntry(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val route: String,
    /** Shows a trailing chevron when true — this entry leads into another screen/menu. */
    val navigable: Boolean = true
)

/** A labeled group of [MenuEntry] items, rendered under a small heading (e.g. "Fastboot"). */
data class MenuSection(
    val title: String,
    val icon: ImageVector,
    val entries: List<MenuEntry>
)

/**
 * One list row (Material 3 "list" pattern — see
 * m3.material.io/components/lists/guidelines): a leading icon in a rounded
 * container, a headline + supporting text block, and — when
 * [MenuEntry.navigable] — a trailing chevron. Each row is its own [Surface]
 * with a [shape] computed by [groupRowShape], so it can carry the clustered
 * big-corner/small-corner look while still sitting apart from its neighbors.
 */
@Composable
private fun MenuListRow(entry: MenuEntry, shape: Shape, onClick: () -> Unit) {
    // 1. Buat tracker interaksi sentuhan
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticTrigger.current
    
    // 2. Tentukan shape dinamis: Jika ditahan, gunakan GroupOuterRadius (20.dp) di semua sudut
    val dynamicShape = if (isPressed) RoundedCornerShape(GroupOuterRadius) else shape

    Surface(
        onClick = { haptic(); onClick() },
        shape = dynamicShape, // Gunakan shape dinamis
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.sirohaCardShadow(if (LocalShadowsEnabled.current) LocalCardElevation.current else 0.dp, dynamicShape),
        interactionSource = interactionSource // Wajib dipasang agar bisa melacak sentuhan
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.subtitle != null) {
                    Text(
                        entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (entry.navigable) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A "clustered" Material 3 list: rows sit apart with a tight [GroupRowSpacing]
 * gap instead of sharing one card, but the group still reads as a single unit
 * because only the outward edges (top of the first row, bottom of the last)
 * get the full [GroupOuterRadius] — everything in between gets a barely-there
 * [GroupInnerRadius], the same shape-morph the Android 12+ Settings app uses.
 */
@Composable
fun MenuListGroup(entries: List<MenuEntry>, onNavigate: (String) -> Unit) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
        entries.forEachIndexed { index, entry ->
            MenuListRow(
                entry = entry,
                shape = groupRowShape(index, entries.size),
                onClick = { onNavigate(entry.route) }
            )
        }
    }
}

/** Adds a [MenuSection] heading followed by one clustered [MenuListGroup], spaced like the rest of a menu tab's LazyColumn. */
fun LazyListScope.menuSection(section: MenuSection, onNavigate: (String) -> Unit) {
    item {
        SectionHeading(section.icon, section.title, modifier = Modifier.padding(start = 4.dp))
    }
    item {
        MenuListGroup(entries = section.entries, onNavigate = onNavigate)
    }
}

/**
 * A single actionable row for an [ActionListGroup] — same row layout as
 * [MenuEntry]/[MenuListRow] (icon-in-circle + headline/supporting text), but
 * triggers [onClick] directly instead of navigating to a route, and has no
 * trailing chevron since it doesn't lead anywhere else. Used for screens
 * that are really "a list of buttons" (e.g. FRP Remove Tool) so they read
 * like the same clustered Material list used for Tools/Guide/Utilities
 * instead of a stack of separate pill buttons.
 */
data class ActionEntry(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun ActionListRow(entry: ActionEntry, shape: Shape) {
    val contentColor = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = LocalHapticTrigger.current
    
    // 1. Tracker interaksi untuk aksi tombol biasa
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val dynamicShape = if (isPressed) RoundedCornerShape(GroupOuterRadius) else shape

    Surface(
        onClick = { haptic(); entry.onClick() },
        enabled = entry.enabled,
        shape = dynamicShape, // Gunakan shape dinamis
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.sirohaCardShadow(if (LocalShadowsEnabled.current) LocalCardElevation.current else 0.dp, dynamicShape),
        interactionSource = interactionSource // Jangan lupa dipasang di sini juga
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (entry.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = if (entry.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.subtitle != null) {
                    Text(
                        entry.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** Clustered list of [ActionEntry] buttons — identical shape-morph treatment to [MenuListGroup]. */
@Composable
fun ActionListGroup(entries: List<ActionEntry>) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
        entries.forEachIndexed { index, entry ->
            ActionListRow(entry = entry, shape = groupRowShape(index, entries.size))
        }
    }
}

/** Simple single clustered list of menu rows with no section grouping (used by the Guide and Utilities tabs). */
@Composable
fun MenuListScreenContent(
    entries: List<MenuEntry>,
    onNavigate: (String) -> Unit,
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (header != null) {
            item { header() }
        }
        item {
            MenuListGroup(entries = entries, onNavigate = onNavigate)
        }
    }
}
