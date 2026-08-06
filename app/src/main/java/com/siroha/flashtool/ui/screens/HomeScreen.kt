package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

private val menuEntries = listOf(
    MenuEntry("QDL Flash (EDL 9008)", "Flash via firehose loader + rawprogram/patch XML", Icons.Filled.Bolt, Routes.QDL_FLASH),
    MenuEntry("Bypass UBL — Redmi 4A (rolex)", "Device-specific bootloader-unlock bypass", Icons.Filled.Shield, Routes.BYPASS_UBL),
    MenuEntry("Logs", "View and share the session log", Icons.Filled.Article, Routes.LOGS),
    MenuEntry("Settings", "Root / Shizuku execution mode", Icons.Filled.Settings, Routes.SETTINGS),
)

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Siroha Flash Tool") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(menuEntries) { entry ->
                    Card(
                        onClick = { onNavigate(entry.route) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
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
