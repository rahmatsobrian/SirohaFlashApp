package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.theme.FilledTonalButton
import com.siroha.flashtool.ui.theme.FilterChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one place in the app for everything ADB: connecting, running a manual
 * shell/raw command, and sideloading a ZIP in recovery. Previously this same
 * command box + sideload button was copy-pasted across Fastboot, A/B
 * Partition, and FRP Tool — consolidated here so connecting once, running a
 * command, and reading its output/log all live in a single, non-duplicated
 * screen instead of three near-identical ones.
 */
@Composable
fun AdbScreen(adbOperations: AdbOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val adb = adbOperations
    var connected by remember { mutableStateOf(adb.isConnected()) }
    var connecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }
    var shellMode by rememberSaveable { mutableStateOf(true) }
    var result by rememberSaveable { mutableStateOf("") }
    var sideloadName by remember { mutableStateOf<String?>(null) }
    var sideloadRunning by remember { mutableStateOf(false) }
    // Once an auto-connect attempt on a *present* device comes back false,
    // stop silently re-triggering the USB permission popup every poll tick
    // — same guard as every other screen's connect card. The manual
    // "Connect" row below always still works since a tap is an explicit
    // user action.
    var autoConnectDenied by remember { mutableStateOf(false) }
    // Bus path of whichever device this screen's connection currently
    // points at. This is THE fix for "sideload silently keeps failing after
    // switching TWRP's screen": a recovery like TWRP re-enumerates a brand
    // NEW USB device (same ADB class signature, different bus path) when
    // switching from its main menu into "ADB Sideload" specifically.
    // adb.isConnected() alone only reflects "we opened an interface at some
    // point" — it has no way to notice that swap, so every command
    // (including this screen's own sideload) kept running against a dead
    // connection to the OLD enumeration until the cable was physically
    // unplugged. HomeScreen already had this exact detection loop, but it
    // only runs while Home itself is composed — leaving THIS screen (or any
    // other) with no way to notice a swap that happens while sitting on it,
    // which is exactly the scenario reported: connected from Home's TWRP
    // main-menu enumeration, then switched to ADB Tools, then TWRP itself
    // switched into Sideload — a new enumeration this screen never checked
    // for.
    var lastAdbDeviceName by remember { mutableStateOf<String?>(null) }
    val entries by logRepository.entries.collectAsState()

    LaunchedEffect(Unit) {
        while (isActive) {
            val liveAdb = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }

            // Never touch the connection while a command or sideload is
            // actually running — reconnecting out from under an in-flight
            // USB transfer would be worse than doing nothing.
            if (!busy) {
                if (connected) {
                    if (liveAdb == null) {
                        // Nothing ADB-looking present anymore — genuinely gone.
                        adb.disconnect()
                        connected = false
                        lastAdbDeviceName = null
                    } else if (lastAdbDeviceName == null) {
                        // First observation since this screen appeared —
                        // adopt an already-open connection instead of
                        // tearing it down, same reasoning as HomeScreen's
                        // own version of this check.
                        lastAdbDeviceName = liveAdb.deviceName
                    } else if (liveAdb.deviceName != lastAdbDeviceName) {
                        // A genuinely new enumeration — e.g. TWRP main menu
                        // -> ADB Sideload. The stale connection is dead;
                        // drop it and let the block below reconnect fresh
                        // against the new one, instead of leaving every
                        // subsequent command (sideload included) silently
                        // failing against a connection nothing is using
                        // anymore.
                        adb.disconnect()
                        connected = false
                        lastAdbDeviceName = null
                        autoConnectDenied = false
                    }
                } else if (liveAdb == null) {
                    autoConnectDenied = false
                } else if (!autoConnectDenied && !connecting) {
                    connecting = true
                    connected = adb.connect()
                    connecting = false
                    if (connected) lastAdbDeviceName = liveAdb.deviceName
                    if (!connected) autoConnectDenied = true
                }
            }

            delay(1500)
        }
    }

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        sideloadRunning = true
        sideloadName = com.siroha.flashtool.core.SafFiles.displayName(context, uri, "sideload.zip")
        busy = true
        scope.launch {
            val ok = adb.sideloadFromUri(context, uri)
            busy = false
            sideloadRunning = false
            snackbarHostState.showSnackbar(if (ok) "Sideload finished - check the log below" else "Sideload failed - check the log below")
        }
    }

    Scaffold(
        topBar = { SirohaTopBar("ADB Tools", icon = Icons.Filled.Terminal, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeading(Icons.Filled.Usb, "Connection", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = when {
                                connected -> "Reconnect ADB device"
                                connecting -> "Connecting..."
                                autoConnectDenied -> "USB permission needed"
                                else -> "Connect ADB device"
                            },
                            subtitle = when {
                                connected -> "Connected - auto-detects and reconnects if the device re-enumerates (e.g. TWRP switching into Sideload)"
                                connecting -> null
                                autoConnectDenied -> "USB permission was denied - tap to ask again"
                                else -> "Works in normal ADB mode and in most custom recoveries (TWRP, OrangeFox, ...)"
                            },
                            icon = if (connected) Icons.Filled.BugReport else Icons.Filled.Usb,
                            enabled = !busy && !connecting,
                            onClick = {
                                connecting = true
                                autoConnectDenied = false
                                scope.launch {
                                    val ok = adb.connect()
                                    connected = ok
                                    connecting = false
                                    if (ok) {
                                        lastAdbDeviceName = UsbDeviceHelper.listDevices(context)
                                            .firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }?.deviceName
                                    } else {
                                        autoConnectDenied = true
                                    }
                                }
                            }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual command", modifier = Modifier.padding(start = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = shellMode, onClick = { shellMode = true }, label = { Text("Shell") })
                            FilterChip(selected = !shellMode, onClick = { shellMode = false }, label = { Text("ADB") })
                        }
                        Text(
                            if (shellMode) {
"Shell mode: executes your input directly through the connected device's ADB shell, equivalent to running (adb shell <command>). Enter only the shell command itself do not include the (adb shell) prefix. For example, use (getprop ro.build.version.release), not (adb shell getprop ro.build.version.release)."
                            } else {
"ADB mode: executes the command directly through ADB, equivalent to running (adb <command>). Enter only the ADB command and its arguments do not include the (adb) prefix or (adb shell). For example, enter (devices), (reboot), or (reboot bootloader). Not (adb devices), (adb reboot)"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            placeholder = { Text(if (shellMode) "getprop ro.build.version.release" else "devices") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            singleLine = true,
                            enabled = !busy,
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
                                enabled = command.isNotBlank() && !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val outcome = runCatching {
                                            if (shellMode) adb.shellWithOutcome(command.trim()) else adb.rawCommand(command.trim())
                                        }.getOrElse { e ->
                                            AdbOperations.ShellOutcome("ERROR: ${e.javaClass.simpleName} - ${e.message ?: "unknown error"}", false)
                                        }
                                        result = outcome.text.ifBlank { "(no output)" }
                                        busy = false
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null)
                                Text("  Send")
                            }
                        }
                        if (result.isNotBlank()) {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                Text(
                                    result,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.CloudUpload, "Sideload", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = when {
                                sideloadRunning -> "Sideloading ${sideloadName ?: "..."}"
                                else -> "Sideload ZIP"
                            },
                            subtitle = if (sideloadRunning) {
                                "In progress - see DEBUG-level log entries below for live per-block detail"
                            } else {
                                "Works with any size ZIP - reads directly from storage without copying when possible"
                            },
                            icon = Icons.Filled.CloudUpload,
                            enabled = !busy,
                            onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
                        )
                    )
                )
            }

            item { AdbLogSection(entries = entries) }
        }
    }
}

@Composable
private fun AdbLogSection(entries: List<com.siroha.flashtool.data.LogEntry>) {
    var showDebug by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionHeading(Icons.Filled.Info, "Log")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Debug detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = showDebug, onCheckedChange = { showDebug = it })
        }
    }
    // Filtered to this screen's own traffic (tag "Adb") rather than the
    // whole app's log — sideload of a large file alone can produce
    // thousands of entries once "Debug detail" is on, and mixing in every
    // other screen's activity would make finding the relevant lines here
    // much harder, not easier.
    val relevant = entries.filter { it.tag == "Adb" && (showDebug || it.level != com.siroha.flashtool.data.LogLevel.DEBUG) }.takeLast(300)
    if (relevant.isEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
            Text("No ADB activity yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                relevant.forEach { e ->
                    Text(
                        e.format(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (e.level) {
                            com.siroha.flashtool.data.LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            com.siroha.flashtool.data.LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            com.siroha.flashtool.data.LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
