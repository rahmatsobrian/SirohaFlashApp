package com.siroha.flashtool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.BuildConfig
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Scaffold(
        topBar = { SirohaTopBar("About", icon = Icons.Filled.Info, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Siroha Flash Tool", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "A native Android port of the original SirohaFlashTool Termux/bash script — " +
                            "combining Termux-QDL, QDL-Flasher, ADBiFY-QDL, and TRRT — with Material 3, " +
                            "Material You dynamic color, and root/Shizuku execution.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(Icons.Filled.People, "Credits")
                        Text("Original tool: Siroha (github.com/rahmatsobrian)", style = MaterialTheme.typography.bodyMedium)
                        Text("Bypass UBL Redmi 4A: Rahmat Sobrian", style = MaterialTheme.typography.bodyMedium)
                        Text("MiTool: offici5l (github.com/offici5l/MiTool)", style = MaterialTheme.typography.bodyMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickableOpen { open("https://github.com/rahmatsobrian") },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Open GitHub", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickableOpen { open("https://t.me/rahmatsobrian") },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Open Telegram", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(Icons.Filled.Warning, "Disclaimer", color = MaterialTheme.colorScheme.error)
                        Text(
                            "Flashing firmware, unlocking bootloaders, and removing FRP can permanently " +
                                "damage a device if used incorrectly, and can be illegal if used on a " +
                                "device you don't own or aren't authorized to service. Use only on your " +
                                "own hardware or with explicit authorization.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(Icons.Filled.Block, "Not yet implemented")
                        Text(
                            "• ADB sideload (separate chunked transfer protocol, not a plain shell command)\n" +
                                "• MiTool (Xiaomi unlock/flash/assistant) — depends on Python + Xiaomi's " +
                                "official account-based unlock API; reference scripts kept in the repo's " +
                                "mitool_reference/ folder but not wired into the app",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/** Row-level click target with no ripple-padding offset, keeping link rows flush with body text above them. */
private fun Modifier.clickableOpen(onClick: () -> Unit): Modifier =
    this.then(androidx.compose.foundation.clickable(onClick = onClick))
