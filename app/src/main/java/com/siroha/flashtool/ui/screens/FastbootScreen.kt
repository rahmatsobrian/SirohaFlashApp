package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.siroha.flashtool.ui.theme.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.FastbootRebootTarget
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.groupRowShape
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.components.launchWithTextFeedback
import androidx.compose.foundation.layout.PaddingValues
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Same "connected/good state" green used on Home/Settings, so this card
// reads as the same signal wherever it shows up in the app.
private val ActiveGreen = androidx.compose.ui.graphics.Color(0xFF84d996)



/** label shown in the list + actual partition name passed to fastboot. */
private data class FlashablePartition(val label: String, val partition: String)

private val flashablePartitions = listOf(
    FlashablePartition("Recovery", "recovery"),
    FlashablePartition("Boot", "boot"),
    FlashablePartition("init_boot", "init_boot"),
    FlashablePartition("vendor_boot", "vendor_boot"),
    FlashablePartition("vbmeta", "vbmeta"),
)

private data class RebootOption(val label: String, val target: FastbootRebootTarget)

private val rebootOptions = listOf(
    RebootOption("Bootloader", FastbootRebootTarget.BOOTLOADER),
    RebootOption("Recovery", FastbootRebootTarget.RECOVERY),
    RebootOption("System", FastbootRebootTarget.SYSTEM),
    RebootOption("FastbootD", FastbootRebootTarget.FASTBOOTD),
)

