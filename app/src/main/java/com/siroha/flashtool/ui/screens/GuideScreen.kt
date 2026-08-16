package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.components.SirohaTopBar

private data class GuideEntry(val title: String, val body: String)

// Ported from flash.sh's menu_panduan, adapted for a native app (no more
// Termux/pkg install steps — see RequirementsScreen for the native
// equivalent) and split by transport instead of one long combined list, so
// each menu's own Guide button only shows what's relevant to it.

private val usbTroubleshooting = GuideEntry(
    "USB/OTG troubleshooting",
    "• Not detected at all → confirm host phone supports USB Host/OTG, try another cable\n" +
        "• Target unresponsive → confirm it's genuinely in the right mode, not just plugged in\n" +
        "• Permission prompt missing → reconnect the cable and retry the action"
)

private val edlEntries = listOf(
    GuideEntry(
        "How to enter EDL (9008) mode",
        "• Via ADB (device still boots): adb reboot edl\n" +
            "• Via fastboot (bootloader already unlocked): fastboot oem edl\n" +
            "• Test point (hardware, last resort — see your device's specific guide)"
    ),
    GuideEntry(
        "QDL flash — step by step",
        "1. Prepare the firmware folder (firehose .mbn, rawprogram*.xml, patch*.xml)\n" +
            "2. Open QDL Flash, pick eMMC or UFS storage\n" +
            "3. Pick the loader, rawprogram, and patch files\n" +
            "4. Uncheck any partitions you don't want touched\n" +
            "5. Connect the target in EDL mode via OTG, then start"
    ),
    GuideEntry(
        "Bypass UBL — Redmi 4A (rolex)",
        "Device-specific: uses a bundled MIUI 10.2.3.0 firehose loader and partition map for " +
            "the Redmi 4A only. Connect in EDL mode, then tap Start — do not run this on any " +
            "other model."
    ),
    GuideEntry(
        "Wiring up qdl for a new ABI",
        "qdl binaries are bundled as jniLibs/<abi>/libqdl.so (armeabi-v7a, arm64-v8a, x86, " +
            "x86_64) rather than raw asset files — that's what keeps them executable under " +
            "Android 10+'s W^X restrictions without a runtime chmod."
    ),
    usbTroubleshooting,
)

private val fastbootEntries = listOf(
    GuideEntry(
        "Fastboot flash — step by step",
        "Requirements: target bootloader unlocked, device in fastboot mode.\n\n" +
            "Recovery + Magisk: flash recovery.img → flash vbmeta.img (disable-verity) → " +
            "reboot to recovery → sideload Magisk ZIP.\n\n" +
            "Boot patch (Magisk-patched boot.img): flash boot.img → reboot system.\n\n" +
            "Testing a recovery without flashing: use \"Boot without flashing\" to boot " +
            "a TWRP/recovery image temporarily instead."
    ),
    GuideEntry(
        "GSI ROM flash — full sequence",
        "1. Bootloader unlocked + fastboot mode\n" +
            "2. Flash vbmeta (disable-verity/disable-verification)\n" +
            "3. Reboot to FastbootD\n" +
            "4. Confirm is-userspace = yes\n" +
            "5. Erase system\n" +
            "6. Wipe Super (optional partitions: product/system_ext/odm)\n" +
            "7. Flash the GSI image to system\n" +
            "8. Reboot to recovery → factory reset → reboot system"
    ),
    GuideEntry(
        "A/B partition tool",
        "Flash any partition by exact name for either slot (e.g. boot_a, vendor_boot_b), check " +
            "or switch the active slot, or boot a recovery image temporarily without flashing it."
    ),
    GuideEntry(
        "FRP removal",
        "SPRD devices: fastboot erase persist. Samsung and SPRD/MTK devices: via ADB shell " +
            "commands instead — connect ADB first, same USB OTG setup as fastboot."
    ),
    GuideEntry(
        "Manual command box",
        "Type the raw wire-protocol command only — never the word \"fastboot\" itself. " +
            "e.g. for \"fastboot oem device-info\" type just  oem device-info ; for " +
            "\"fastboot getvar product\" type  getvar:product  (colon, not space). " +
            "Typing  devices  lists the currently attached fastboot device(s) locally, " +
            "same as the real fastboot CLI — it's never sent to the phone."
    ),
    usbTroubleshooting,
)

private val miToolEntries = listOf(
    GuideEntry(
        "Mi Unlock — step by step",
        "1. Log in with your Xiaomi account in the built-in login page\n" +
            "2. Wait for the account/server session to resolve\n" +
            "3. Power off the phone, hold Volume Down + Power to enter Bootloader mode, connect via OTG\n" +
            "4. Tap \"Connect and check eligibility\" — review the notice (it tells you if user data will be wiped)\n" +
            "5. Tap \"Unlock bootloader\" and don't disconnect the phone until it finishes"
    ),
    GuideEntry(
        "Flash Fastboot ROM",
        "Pick the folder you extracted a Xiaomi fastboot ROM into (the one containing images/). " +
            "Every *.img found is matched to a same-named partition, exactly how Xiaomi's own " +
            "flash_all.sh scripts work — review the detected list and uncheck anything you don't " +
            "want flashed before starting."
    ),
    GuideEntry(
        "Firmware Content Extractor",
        "Paste a direct ROM ZIP URL and the exact file name inside it you want (e.g. boot.img), " +
            "then Download & extract. This downloads the whole ZIP first (simpler, but slower for " +
            "multi-gigabyte ROMs than the original tool's range-request approach)."
    ),
    GuideEntry(
        "What's not here",
        "\"Mi Assistant\" isn't implemented — it depends on an external binary that was never " +
            "open-sourced anywhere, even in the original project. See the About screen for details."
    ),
    usbTroubleshooting,
)

@Composable
private fun GuideList(title: String, icon: ImageVector, entries: List<GuideEntry>, onBack: () -> Unit) {
    Scaffold(
        topBar = { SirohaTopBar(title, icon = icon, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding)
        ) {
            items(entries) { entry ->
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            entry.body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GuideEdlScreen(onBack: () -> Unit) = GuideList("Guide — Qualcomm EDL", Icons.Filled.Bolt, edlEntries, onBack)

@Composable
fun GuideFastbootScreen(onBack: () -> Unit) = GuideList("Guide — Fastboot", Icons.Filled.Build, fastbootEntries, onBack)

@Composable
fun GuideMiToolScreen(onBack: () -> Unit) = GuideList("Guide — MiTool", Icons.Filled.Extension, miToolEntries, onBack)
