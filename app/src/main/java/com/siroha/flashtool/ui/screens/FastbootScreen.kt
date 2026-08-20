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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.components.launchWithOutcomeFeedback
import com.siroha.flashtool.ui.components.launchWithTextFeedback
import kotlinx.coroutines.launch
import java.io.File

/** One "pick a file, flash it to this partition" row — the fastboot equivalent of flash_partition() in flash.sh. */
@Composable
private fun FlashPartitionRow(
    label: String,
    partition: String,
    ops: () -> FastbootOperations?,
    busy: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "$partition.img")
        scope.launchWithFeedback(snackbarHostState, "Flash $label", busy) {
            ops()?.flashPartition(partition, File(path)) ?: false
        }
    }
    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Flash $label") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastbootScreen(fastbootOperations: FastbootOperations, adbOperations: AdbOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    val adb = adbOperations
    var connected by remember { mutableStateOf(ops.isConnected()) }
    var adbConnected by remember { mutableStateOf(adb.isConnected()) }
    var busy by remember { mutableStateOf(false) }
    var manualCommand by rememberSaveable { mutableStateOf("") }
    var manualResult by rememberSaveable { mutableStateOf("") }
    var ublStatusResult by rememberSaveable { mutableStateOf("") }
    var adbCommand by rememberSaveable { mutableStateOf("") }
    var adbShellMode by rememberSaveable { mutableStateOf(true) }
    var adbResult by rememberSaveable { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "sideload.zip")
        scope.launchWithFeedback(snackbarHostState, "Sideload", { busy = it }) { adb.sideload(File(path)) }
    }

    Scaffold(
        topBar = { SirohaTopBar("Fastboot Flash Tool", icon = Icons.Filled.Build, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Native fastboot-over-USB — no external fastboot binary needed. Connect the target " +
                        "in fastboot mode via OTG first.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) {
                            ops.connect().also { connected = it }
                        }
                    }
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null)
                    Text(if (connected) "  Reconnect device" else "  Connect fastboot device")
                }
            }

            item {
                SectionHeading(Icons.Filled.Bolt, "Flash partition")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlashPartitionRow("Recovery", "recovery", { ops }, { busy = it }, snackbarHostState)
                    FlashPartitionRow("Boot", "boot", { ops }, { busy = it }, snackbarHostState)
                    FlashPartitionRow("init_boot", "init_boot", { ops }, { busy = it }, snackbarHostState)
                    FlashPartitionRow("vendor_boot", "vendor_boot", { ops }, { busy = it }, snackbarHostState)
                    FlashPartitionRow("vbmeta", "vbmeta", { ops }, { busy = it }, snackbarHostState)
                }
            }

            item {
                SectionHeading(Icons.Filled.RestartAlt, "Reboot")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to Bootloader") { ops.reboot(FastbootRebootTarget.BOOTLOADER).also { connected = false } }
                    }) { Text("Bootloader") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to Recovery") { ops.reboot(FastbootRebootTarget.RECOVERY).also { connected = false } }
                    }) { Text("Recovery") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to System") { ops.reboot(FastbootRebootTarget.SYSTEM).also { connected = false } }
                    }) { Text("System") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to FastbootD") { ops.reboot(FastbootRebootTarget.FASTBOOTD).also { connected = false } }
                    }) { Text("FastbootD") }
                }
            }

            item {
                SectionHeading(Icons.Filled.Info, "Status")
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launchWithTextFeedback(
                            snackbarHostState, "Check Status UBL",
                            isSuccess = { !it.contains("FAILED") && !it.contains("ERROR") },
                            onResult = { ublStatusResult = it }
                        ) { ops.rawCommandWithResponse("oem device-info") }
                    }
                ) {
                    Text("Check Status UBL (oem device-info)")
                }
                if (ublStatusResult.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            ublStatusResult,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual command (raw wire protocol)")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Don't type the word \"fastboot\" — just the part after it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    "Want to run  fastboot oem device-info  ?  →  type only  oem device-info\n" +
                                        "Want to run  fastboot getvar product  ?  →  type only  getvar:product  (colon, not space)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        // No floating `label` here on purpose — the label cutout in the
                        // border can look like a rendering glitch on a small phone
                        // screen. A plain caption above + an in-field leading icon reads
                        // more clearly as "this is a command box."
                        OutlinedTextField(
                            value = manualCommand,
                            onValueChange = { manualCommand = it },
                            placeholder = { Text("getvar:product") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            singleLine = true,
                            enabled = !busy,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Live echo: proves keystrokes are actually reaching the state,
                        // rather than only trusting what the text field itself renders.
                        if (manualCommand.isNotEmpty()) {
                            Text(
                                "Will send: $manualCommand",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilledTonalButton(
                                enabled = manualCommand.isNotBlank() && !busy,
                                onClick = {
                                    scope.launchWithTextFeedback(
                                        snackbarHostState, "Send command",
                                        isSuccess = { !it.contains("FAILED") && !it.contains("ERROR") },
                                        setBusy = { busy = it },
                                        onResult = { manualResult = it }
                                    ) { ops.rawCommandWithResponse(manualCommand.trim()) }
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
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = if (manualResult.contains("FAILED") || manualResult.contains("ERROR")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Boot without flashing (e.g. TWRP)")
                val twrpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val path = SafFiles.copyToCache(context, uri, "twrp.img")
                    scope.launchWithFeedback(snackbarHostState, "Boot TWRP image", { busy = it }) { ops.boot(File(path)) }
                }
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { twrpPicker.launch(arrayOf("*/*")) }
                ) { Text("Boot TWRP image") }
            }

            item {
                SectionHeading(Icons.Filled.CloudUpload, "ADB Sideload")
                Text(
                    "Uses this app's from-scratch ADB-over-USB client, with the target already booted " +
                        "into recovery/sideload mode. First connection needs \"Allow USB debugging\" on " +
                        "the target's screen, then tap Connect again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
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
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Text("  Sideload ZIP")
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual ADB command")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.FilterChip(selected = adbShellMode, onClick = { adbShellMode = true }, label = { Text("Shell") })
                            androidx.compose.material3.FilterChip(selected = !adbShellMode, onClick = { adbShellMode = false }, label = { Text("ADB") })
                        }
                        Text(
                            if (adbShellMode) {
                                "Shell mode: runs as  adb shell <what you type>  — e.g. type  getprop ro.build.version.release"
                            } else {
                                "ADB mode: runs as a bare  adb <what you type>  command, not wrapped in shell — e.g. type  devices  or  reboot  or  reboot:bootloader"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = adbCommand,
                            onValueChange = { adbCommand = it },
                            placeholder = { Text(if (adbShellMode) "getprop ro.build.version.release" else "devices") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            singleLine = true,
                            enabled = !busy,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
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
                                        onResult = { adbResult = it.text.ifBlank { "(no output)" } },
                                        fallback = { e -> com.siroha.flashtool.core.AdbOperations.ShellOutcome("ERROR: ${e.javaClass.simpleName} — ${e.message ?: "unknown error"}", false) }
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
                            Text(
                                adbResult,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            item { SectionHeading(Icons.Filled.Info, "Recent activity") }
            items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
