package com.siroha.flashtool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.BuildConfig
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.groupRowShape
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import androidx.compose.foundation.layout.PaddingValues

private data class AboutLink(val label: String, val url: String, val icon: ImageVector)

private val creditLinks = listOf(
    AboutLink("Open GitHub", "https://github.com/rahmatsobrian", Icons.Filled.OpenInNew),
    AboutLink("Open Telegram", "https://t.me/rahmatsobrian", Icons.Filled.Send)
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    val haptic = LocalHapticTrigger.current

    Scaffold(
        topBar = { SirohaTopBar("About", icon = Icons.Filled.Info, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Column {
                        Text("Siroha Flash Tool", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Text(
                    "A modern, native Qualcomm flashing solution for Android. Built from the ground up with Material 3 and Dynamic Color, SirohaFlashTool communicates with supported devices directly through USB without requiring root, Shizuku, Termux, or any external environment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.People, "Credits", modifier = Modifier.padding(start = 4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Original tool: Siroha (github.com/rahmatsobrian)", style = MaterialTheme.typography.bodyMedium)
                            Text("Bypass UBL Redmi 4A: Rahmat Sobrian", style = MaterialTheme.typography.bodyMedium)
                            Text("MiTool: offici5l (github.com/offici5l/MiTool)", style = MaterialTheme.typography.bodyMedium)
                            Text("QDL-NonRoot: ADBify", style = MaterialTheme.typography.bodyMedium)
                            Text("OtherTool: Ishu43642 (github.com/Ishu43642)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // Clustered link list — same big/small/big corner-morph as the
                    // Tools/Guide menus, so it reads as "part of one list" instead
                    // of two disconnected clickable rows.
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        creditLinks.forEachIndexed { index, link ->
                            Surface(
                                onClick = { haptic(); open(link.url) },
                                shape = groupRowShape(index, creditLinks.size),
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        link.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(link.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Warning, "Disclaimer", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            "Flashing firmware, unlocking bootloaders, and removing FRP can permanently " +
                                "damage a device if used incorrectly, and can be illegal if used on a " +
                                "device you don't own or aren't authorized to service. Use only on your " +
                                "own hardware or with explicit authorization.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Block, "Not implementable here", modifier = Modifier.padding(start = 4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Text(
"• Mi Assistant - unavailable due to the lack of a publicly available " + 
"miasst_termux implementation or protocol specification. The feature " + 
"remains planned in the original project with no announced ETA.\n" + 
"• wipe-super - requires host-side parsing of super_empty.img metadata " + 
"rather than a standalone flashing protocol command. The GSI screen's " + 
"(Wipe Super) option provides the equivalent functionality for common cases.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
