package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar

private data class GuideEntry(
    val title: String,
    val body: String,
    val icon: ImageVector,
    /** True for device-specific or otherwise risky entries - rendered in an error-tinted card, same treatment as About's Disclaimer. */
    val warning: Boolean = false
)

// Ported from flash.sh's menu_panduan, adapted for a native app (no more
// Termux/pkg install steps - see RequirementsScreen for the native
// equivalent) and split by transport instead of one long combined list, so
// each menu's own Guide button only shows what's relevant to it.
//
// Body text is still authored as plain strings (blocks separated by a blank
// line), but GuideBody below parses each block into real step/bullet/label
// UI instead of dumping raw "1. "/"• " characters as body text - see
// GuideBody's kdoc for the exact rules.

private val usbTroubleshooting = GuideEntry(
    "USB/OTG troubleshooting",
    "• Not detected at all → confirm host phone supports USB Host/OTG, try another cable\n" +
        "• Target unresponsive → confirm it's genuinely in the right mode, not just plugged in\n" +
        "• Permission prompt missing → reconnect the cable and retry the action",
    Icons.Filled.Cable
)

private val edlEntries = listOf(
    GuideEntry(
        "How to enter EDL (9008) mode",
        "• Via ADB (device still boots): adb reboot edl\n" +
            "• Via fastboot (bootloader already unlocked): fastboot oem edl\n" +
            "• Test point (hardware, last resort - see your device's specific guide)",
        Icons.Filled.PowerSettingsNew
    ),
    GuideEntry(
        "QDL flash - step by step",
        "1. Prepare the firmware folder (firehose .mbn, rawprogram*.xml, patch*.xml)\n" +
            "2. Open QDL Flash, pick eMMC or UFS storage\n" +
            "3. Pick the loader, rawprogram, and patch files\n" +
            "4. Uncheck any partitions you don't want touched\n" +
            "5. Connect the target in EDL mode via OTG, then start",
        Icons.Filled.Bolt
    ),
    GuideEntry(
        "Bypass UBL - Redmi 4A (rolex)",
        "Device-specific: uses a bundled MIUI 10.2.3.0 firehose loader and partition map for " +
            "the Redmi 4A only. Connect in EDL mode, then tap Start - do not run this on any " +
            "other model.",
        Icons.Filled.Shield,
        warning = true
    ),
    GuideEntry(
        "Wiring up qdl for a new ABI",
        "qdl binaries are bundled as jniLibs/<abi>/libqdl.so (armeabi-v7a, arm64-v8a, x86, " +
            "x86_64) rather than raw asset files - that's what keeps them executable under " +
            "Android 10+'s W^X restrictions without a runtime chmod.",
        Icons.Filled.Memory
    ),
    usbTroubleshooting,
)

private val fastbootEntries = listOf(
    GuideEntry(
        "Fastboot flash - step by step",
        "Requirements: target bootloader unlocked, device in fastboot mode.\n\n" +
            "Recovery + Magisk: flash recovery.img → flash vbmeta.img (disable-verity) → " +
            "reboot to recovery → sideload Magisk ZIP.\n\n" +
            "Boot patch (Magisk-patched boot.img): flash boot.img → reboot system.\n\n" +
            "Testing a recovery without flashing: use \"Boot without flashing\" to boot " +
            "a TWRP/recovery image temporarily instead.",
        Icons.Filled.Build
    ),
    GuideEntry(
        "GSI ROM flash - full sequence",
        "1. Bootloader unlocked + fastboot mode\n" +
            "2. Flash vbmeta (disable-verity/disable-verification)\n" +
            "3. Reboot to FastbootD\n" +
            "4. Confirm is-userspace = yes\n" +
            "5. Erase system\n" +
            "6. Wipe Super (optional partitions: product/system_ext/odm)\n" +
            "7. Flash the GSI image to system\n" +
            "8. Reboot to recovery → factory reset → reboot system",
        Icons.Filled.RocketLaunch
    ),
    GuideEntry(
        "A/B partition tool",
        "Flash any partition by exact name for either slot (e.g. boot_a, vendor_boot_b), check " +
            "or switch the active slot, or boot a recovery image temporarily without flashing it.",
        Icons.Filled.SwapHoriz
    ),
    GuideEntry(
        "FRP removal",
        "SPRD devices: fastboot erase persist. Samsung and SPRD/MTK devices: via ADB shell " +
            "commands instead - connect ADB first, same USB OTG setup as fastboot.",
        Icons.Filled.Lock
    ),
    GuideEntry(
        "Manual command box",
        "Type the raw wire-protocol command only - never the word \"fastboot\" itself. " +
            "e.g. for \"fastboot oem device-info\" type just  oem device-info ; for " +
            "\"fastboot getvar product\" type  getvar:product  (colon, not space). " +
            "Typing  devices  lists the currently attached fastboot device(s) locally, " +
            "same as the real fastboot CLI - it's never sent to the phone.",
        Icons.Filled.Code
    ),
    usbTroubleshooting,
)

