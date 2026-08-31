package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.siroha.flashtool.ui.components.MenuEntry
import com.siroha.flashtool.ui.components.MenuListScreenContent
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.navigation.Routes

private val guideEntries = listOf(
    MenuEntry(
        "Guide - Qualcomm EDL", "EDL mode, QDL flashing, wiring up qdl for a new ABI",
        Icons.Filled.Bolt, Routes.GUIDE_EDL
    ),
    MenuEntry(
        "Guide - Fastboot", "Fastboot flashing, GSI sequence, A/B, FRP, manual command syntax",
        Icons.Filled.Build, Routes.GUIDE_FASTBOOT
    ),
    MenuEntry(
        "Guide - MiTool", "Mi Unlock steps, Flash Fastboot ROM, Firmware Content Extractor",
        Icons.Filled.Extension, Routes.GUIDE_MITOOL
    ),
)

@Composable
fun GuideTabScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SirohaTopBar("Guide", icon = Icons.Filled.MenuBook) }
    ) { padding ->
        MenuListScreenContent(
            entries = guideEntries,
            onNavigate = onNavigate,
            modifier = Modifier.padding(padding)
        )
    }
}