@Composable
fun FastbootScreen(fastbootOperations: FastbootOperations, logRepository: LogRepository, onOpenAdb: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    var connected by remember { mutableStateOf(ops.isConnected()) }
    var busy by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    // Once an auto-connect attempt on a *present* device comes back false,
    // it almost always means the user tapped "Deny" on the USB permission
    // popup - so stop silently re-triggering that popup every poll tick.
    // The manual "Connect fastboot device" button below always still works
    // (a button tap is an explicit user action, so it's fine to prompt
    // again there even after this guard trips).
    var autoConnectDenied by remember { mutableStateOf(false) }
    // Bus path of whichever device fastboot currently holds a live
    // connection to - see the matching comment in HomeScreen for why type
    // alone (isLikelyFastbootDevice) isn't enough: some recoveries
    // re-enumerate a brand new USB device with the SAME class signature
    // when switching modes, so only comparing the actual device identity
    // catches that swap.
    var lastFastbootDeviceName by remember { mutableStateOf<String?>(null) }
    var manualCommand by rememberSaveable { mutableStateOf("") }
    var manualResult by rememberSaveable { mutableStateOf("") }
    var ublStatusResult by rememberSaveable { mutableStateOf("") }
    var pendingFlash by remember { mutableStateOf<FlashablePartition?>(null) }
    val entries by logRepository.entries.collectAsState()

    // Keeps this screen's status in sync with what Home already saw. Home
    // only *enumerates* the USB device list to show "Detected - Fastboot"
    // (cheap, no permission dialog) - it never calls connect() itself, so
    // arriving here still needs its own connect() to actually claim the
    // interface. This polls instead of a one-shot LaunchedEffect so it:
    //  1) fires the permission popup immediately on first entry instead of
    //     only after leaving and reopening the screen,
    //  2) keeps polling if the cable gets plugged in while already here,
    //  3) notices if the connection drops (reboot, unplug) and flips the
    //     card back to "waiting" instead of staying stuck on green.
    LaunchedEffect(Unit) {
        while (isActive) {
            val liveFastboot = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }

            if (connected) {
                // isConnected() alone only reflects "we successfully opened
                // the interface at some point" - it stays true even after
                // the cable is physically unplugged, since nothing nulls it
                // out except an explicit disconnect() (e.g. after a reboot
                // command). Re-checking the device list here is what
                // actually notices an unplug and flips the card back to
                // "waiting", matching what Home already shows. Comparing
                // deviceName (not just presence) also catches a same-type
                // device SWAP - see the class-level comment above.
                if (liveFastboot == null || liveFastboot.deviceName != lastFastbootDeviceName || !ops.isConnected()) {
                    ops.disconnect()
                    connected = false
                    lastFastbootDeviceName = null
                }
            } else {
                if (liveFastboot == null) {
                    // Nothing plugged in (or it was unplugged) - clear the
                    // deny guard so a fresh/different device gets its own
                    // auto-connect attempt instead of inheriting the old one.
                    autoConnectDenied = false
                } else if (!autoConnectDenied && !connecting) {
                    connecting = true
                    connected = ops.connect()
                    connecting = false
                    if (connected) lastFastbootDeviceName = liveFastboot.deviceName
                    if (!connected) autoConnectDenied = true
                }
            }

            delay(1500)
        }
    }
    // One shared picker for every "Flash partition" row, same trick as A/B Partition
    // Tool - each row just records which partition it's for, then launches this.
    val flashPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = pendingFlash
        pendingFlash = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launchWithFeedback(snackbarHostState, "Flash ${target.label}", { busy = it }) {
            val path = SafFiles.copyToCache(context, uri, "${target.partition}.img")
            ops.flashPartition(target.partition, File(path))
        }
    }

    val twrpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchWithFeedback(snackbarHostState, "Boot TWRP image", { busy = it }) {
            val path = SafFiles.copyToCache(context, uri, "twrp.img")
            ops.boot(File(path))
        }
    }


    Scaffold(
        topBar = { SirohaTopBar("Fastboot Flash Tool", icon = Icons.Filled.Build, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Device Status", modifier = Modifier.padding(start = 4.dp))
                
                val showConnectRow = !connected && !connecting
                
                // Menyiapkan daftar item untuk ActionListGroup
                val statusEntries = mutableListOf<ActionEntry>()

                // 1. Entri Status (Waiting / Connected) yang bisa diklik
                statusEntries.add(
                    ActionEntry(
                        title = when {
                            connected -> "Connected (Ready for flashing)"
                            connecting -> "Connecting..."
                            autoConnectDenied -> "USB permission needed"
                            else -> "Waiting for device..."
                        },
                        subtitle = when {
                            connected -> null
                            connecting -> null
                            autoConnectDenied -> "USB permission was denied - tap to ask again"
                            else -> "Please connect a device in fastboot mode"
                        },
                        icon = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Usb,
                        enabled = true,
                        onClick = {
                            // Aksi saat status diklik (untuk interaksi / kesenangan)
                            if (!connected && !connecting) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Status: Waiting for device to connect in fastboot mode.")
                                }
                            }
                        }
                    )
                )

                // 2. Entri tombol Connect jika perangkat belum terhubung
                if (showConnectRow) {
                    statusEntries.add(
                        ActionEntry(
                            title = "Connect fastboot device",
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    autoConnectDenied = false
                                    connecting = true
                                    val ok = ops.connect()
                                    connecting = false
                                    connected = ok
                                    if (!ok) {
                                        autoConnectDenied = true
                                        snackbarHostState.showSnackbar("Couldn't connect - make sure the device is in fastboot mode and USB permission is granted")
                                    }
                                }
                            }
                        )
                    )
                }

                // Render menggunakan ActionListGroup agar bentuk dan efek kliknya sama persis seperti tombol lain
                ActionListGroup(statusEntries)
            }


            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Boot without flashing (e.g. TWRP)", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Boot TWRP image",
                            icon = Icons.Filled.SystemUpdate,
                            enabled = !busy,
                            onClick = { twrpPicker.launch(arrayOf("*/*")) }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.Bolt, "Flash partition", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    flashablePartitions.map { p ->
                        ActionEntry(
                            title = "Flash ${p.label}",
                            icon = Icons.Filled.Bolt,
                            enabled = !busy,
                            onClick = { pendingFlash = p; flashPicker.launch(arrayOf("*/*")) }
                        )
                    }
                )
            }

            item {
                SectionHeading(Icons.Filled.RestartAlt, "Reboot", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    rebootOptions.map { opt ->
                        ActionEntry(
                            title = opt.label,
                            icon = Icons.Filled.RestartAlt,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithFeedback(snackbarHostState, "Reboot to ${opt.label}", { busy = it }) {
                                    ops.reboot(opt.target).also { connected = false }
                                }
                            }
                        )
                    }
                )
            }

            item {
                SectionHeading(Icons.Filled.Info, "Status", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Check Status UBL (oem device-info)",
                            icon = Icons.Filled.Info,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Check Status UBL",
                                    isSuccess = { !it.contains("FAILED") && !it.contains("ERROR") },
                                    setBusy = { busy = it },
                                    onResult = { ublStatusResult = it }
                                ) { ops.rawCommandWithResponse("oem device-info") }
                            }
                        )
                    )
                )
                if (ublStatusResult.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Text(
                            ublStatusResult,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual command (raw wire protocol)")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Don't type the word \"fastboot\" - just the part after it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                     "Want to run  fastboot oem device-info?  →  type only  oem device-info\n" +
                     "Want to run  fastboot reboot recovery?  →  type only  reboot recovery\n" +
                     "Want to run  fastboot getvar product??  →  type only  getvar:product  (colon, not space)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        // No floating `label` here on purpose - the label cutout in the
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
                            colors = OutlinedTextFieldDefaults.colors(
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
                        // Immediate inline feedback - without this, a successful or failed
                        // command only showed up in "Recent activity" below, which reads
                        // as "nothing happened" unless you scroll down to check.
                        if (manualResult.isNotBlank()) {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                Text(
                                    manualResult,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (manualResult.contains("FAILED") || manualResult.contains("ERROR")) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "ADB tools", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Open ADB tools",
                            subtitle = "Connect, run shell/ADB commands, and sideload ZIPs - now in one place",
                            icon = Icons.Filled.Terminal,
                            onClick = onOpenAdb
                        )
                    )
                )
            }

            item { SectionHeading(Icons.Filled.Info, "Recent activity") }
            item {
                val recent = entries.takeLast(20)
                if (recent.isEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                        Text("No activity yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            recent.forEach { e ->
                                Text(
                                    e.format(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
