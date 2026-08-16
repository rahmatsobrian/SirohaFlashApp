package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.ui.components.DeviceStatusCard
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.navigation.Routes

private data class MenuEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private data class MenuSection(val icon: ImageVector, val title: String, val entries: List<MenuEntry>)

private val sections = listOf(
    MenuSection(
        Icons.Filled.Bolt, "Qualcomm EDL",
        listOf(
            MenuEntry("QDL Flash (EDL 9008)", "Firehose loader + rawprogram/patch, with partition checklist", Icons.Filled.Bolt, Routes.QDL_FLASH),
            MenuEntry("Bypass UBL — Redmi 4A (rolex)", "Device-specific bootloader-unlock bypass", Icons.Filled.Shield, Routes.BYPASS_UBL),
            MenuEntry("Guide", "EDL mode, QDL flashing, wiring up qdl for a new ABI", Icons.Filled.MenuBook, Routes.GUIDE_EDL),
        )
    ),
    MenuSection(
        Icons.Filled.Build, "Fastboot",
        listOf(
            MenuEntry("Fastboot Flash Tool", "Flash boot/recovery/vbmeta, reboot, manual command", Icons.Filled.Build, Routes.FASTBOOT),
            MenuEntry("GSI ROM Flash Tool", "Dynamic partitions: erase system, flash GSI", Icons.Filled.RocketLaunch, Routes.GSI_TOOL),
            MenuEntry("A/B Partition Tool", "Slot-aware flashing, active slot switch", Icons.Filled.SwapHoriz, Routes.AB_PARTITION),
            MenuEntry("FRP Remove Tool", "SPRD via fastboot; Samsung/SPRD-MTK via ADB", Icons.Filled.Lock, Routes.FRP_TOOL),
            MenuEntry("Guide", "Fastboot flashing, GSI sequence, A/B, FRP, manual command syntax", Icons.Filled.MenuBook, Routes.GUIDE_FASTBOOT),
        )
    ),
    MenuSection(
        Icons.Filled.Extension, "Xiaomi (MiTool)",
        listOf(
            MenuEntry("Mi Unlock", "Log in, then unlock the bootloader via Xiaomi's account API", Icons.Filled.LockOpen, Routes.MIUNLOCK),
            MenuEntry("MiTool", "Flash fastboot ROM by folder, extract a file from a ROM ZIP", Icons.Filled.Extension, Routes.MITOOL),
            MenuEntry("Guide", "Mi Unlock steps, Flash Fastboot ROM, Firmware Content Extractor", Icons.Filled.MenuBook, Routes.GUIDE_MITOOL),
        )
    ),
    MenuSection(
        Icons.Filled.Settings, "Utilities",
        listOf(
            MenuEntry("Requirements & Status", "Root / Shizuku / USB — what's ready, what isn't", Icons.Filled.TaskAlt, Routes.REQUIREMENTS),
            MenuEntry("USB / OTG Fix", "Device list, permission status, troubleshooting", Icons.Filled.Cable, Routes.USB_FIX),
            MenuEntry("Logs", "View and share the session log", Icons.Filled.Article, Routes.LOGS),
            MenuEntry("Settings", "Theme, execution backend, and more", Icons.Filled.Settings, Routes.SETTINGS),
        )
    ),
    MenuSection(
        Icons.Filled.Info, "Info",
        listOf(
            MenuEntry("About", "Credits, links, version, disclaimer", Icons.Filled.Info, Routes.ABOUT),
        )
    ),
)

@Composable
fun HomeScreen(
    executorProvider: ExecutorProvider,
    fastbootOperations: FastbootOperations,
    adbOperations: AdbOperations,
    onNavigate: (String) -> Unit
) {
    Scaffold(
        topBar = { SirohaTopBar("Siroha Flash Tool") }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding)
        ) {
            item { DeviceStatusCard(executorProvider, fastbootOperations, adbOperations) }

            item {
                // Differentiator card: orients a first-time user to the two
                // fundamentally different transports this app drives (EDL's
                // qdl-over-shell vs fastboot/ADB-over-raw-USB) before they
                // pick a tool, so "QDL Flash" vs "Fastboot Flash Tool" reads
                // as an intentional choice rather than two similar-looking
                // menu items.
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        headlineContent = { Text("Two transports, one app", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                "Qualcomm EDL (bricked/9008 mode) uses the qdl tool via root/Shizuku. " +
                                    "Fastboot and ADB modes talk directly over USB — no root needed for those.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    )
                }
            }

            // Each menu group is its own physically separate Card — not just
            // a text label — so the boundary between e.g. "Qualcomm EDL" and
            // "Fastboot" is unmistakable rather than reading as one long list.
            items(sections) { section ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)
                        ) {
                            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(section.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        section.entries.forEachIndexed { index, entry ->
                            // A custom Row instead of ListItem: Material3's ListItem
                            // spec top-aligns the leading icon whenever there's a
                            // 2-line (headline + supporting) body, which reads oddly
                            // when the subtitle wraps to two lines — this keeps the
                            // icon vertically centered against the whole text block
                            // regardless of how many lines it wraps to.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(entry.route) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(entry.icon, contentDescription = null)
                                Column {
                                    Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (index != section.entries.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
