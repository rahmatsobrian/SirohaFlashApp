package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.FastbootRebootTarget
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import java.io.File

/** One "pick a file, flash it to this partition" row — the fastboot equivalent of flash_partition() in flash.sh. */
@Composable
private fun FlashPartitionRow(label: String, partition: String, ops: () -> FastbootOperations?, busy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "$partition.img")
        scope.launch {
            busy(true)
            val op = ops()
            if (op == null) { busy(false); return@launch }
            op.flashPartition(partition, File(path))
            busy(false)
        }
    }
    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Flash $label") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastbootScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember { FastbootOperations(context, logRepository) }
    val adb = remember { AdbOperations(context, logRepository) }
    var connected by remember { mutableStateOf(false) }
    var adbConnected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var manualCommand by remember { mutableStateOf("") }
    var manualResult by remember { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "sideload.zip")
        scope.launch { busy = true; adb.sideload(File(path)); busy = false }
    }

    Scaffold(
        topBar = { SirohaTopBar("Fastboot Flash Tool", icon = Icons.Filled.Build, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Native fastboot-over-USB — no external fastboot binary needed. Connect the target " +
                    "in fastboot mode via OTG first.",
                style = MaterialTheme.typography.bodyMedium
            )

            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { busy = true; connected = ops.connect(); busy = false } }
            ) {
                Icon(Icons.Filled.Usb, contentDescription = null)
                Text(if (connected) "  Reconnect device" else "  Connect fastboot device")
            }

            SectionHeading(Icons.Filled.Bolt, "Flash partition")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashPartitionRow("Recovery", "recovery", { ops }, { busy = it })
                FlashPartitionRow("Boot", "boot", { ops }, { busy = it })
                FlashPartitionRow("init_boot", "init_boot", { ops }, { busy = it })
                FlashPartitionRow("vendor_boot", "vendor_boot", { ops }, { busy = it })
                FlashPartitionRow("vbmeta", "vbmeta", { ops }, { busy = it })
            }

            SectionHeading(Icons.Filled.RestartAlt, "Reboot")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.BOOTLOADER); connected = false } }) { Text("Bootloader") }
                FilledTonalButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.RECOVERY); connected = false } }) { Text("Recovery") }
                FilledTonalButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.SYSTEM); connected = false } }) { Text("System") }
                FilledTonalButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.FASTBOOTD); connected = false } }) { Text("FastbootD") }
            }

            SectionHeading(Icons.Filled.Info, "Status")
            FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { ops.oem("device-info") } }) {
                Text("Check Status UBL (oem device-info)")
            }

            SectionHeading(Icons.Filled.Terminal, "Manual command (raw wire protocol)")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualCommand,
                        onValueChange = { manualCommand = it },
                        label = { Text("Command") },
                        placeholder = { Text("getvar:product") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(
                            enabled = manualCommand.isNotBlank() && !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    manualResult = ops.rawCommandWithResponse(manualCommand.trim())
                                    busy = false
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = null)
                            Text("  Send")
                        }
                    }
                    // Immediate inline feedback — without this, a successful or failed
                    // command only showed up in "Recent activity" below, which reads
                    // as "nothing happened" unless you scroll down to check.
                    if (manualResult.isNotBlank()) {
                        Text(
                            manualResult,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (manualResult.startsWith("OK")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }

            SectionHeading(Icons.Filled.CloudUpload, "ADB Sideload")
            Text(
                "Uses this app's from-scratch ADB-over-USB client, with the target already booted " +
                    "into recovery/sideload mode. First connection needs \"Allow USB debugging\" on " +
                    "the target's screen, then tap Connect again.",
                style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { busy = true; adbConnected = adb.connect(); busy = false } }
            ) {
                Icon(Icons.Filled.Usb, contentDescription = null)
                Text(if (adbConnected) "  Reconnect ADB device" else "  Connect ADB device")
            }
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                Text("  Sideload ZIP")
            }

            SectionHeading(Icons.Filled.Info, "Recent activity")
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