private val miToolEntries = listOf(
    GuideEntry(
        "Mi Unlock - step by step",
        "1. Log in with your Xiaomi account in the built-in login page\n" +
            "2. Wait for the account/server session to resolve\n" +
            "3. Power off the phone, hold Volume Down + Power to enter Bootloader mode, connect via OTG\n" +
            "4. Tap \"Connect and check eligibility\" - review the notice (it tells you if user data will be wiped)\n" +
            "5. Tap \"Unlock bootloader\" and don't disconnect the phone until it finishes",
        Icons.Filled.LockOpen
    ),
    GuideEntry(
        "Flash Fastboot ROM",
        "Pick the folder you extracted a Xiaomi fastboot ROM into (the one containing images/). " +
            "Every *.img found is matched to a same-named partition, exactly how Xiaomi's own " +
            "flash_all.sh scripts work - review the detected list and uncheck anything you don't " +
            "want flashed before starting.",
        Icons.Filled.Extension
    ),
    GuideEntry(
        "Firmware Content Extractor",
        "Paste a direct ROM ZIP URL and the exact file name inside it you want (e.g. boot.img), " +
            "then Download & extract. This downloads the whole ZIP first (simpler, but slower for " +
            "multi-gigabyte ROMs than the original tool's range-request approach).",
        Icons.Filled.Download
    ),
    GuideEntry(
        "What's not here",
        "\"Mi Assistant\" isn't implemented - it depends on an external binary that was never " +
            "open-sourced anywhere, even in the original project. See the About screen for details.",
        Icons.Filled.Block
    ),
    usbTroubleshooting,
)

private val numberedLineRegex = Regex("^\\d+\\.\\s+")

/**
 * Renders a [GuideEntry.body] as real UI instead of raw text. The body is
 * plain-string authored (paragraphs separated by a blank line) so entries
 * stay easy to write/edit, but each paragraph ("block") is parsed into one
 * of:
 * - every line starts with "• " → a bullet list (dot + text per row)
 * - every line starts with "1. ", "2. ", ... → a numbered step list (circled
 *   number badge + text per row)
 * - a single line shaped "Label: rest of the sentence" → a bold label above
 *   muted supporting text, matching this app's other label/value rows
 * - anything else → a plain paragraph
 */
@Composable
private fun GuideBody(body: String, contentColor: Color, mutedColor: Color) {
    val blocks = body.split("\n\n")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        blocks.forEach { block ->
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEach

            val isBulletBlock = lines.all { it.startsWith("• ") }
            val isNumberedBlock = lines.all { numberedLineRegex.containsMatchIn(it) }
            val labelSplit = lines.singleOrNull()
                ?.let { line -> line.indexOf(": ").takeIf { it in 3..40 }?.let { idx -> line.substring(0, idx) to line.substring(idx + 2) } }

            when {
                isBulletBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .size(5.dp)
                                    .background(contentColor, CircleShape)
                            )
                            Text(
                                line.removePrefix("• "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                isNumberedBlock -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.forEach { line ->
                        val number = line.substringBefore(".")
                        val text = line.substringAfter(numberedLineRegex.find(line)!!.value)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Surface(shape = CircleShape, color = contentColor.copy(alpha = 0.16f), modifier = Modifier.size(22.dp)) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(number, style = MaterialTheme.typography.labelSmall, color = contentColor, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor, modifier = Modifier.weight(1f))
                        }
                    }
                }
                labelSplit != null -> Column {
                    Text(labelSplit.first, style = MaterialTheme.typography.labelLarge, color = contentColor, fontWeight = FontWeight.Bold)
                    Text(labelSplit.second, style = MaterialTheme.typography.bodyMedium, color = mutedColor, modifier = Modifier.padding(top = 2.dp))
                }
                else -> Text(block.replace("\n", " "), style = MaterialTheme.typography.bodyMedium, color = mutedColor)
            }
        }
    }
}

@Composable
private fun GuideEntryCard(entry: GuideEntry) {
    val headingColor = if (entry.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = if (entry.warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val contentColor = if (entry.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (entry.warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(entry.icon, entry.title, color = headingColor, modifier = Modifier.padding(start = 4.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
            Box(modifier = Modifier.padding(16.dp)) {
                GuideBody(entry.body, contentColor = contentColor, mutedColor = mutedColor)
            }
        }
    }
}

@Composable
private fun GuideList(title: String, icon: ImageVector, entries: List<GuideEntry>, onBack: () -> Unit) {
    Scaffold(
        topBar = { SirohaTopBar(title, icon = icon, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            items(entries) { entry -> GuideEntryCard(entry) }
        }
    }
}

@Composable
fun GuideEdlScreen(onBack: () -> Unit) = GuideList("Guide - Qualcomm EDL", Icons.Filled.Bolt, edlEntries, onBack)

@Composable
fun GuideFastbootScreen(onBack: () -> Unit) = GuideList("Guide - Fastboot", Icons.Filled.Build, fastbootEntries, onBack)

@Composable
fun GuideMiToolScreen(onBack: () -> Unit) = GuideList("Guide - MiTool", Icons.Filled.Extension, miToolEntries, onBack)
