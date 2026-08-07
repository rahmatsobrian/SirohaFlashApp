package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class GuideSection(val title: String, val body: String)

// Ported from flash.sh's menu_panduan, adapted for a native app (no more
// Termux/pkg install steps — see RequirementsScreen for the native equivalent).
private val sections = listOf(
    GuideSection(
        "How to enter EDL (9008) mode",
        "• Via ADB (device still boots): adb reboot edl\n" +
            "• Via fastboot (bootloader already unlocked): fastboot oem edl\n" +
            "• Test point (hardware, last resort — see your device's specific guide)"
    ),
    GuideSection(
        "QDL flash — step by step",
        "1. Prepare the firmware folder (firehose .mbn, rawprogram*.xml, patch*.xml)\n" +
            "2. Open QDL Flash, pick eMMC or UFS storage\n" +
            "3. Pick the loader, rawprogram, and patch files\n" +
            "4. Uncheck any partitions you don't want touched\n" +
            "5. Connect the target in EDL mode via OTG, then start"
    ),
    GuideSection(
        "Fastboot flash — step by step",
        "Requirements: target bootloader unlocked, device in fastboot mode.\n\n" +
            "Recovery + Magisk: flash recovery.img → flash vbmeta.img (disable-verity) → " +
            "reboot to recovery → sideload Magisk ZIP (use a dedicated ADB app for sideload).\n\n" +
            "Boot patch (Magisk-patched boot.img): flash boot.img → reboot system."
    ),
    GuideSection(
        "GSI ROM flash — full sequence",
        "1. Bootloader unlocked + fastboot mode\n" +
            "2. Flash vbmeta (disable-verity/disable-verification)\n" +
            "3. Reboot to FastbootD\n" +
            "4. Confirm is-userspace = yes\n" +
            "5. Erase system\n" +
            "6. Delete logical partitions product_a & product_b\n" +
            "7. Flash the GSI image to system\n" +
            "8. Reboot to recovery → factory reset → reboot system"
    ),
    GuideSection(
        "Wiring up qdl for a new ABI",
        "qdl binaries are bundled as jniLibs/<abi>/libqdl.so (armeabi-v7a, arm64-v8a, x86, " +
            "x86_64) rather than raw asset files — that's what keeps them executable under " +
            "Android 10+'s W^X restrictions without a runtime chmod."
    ),
    GuideSection(
        "USB/OTG troubleshooting",
        "• Not detected at all → confirm host phone supports USB Host/OTG, try another cable\n" +
            "• Target unresponsive → confirm it's genuinely in EDL/fastboot mode, not just plugged in\n" +
            "• Permission prompt missing → reconnect the cable and retry the action"
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guide") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(sections) { section ->
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            section.body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
