package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.FastbootRebootTarget
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.launch

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
            op.flashPartition(partition, java.io.File(path))
            busy(false)
        }
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Flash $label") }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FastbootScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember { FastbootOperations(context, logRepository) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var manualCommand by remember { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fastboot Flash Tool") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
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

            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        connected = ops.connect()
                        busy = false
                    }
                }
            ) { Text(if (connected) "Reconnect device" else "Connect fastboot device") }

            Text("Flash partition", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashPartitionRow("Recovery", "recovery", { ops }, { busy = it })
                FlashPartitionRow("Boot", "boot", { ops }, { busy = it })
                FlashPartitionRow("init_boot", "init_boot", { ops }, { busy = it })
                FlashPartitionRow("vendor_boot", "vendor_boot", { ops }, { busy = it })
                FlashPartitionRow("vbmeta", "vbmeta", { ops }, { busy = it })
            }

            Text("Reboot", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.BOOTLOADER); connected = false } }) { Text("Bootloader") }
                OutlinedButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.RECOVERY); connected = false } }) { Text("Recovery") }
                OutlinedButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.SYSTEM); connected = false } }) { Text("System") }
                OutlinedButton(onClick = { scope.launch { ops.reboot(FastbootRebootTarget.FASTBOOTD); connected = false } }) { Text("FastbootD") }
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { scope.launch { ops.oem("device-info") } }) { Text("Check Status UBL (oem device-info)") }

            Text(
                "ADB Sideload isn't implemented — it needs the full ADB protocol (RSA key " +
                    "auth handshake), which is a separate, much larger undertaking from fastboot's " +
                    "simple command/response protocol. Use a dedicated ADB app for sideloading for now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            Text("Manual fastboot command (raw wire protocol — e.g. getvar:product, oem device-info)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = manualCommand,
                onValueChange = { manualCommand = it },
                label = { Text("e.g. getvar:product") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = manualCommand.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        ops.rawCommand(manualCommand.trim())
                        busy = false
                    }
                }
            ) { Text("Send") }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
