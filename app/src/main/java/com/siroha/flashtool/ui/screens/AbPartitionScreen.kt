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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.components.launchWithOutcomeFeedback
import java.io.File

/** A file-picker button that flashes the picked file straight to [partition] once tapped. */
@Composable
private fun FlashButton(label: String, partition: String, ops: FastbootOperations, snackbarHostState: SnackbarHostState, busy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "$partition.img")
        scope.launchWithFeedback(snackbarHostState, "Flash $label", busy) { ops.flashPartition(partition, File(path)) }
    }
    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(label) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AbPartitionScreen(fastbootOperations: FastbootOperations, adbOperations: AdbOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    val adb = adbOperations
    var busy by remember { mutableStateOf(false) }
    var adbConnected by remember { mutableStateOf(adb.isConnected()) }
    var adbCommand by rememberSaveable { mutableStateOf("") }
    var adbShellMode by rememberSaveable { mutableStateOf(true) }
    var adbResult by rememberSaveable { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    Scaffold(
        topBar = { SirohaTopBar("A/B Partition Tool", icon = Icons.Filled.SwapHoriz, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { ops.connect() } }
                ) { Text("Connect fastboot device") }
            }

            item {
                SectionHeading(Icons.Filled.RocketLaunch, "Flash by slot")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlashButton("boot (active)", "boot", ops, snackbarHostState) { busy = it }
                    FlashButton("boot_a", "boot_a", ops, snackbarHostState) { busy = it }
                    FlashButton("boot_b", "boot_b", ops, snackbarHostState) { busy = it }
                    FlashButton("init_boot_a", "init_boot_a", ops, snackbarHostState) { busy = it }
                    FlashButton("init_boot_b", "init_boot_b", ops, snackbarHostState) { busy = it }
                    FlashButton("recovery (active)", "recovery", ops, snackbarHostState) { busy = it }
                    FlashButton("recovery_a", "recovery_a", ops, snackbarHostState) { busy = it }
                    FlashButton("recovery_b", "recovery_b", ops, snackbarHostState) { busy = it }
                    FlashButton("vendor_boot_a", "vendor_boot_a", ops, snackbarHostState) { busy = it }
                    FlashButton("vendor_boot_b", "vendor_boot_b", ops, snackbarHostState) { busy = it }
                    FlashButton("vbmeta_a", "vbmeta_a", ops, snackbarHostState) { busy = it }
                    FlashButton("vbmeta_b", "vbmeta_b", ops, snackbarHostState) { busy = it }
                }
            }

            item {
                SectionHeading(Icons.Filled.SwapHoriz, "Slot control")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Check active slot", { busy = it }) { ops.getVar("current-slot").isNotBlank() }
                    }) { Text("Check active slot") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Set active slot A", { busy = it }) { ops.setActiveSlot("a") }
                    }) { Text("Set active: A") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Set active slot B", { busy = it }) { ops.setActiveSlot("b") }
                    }) { Text("Set active: B") }
                }
            }

            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Boot without flashing (e.g. TWRP)")
                val twrpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val path = SafFiles.copyToCache(context, uri, "twrp.img")
                    scope.launchWithFeedback(snackbarHostState, "Boot TWRP image", { busy = it }) { ops.boot(File(path)) }
                }
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { twrpPicker.launch(arrayOf("*/*")) }) { Text("Boot TWRP image") }
            }

            item { SectionHeading(Icons.Filled.CloudUpload, "ADB Sideload") }
            item {
                val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val path = SafFiles.copyToCache(context, uri, "sideload.zip")
                    scope.launchWithFeedback(snackbarHostState, "Sideload", { busy = it }) { adb.sideload(File(path)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launchWithFeedback(snackbarHostState, "Connect ADB", { busy = it }) {
                                adb.connect().also { adbConnected = it }
                            }
                        }
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
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual ADB command")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = adbShellMode, onClick = { adbShellMode = true }, label = { Text("Shell") })
                            FilterChip(selected = !adbShellMode, onClick = { adbShellMode = false }, label = { Text("ADB") })
                        }
                        Text(
                            if (adbShellMode) "Shell mode: runs as  adb shell <what you type>"
                            else "ADB mode: bare  adb <what you type>  — e.g.  devices ,  reboot ,  reboot:bootloader",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = adbCommand,
                            onValueChange = { adbCommand = it },
                            placeholder = { Text(if (adbShellMode) "getprop ro.build.version.release" else "devices") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilledTonalButton(
                                enabled = adbCommand.isNotBlank() && !busy,
                                onClick = {
                                    scope.launchWithOutcomeFeedback(
                                        snackbarHostState, "Send ADB command",
                                        text = { it.text }, success = { it.success },
                                        setBusy = { busy = it },
                                        onResult = { adbResult = it.text.ifBlank { "(no output)" } }
                                    ) {
                                        if (adbShellMode) adb.shellWithOutcome(adbCommand.trim()) else adb.rawCommand(adbCommand.trim())
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null)
                                Text("  Send")
                            }
                        }
                        if (adbResult.isNotBlank()) {
                            Text(adbResult, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            item { SectionHeading(Icons.Filled.Usb, "Recent activity") }
            items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
