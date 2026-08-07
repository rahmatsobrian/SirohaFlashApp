package com.siroha.flashtool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Siroha Flash Tool", style = MaterialTheme.typography.titleLarge)
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                    "build type: ${BuildConfig.BUILD_TYPE}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "A native Android port of the original SirohaFlashTool Termux/bash script — " +
                    "combining Termux-QDL, QDL-Flasher, ADBiFY-QDL, and TRRT — with Material 3, " +
                    "Material You dynamic color, and root/Shizuku execution.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Credits", style = MaterialTheme.typography.titleLarge)
                    Text("Original tool: Siroha (github.com/rahmatsobrian)", style = MaterialTheme.typography.bodyMedium)
                    Text("Bypass UBL Redmi 4A: Rahmat Sobrian", style = MaterialTheme.typography.bodyMedium)
                    Text("MiTool: offici5l (github.com/offici5l/MiTool)", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rahmatsobrian")))
                    }) { Text("Open GitHub") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/rahmatsobrian")))
                    }) { Text("Open Telegram") }
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Disclaimer", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Flashing firmware, unlocking bootloaders, and removing FRP can permanently " +
                            "damage a device if used incorrectly, and can be illegal if used on a " +
                            "device you don't own or aren't authorized to service. Use only on your " +
                            "own hardware or with explicit authorization.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Not yet implemented", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "• ADB-over-USB (sideload, Samsung/SPRD FRP via ADB) — needs the full ADB " +
                            "auth protocol, a separate undertaking from fastboot's protocol\n" +
                            "• MiTool (Xiaomi unlock/flash/assistant) — depends on Python + Xiaomi's " +
                            "official account-based unlock API; reference scripts are kept in the " +
                            "repo's mitool_reference/ folder but aren't wired into the app",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
