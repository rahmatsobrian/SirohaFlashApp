package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.components.MenuSection
import com.siroha.flashtool.ui.components.MenuEntry
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.menuSection
import com.siroha.flashtool.ui.navigation.Routes

private val toolSections = listOf(
    MenuSection(
        "Qualcomm EDL", Icons.Filled.Bolt,
        listOf(
            MenuEntry("QDL Flash (EDL 9008)", "Firehose loader + rawprogram/patch, with partition checklist", Icons.Filled.Bolt, Routes.QDL_FLASH),
            MenuEntry("Bypass UBL - Redmi 4A (rolex)", "Device-specific bootloader-unlock bypass", Icons.Filled.Shield, Routes.BYPASS_UBL),
        )
    ),
    MenuSection(
        "Fastboot", Icons.Filled.Build,
        listOf(
            MenuEntry("Fastboot Flash Tool", "Flash boot/recovery/vbmeta, reboot, manual command", Icons.Filled.Build, Routes.FASTBOOT),
            MenuEntry("GSI ROM Flash Tool", "Dynamic partitions: erase system, flash GSI", Icons.Filled.RocketLaunch, Routes.GSI_TOOL),
            MenuEntry("A/B Partition Tool", "Slot-aware flashing, active slot switch", Icons.Filled.SwapHoriz, Routes.AB_PARTITION),
            MenuEntry("FRP Remove Tool", "SPRD via fastboot; Samsung/SPRD-MTK via ADB", Icons.Filled.Lock, Routes.FRP_TOOL),
        )
    ),
    MenuSection(
        "ADB", Icons.Filled.Terminal,
        listOf(
            MenuEntry("ADB Tools", "Connect, run shell/ADB commands, and sideload ZIPs", Icons.Filled.Terminal, Routes.ADB),
        )
    ),
    MenuSection(
        "Xiaomi (MiTool)", Icons.Filled.Extension,
        listOf(
            MenuEntry("Mi Unlock", "Log in, then unlock the bootloader via Xiaomi's account API", Icons.Filled.LockOpen, Routes.MIUNLOCK),
            MenuEntry("MiTool", "Flash fastboot ROM by folder, extract a file from a ROM ZIP", Icons.Filled.Extension, Routes.MITOOL),
        )
    ),
)

@Composable
fun ToolsScreen(
    onNavigate: (String) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SirohaTopBar("Tools", icon = Icons.Filled.Build) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding)
        ) {
            toolSections.forEach { section -> menuSection(section, onNavigate) }
        }
    }
}
