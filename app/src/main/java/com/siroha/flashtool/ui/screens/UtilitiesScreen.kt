package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.siroha.flashtool.ui.components.MenuEntry
import com.siroha.flashtool.ui.components.MenuListScreenContent
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.navigation.Routes

private val utilityEntries = listOf(
    MenuEntry("Requirements & Status", "qdl binary / USB - what's ready, what isn't", Icons.Filled.TaskAlt, Routes.REQUIREMENTS),
    MenuEntry("USB / OTG Fix", "Device list, permission status, troubleshooting", Icons.Filled.Cable, Routes.USB_FIX),
    MenuEntry("Logs", "View and share the session log", Icons.Filled.Article, Routes.LOGS),
    MenuEntry("Settings", "Theme, execution backend, and more", Icons.Filled.Settings, Routes.SETTINGS),
    MenuEntry("About", "Credits, links, version, disclaimer", Icons.Filled.Info, Routes.ABOUT),
)

@Composable
fun UtilitiesScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { SirohaTopBar("Utilities", icon = Icons.Filled.Widgets) }
    ) { padding ->
        MenuListScreenContent(
            entries = utilityEntries,
            onNavigate = onNavigate,
            modifier = Modifier.padding(padding)
        )
    }
}
