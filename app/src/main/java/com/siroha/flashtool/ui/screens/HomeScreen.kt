package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.navigation.Routes

private data class MenuEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private data class MenuSection(val title: String, val entries: List<MenuEntry>)

private val sections = listOf(
    MenuSection(
        "Qualcomm EDL",
        listOf(
            MenuEntry("QDL Flash (EDL 9008)", "Firehose loader + rawprogram/patch, with partition checklist", Icons.Filled.Bolt, Routes.QDL_FLASH),
            MenuEntry("Bypass UBL — Redmi 4A (rolex)", "Device-specific bootloader-unlock bypass", Icons.Filled.Shield, Routes.BYPASS_UBL),
        )
    ),
    MenuSection(
        "Fastboot",
        listOf(
            MenuEntry("Fastboot Flash Tool", "Flash boot/recovery/vbmeta, reboot, sideload", Icons.Filled.Build, Routes.FASTBOOT),
            MenuEntry("GSI ROM Flash Tool", "Dynamic partitions: erase system, flash GSI", Icons.Filled.RocketLaunch, Routes.GSI_TOOL),
            MenuEntry("A/B Partition Tool", "Slot-aware flashing, active slot switch", Icons.Filled.SwapHoriz, Routes.AB_PARTITION),
            MenuEntry("FRP Remove Tool", "Erase persist (fastboot); ADB-based methods noted as unsupported", Icons.Filled.Lock, Routes.FRP_TOOL),
        )
    ),
    MenuSection(
        "Utilities",
        listOf(
            MenuEntry("Requirements & Status", "Root / Shizuku / USB — what's ready, what isn't", Icons.Filled.TaskAlt, Routes.REQUIREMENTS),
            MenuEntry("USB / OTG Fix", "Device list, permission status, troubleshooting", Icons.Filled.Cable, Routes.USB_FIX),
            MenuEntry("Logs", "View and share the session log", Icons.Filled.Article, Routes.LOGS),
            MenuEntry("Settings", "Root / Shizuku execution mode", Icons.Filled.Settings, Routes.SETTINGS),
        )
    ),
    MenuSection(
        "Info",
        listOf(
            MenuEntry("Guide", "Step-by-step: EDL, fastboot, GSI, wiring qdl", Icons.Filled.MenuBook, Routes.GUIDE),
            MenuEntry("About", "Credits, links, version, disclaimer", Icons.Filled.Info, Routes.ABOUT),
        )
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Siroha Flash Tool") }) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding)
        ) {
            sections.forEach { section ->
                item {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                    )
                }
                items(section.entries) { entry ->
                    Card(
                        onClick = { onNavigate(entry.route) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { Text(entry.title) },
                            supportingContent = { Text(entry.subtitle) },
                            leadingContent = { Icon(entry.icon, contentDescription = null) }
                        )
                    }
                }
            }
        }
    }
}
