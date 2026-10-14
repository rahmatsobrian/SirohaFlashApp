package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.OperationStatus
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.siroha.flashtool.ui.components.OperationOverlay
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.components.launchWithTextFeedback
import androidx.compose.foundation.layout.PaddingValues
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import com.siroha.flashtool.ui.theme.HapticIconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Same "connected/good state" green used on Home/Settings, so this card
// reads as the same signal wherever it shows up in the app.
private val ActiveGreen = androidx.compose.ui.graphics.Color(0xFF84d996)



/** label shown in the list + actual partition name passed to fastboot. */
private data class FlashablePartition(
    val label: String,
    val partition: String,
    /** Logical (dynamic) partitions can normally only be flashed from FastbootD. */
    val logical: Boolean = false,
    /** Firmware / critical partitions: ask for confirmation before picking an image. */
    val risky: Boolean = false,
    /** Optional hint shown under the row. */
    val note: String? = null
)

/** A confirmation popup waiting for the person's answer. */
private class PendingConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit
)

private enum class PartitionDialog { FLASH_CUSTOM, ERASE_CUSTOM, CREATE_LP, RESIZE_LP, DELETE_LP }

private fun textOk(result: String) = !result.contains("FAILED") && !result.contains("ERROR")

private val flashablePartitions = listOf(
    FlashablePartition("Boot", "boot"),
    FlashablePartition("init_boot", "init_boot"),
    FlashablePartition("vendor_boot", "vendor_boot"),
    FlashablePartition("dtbo", "dtbo"),
    FlashablePartition("Recovery", "recovery"),
    FlashablePartition("vbmeta", "vbmeta"),
)

private val moreFlashablePartitions = listOf(
    FlashablePartition("splash", "splash", note = "Boot logo - some devices name it \"logo\""),
    FlashablePartition("logo", "logo"),
    FlashablePartition("vbmeta_system", "vbmeta_system"),
    FlashablePartition("vbmeta_vendor", "vbmeta_vendor"),
    FlashablePartition("system", "system", logical = true),
    FlashablePartition("system_ext", "system_ext", logical = true),
    FlashablePartition("vendor", "vendor", logical = true),
    FlashablePartition("product", "product", logical = true),
    FlashablePartition("odm", "odm", logical = true),
    FlashablePartition("super", "super", risky = true),
    FlashablePartition("modem", "modem", risky = true),
    FlashablePartition("bootloader", "bootloader", risky = true),
)

/** One reboot target, available through fastboot ([target]) and through ADB ([adbCommand]). */
private data class RebootOption(
    val label: String,
    val target: FastbootRebootTarget,
    val adbCommand: String,
    /** Run [adbCommand] as a shell command instead of a raw ADB service (needed for `reboot -p`). */
    val adbShell: Boolean = false,
    val feedback: String = "Reboot to $label"
)

private val rebootOptions = listOf(
    RebootOption("Bootloader", FastbootRebootTarget.BOOTLOADER, "reboot bootloader"),
    RebootOption("Recovery", FastbootRebootTarget.RECOVERY, "reboot recovery"),
    RebootOption("System", FastbootRebootTarget.SYSTEM, "reboot"),
    RebootOption("FastbootD", FastbootRebootTarget.FASTBOOTD, "reboot fastboot"),
    RebootOption("Power off", FastbootRebootTarget.POWEROFF, "reboot -p", adbShell = true, feedback = "Power off"),
    RebootOption("EDL (9008)", FastbootRebootTarget.EDL, "reboot edl", feedback = "Reboot to EDL"),
)

/**
 * Monospace result box shared by the sections below; red when the text
 * reports a failure. The X clears it, so a small result (device summary,
 * UBL status, ...) doesn't stay on screen until the person leaves the menu.
 */
@Composable
private fun ResultCard(text: String, onDismiss: () -> Unit) {
    if (text.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Row(verticalAlignment = Alignment.Top) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (textOk(text)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            HapticIconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss result")
            }
        }
    }
}

@Composable
fun FastbootScreen(fastbootOperations: FastbootOperations, adbOperations: AdbOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    val adb = adbOperations
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
    var ublStatusResult by rememberSaveable { mutableStateOf("") }
    var pendingFlash by remember { mutableStateOf<FlashablePartition?>(null) }
    var deviceInfoResult by rememberSaveable { mutableStateOf("") }
    var lockResult by rememberSaveable { mutableStateOf("") }
    var dynamicResult by rememberSaveable { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<PendingConfirm?>(null) }
    var partitionDialog by remember { mutableStateOf<PartitionDialog?>(null) }
    var slotChoice by remember { mutableStateOf<FlashablePartition?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var advancedAcknowledged by rememberSaveable { mutableStateOf(false) }
    // --- ADB (reboot + sideload live here now) ---
    var adbConnected by remember { mutableStateOf(adb.isConnected()) }
    var adbConnecting by remember { mutableStateOf(false) }
    var adbAutoConnectDenied by remember { mutableStateOf(false) }
    var lastAdbDeviceName by remember { mutableStateOf<String?>(null) }
    var rebootViaAdb by rememberSaveable { mutableStateOf(false) }
    var sideloadName by remember { mutableStateOf<String?>(null) }
    var sideloadRunning by remember { mutableStateOf(false) }

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
            val path = SafFiles.prepareFile(context, uri, "${target.partition}.img")
            ops.flashPartitionChecked(target.partition, File(path))
        }
    }

    val twrpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchWithFeedback(snackbarHostState, "Boot TWRP image", { busy = it }) {
            val path = SafFiles.prepareFile(context, uri, "twrp.img")
            ops.boot(File(path))
        }
    }

    // ADB actions never need a pre-connected device: like the fastboot ones they try to connect
    // themselves first, so the buttons are always usable.
    suspend fun ensureAdbConnected(): Boolean {
        if (adbConnected && adb.isConnected()) return true
        adbConnecting = true
        val ok = adb.connect()
        adbConnecting = false
        adbConnected = ok
        if (ok) {
            lastAdbDeviceName = UsbDeviceHelper.listDevices(context)
                .firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }?.deviceName
        }
        return ok
    }

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        sideloadRunning = true
        sideloadName = SafFiles.displayName(context, uri, "sideload.zip")
        busy = true
        OperationStatus.begin("ADB sideload: $sideloadName")
        scope.launch {
            val ok = try {
                ensureAdbConnected() && adb.sideloadFromUri(context, uri)
            } finally {
                OperationStatus.end()
            }
            busy = false
            sideloadRunning = false
            snackbarHostState.showSnackbar(if (ok) "Sideload finished - check Utilities > Logs" else "Sideload failed - check Utilities > Logs")
        }
    }

    // The reboot list follows whichever link is up (fastboot or ADB); the chips still let you override it.
    LaunchedEffect(connected, adbConnected) {
        if (connected) rebootViaAdb = false else if (adbConnected) rebootViaAdb = true
    }

    // Keeps the ADB link honest while this screen is open (same logic the old ADB Tools screen had):
    // drop it when the phone is unplugged, reconnect when a recovery re-enumerates (e.g. TWRP
    // switching into Sideload), and open it once per plug-in.
    LaunchedEffect(Unit) {
        while (isActive) {
            val liveAdb = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }
            if (!busy) {
                if (adbConnected) {
                    if (liveAdb == null) {
                        adb.disconnect()
                        adbConnected = false
                        lastAdbDeviceName = null
                    } else if (lastAdbDeviceName == null) {
                        lastAdbDeviceName = liveAdb.deviceName
                    } else if (liveAdb.deviceName != lastAdbDeviceName) {
                        adb.disconnect()
                        adbConnected = false
                        lastAdbDeviceName = null
                        adbAutoConnectDenied = false
                    }
                } else if (liveAdb == null) {
                    adbAutoConnectDenied = false
                } else if (!adbAutoConnectDenied && !adbConnecting) {
                    adbConnecting = true
                    adbConnected = adb.connect()
                    adbConnecting = false
                    if (adbConnected) lastAdbDeviceName = liveAdb.deviceName
                    if (!adbConnected) adbAutoConnectDenied = true
                }
            }
            delay(1500)
        }
    }

    fun startFlash(p: FlashablePartition) {
        pendingFlash = p
        flashPicker.launch(arrayOf("*/*"))
    }

    // Tapping a flash row: if the partition has A/B slots (or the device
    // can't say), ask which one - plain name, _a or _b - before picking the
    // image. Only a definite "no slots" skips the question.
    fun requestFlash(p: FlashablePartition) {
        scope.launch {
            val hasSlot = if (connected) ops.partitionHasSlot(p.partition) else null
            if (hasSlot == false) startFlash(p) else slotChoice = p
        }
    }

    slotChoice?.let { p ->
        SlotChoiceDialog(
            partition = p.partition,
            onChoose = { suffix ->
                slotChoice = null
                startFlash(p.copy(label = p.label + suffix, partition = p.partition + suffix))
            },
            onDismiss = { slotChoice = null }
        )
    }

    confirmAction?.let { c ->
        ConfirmActionDialog(
            title = c.title,
            message = c.message,
            confirmLabel = c.confirmLabel,
            onConfirm = { confirmAction = null; c.onConfirm() },
            onDismiss = { confirmAction = null }
        )
    }

    partitionDialog?.let { kind ->
        val needsSize = kind == PartitionDialog.CREATE_LP || kind == PartitionDialog.RESIZE_LP
        PartitionInputDialog(
            title = when (kind) {
                PartitionDialog.FLASH_CUSTOM -> "Flash custom partition"
                PartitionDialog.ERASE_CUSTOM -> "Erase custom partition"
                PartitionDialog.CREATE_LP -> "Create logical partition"
                PartitionDialog.RESIZE_LP -> "Resize logical partition"
                PartitionDialog.DELETE_LP -> "Delete logical partition"
            },
            description = when (kind) {
                PartitionDialog.FLASH_CUSTOM -> "Enter the exact partition name, including the slot suffix on A/B devices (e.g. boot_a). You'll pick the image next."
                PartitionDialog.ERASE_CUSTOM -> "Enter the exact partition name to erase. You'll be asked to confirm."
                PartitionDialog.CREATE_LP -> "Needs FastbootD. Enter the new partition's name and size in bytes."
                PartitionDialog.RESIZE_LP -> "Needs FastbootD. Enter the partition name and its new size in bytes."
                PartitionDialog.DELETE_LP -> "Needs FastbootD. Enter the logical partition to delete. You'll be asked to confirm."
            },
            confirmLabel = when (kind) {
                PartitionDialog.FLASH_CUSTOM -> "Choose image"
                PartitionDialog.ERASE_CUSTOM, PartitionDialog.DELETE_LP -> "Continue"
                PartitionDialog.CREATE_LP -> "Create"
                PartitionDialog.RESIZE_LP -> "Resize"
            },
            showSize = needsSize,
            onConfirm = { name, size ->
                partitionDialog = null
                when (kind) {
                    PartitionDialog.FLASH_CUSTOM -> {
                        startFlash(FlashablePartition(name, name))
                    }
                    PartitionDialog.ERASE_CUSTOM -> {
                        confirmAction = PendingConfirm(
                            "Erase $name?", "This permanently erases the '$name' partition. It cannot be undone.", "Erase"
                        ) {
                            scope.launchWithFeedback(snackbarHostState, "Erase $name", { busy = it }) { ops.erase(name) }
                        }
                    }
                    PartitionDialog.CREATE_LP -> {
                        if (size != null) {
                            scope.launchWithFeedback(snackbarHostState, "Create $name", { busy = it }) { ops.createLogicalPartition(name, size) }
                        }
                    }
                    PartitionDialog.RESIZE_LP -> {
                        if (size != null) {
                            scope.launchWithFeedback(snackbarHostState, "Resize $name", { busy = it }) { ops.resizeLogicalPartition(name, size) }
                        }
                    }
                    PartitionDialog.DELETE_LP -> {
                        confirmAction = PendingConfirm(
                            "Delete $name?", "Deleting a logical partition can leave the device unable to boot until it is flashed again.", "Delete"
                        ) {
                            scope.launchWithFeedback(snackbarHostState, "Delete $name", { busy = it }) { ops.deleteLogicalPartition(name) }
                        }
                    }
                }
            },
            onDismiss = { partitionDialog = null }
        )
    }

    Scaffold(
        topBar = { SirohaTopBar("Fastboot Flash Tool", icon = Icons.Filled.Build, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Device Status", modifier = Modifier.padding(start = 4.dp))
                
                val showConnectRow = !connected && !connecting
                
                // Menyiapkan daftar item untuk ActionListGroup
                val statusEntries = mutableListOf<ActionEntry>()

                val anyConnected = connected || adbConnected
                val anyConnecting = connecting || adbConnecting
                val anyDenied = autoConnectDenied || adbAutoConnectDenied

                statusEntries.add(
                    ActionEntry(
                        title = when {
                            connected -> "Connected - Fastboot"
                            adbConnected -> "Connected - ADB"
                            anyConnecting -> "Connecting..."
                            anyDenied -> "USB permission needed"
                            else -> "Waiting for device..."
                        },
                        subtitle = when {
                            connected -> "Ready for flashing"
                            adbConnected -> "Ready for reboot and sideload"
                            anyConnecting -> null
                            anyDenied -> "USB permission was denied - tap Connect device to ask again"
                            else -> "Connect a device in fastboot mode, or in ADB / recovery mode"
                        },
                        icon = if (anyConnected) Icons.Filled.CheckCircle else Icons.Filled.Usb,
                        enabled = true,
                        onClick = {
                            if (!anyConnected && !anyConnecting) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Status: waiting for a device in fastboot or ADB mode.")
                                }
                            }
                        }
                    )
                )

                // One Connect button for both: it connects to whichever mode the phone is in.
                if (!anyConnected && !anyConnecting) {
                    statusEntries.add(
                        ActionEntry(
                            title = "Connect device",
                            subtitle = "Fastboot or ADB - detected automatically",
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    autoConnectDenied = false
                                    adbAutoConnectDenied = false
                                    val devices = UsbDeviceHelper.listDevices(context)
                                    val hasFastboot = devices.any { UsbDeviceHelper.isLikelyFastbootDevice(it) }
                                    val hasAdb = devices.any { UsbDeviceHelper.isLikelyAdbDevice(it) }
                                    var ok = false
                                    if (hasFastboot || !hasAdb) {
                                        connecting = true
                                        ok = ops.connect()
                                        connecting = false
                                        connected = ok
                                        if (!ok) autoConnectDenied = true
                                    }
                                    if (!ok && (hasAdb || !hasFastboot)) {
                                        ok = ensureAdbConnected()
                                        if (!ok) adbAutoConnectDenied = true
                                    }
                                    if (!ok) {
                                        snackbarHostState.showSnackbar("Couldn't connect - check the mode (fastboot / ADB) and the USB permission")
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
                SectionHeading(Icons.Filled.CloudUpload, "ADB sideload", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (sideloadRunning) "Sideloading ${sideloadName ?: "..."}" else "Sideload ZIP",
                            subtitle = if (sideloadRunning) {
                                "In progress - details are in Utilities > Logs"
                            } else {
                                "Connects to the ADB device by itself. Recovery must be in ADB sideload mode; the ZIP is read in place (All files access) when possible"
                            },
                            icon = Icons.Filled.CloudUpload,
                            enabled = !busy,
                            onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
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
                            subtitle = p.note,
                            icon = Icons.Filled.Bolt,
                            enabled = !busy,
                            onClick = { requestFlash(p) }
                        )
                    }
                )
            }

            item {
                SectionHeading(Icons.Filled.SwapHoriz, "Slot control", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Check active slot",
                            icon = Icons.Filled.CheckCircle,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Check active slot", { busy = it }) { ops.getVar("current-slot").isNotBlank() } }
                        ),
                        ActionEntry(
                            title = "Set active: A",
                            icon = Icons.Filled.SwapHoriz,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Set active slot A", { busy = it }) { ops.setActiveSlot("a") } }
                        ),
                        ActionEntry(
                            title = "Set active: B",
                            icon = Icons.Filled.SwapHoriz,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Set active slot B", { busy = it }) { ops.setActiveSlot("b") } }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.RestartAlt, "Reboot", modifier = Modifier.padding(start = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = !rebootViaAdb, onClick = { rebootViaAdb = false }, label = { Text("Fastboot") })
                    FilterChip(selected = rebootViaAdb, onClick = { rebootViaAdb = true }, label = { Text("ADB") })
                }
                ActionListGroup(
                    rebootOptions.map { opt ->
                        ActionEntry(
                            title = opt.label,
                            icon = Icons.Filled.RestartAlt,
                            enabled = !busy,
                            onClick = {
                                if (rebootViaAdb) {
                                    scope.launchWithFeedback(snackbarHostState, "ADB reboot - ${opt.label}", { busy = it }) {
                                        if (!ensureAdbConnected()) return@launchWithFeedback false
                                        val outcome = if (opt.adbShell) adb.shellWithOutcome(opt.adbCommand) else adb.rawCommand(opt.adbCommand)
                                        // The device drops off USB after a reboot; the poll loop notices and resets the link.
                                        outcome.success.also { adbConnected = false }
                                    }
                                } else {
                                    scope.launchWithFeedback(snackbarHostState, opt.feedback, { busy = it }) {
                                        ops.reboot(opt.target).also { connected = false }
                                    }
                                }
                            }
                        )
                    }
                )
            }

            item {
                SectionHeading(Icons.Filled.Info, "Bootloader status", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Check Status UBL (oem device-info)",
                            icon = Icons.Filled.Info,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Check Status UBL",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { ublStatusResult = it }
                                ) { ops.rawCommandWithResponse("oem device-info") }
                            }
                        )
                    )
                )
                ResultCard(ublStatusResult) { ublStatusResult = "" }
            }

            item {
                SectionHeading(Icons.Filled.Tune, "Advanced", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (advancedOpen) "Hide advanced options" else "Show advanced options",
                            subtitle = if (advancedOpen) null else "System/super flashing, bootloader lock, dynamic partitions, device info",
                            icon = if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.Tune,
                            onClick = {
                                if (advancedOpen) {
                                    advancedOpen = false
                                } else if (advancedAcknowledged) {
                                    advancedOpen = true
                                } else {
                                    confirmAction = PendingConfirm(
                                        "Open advanced options?",
                                        "These options are not part of everyday flashing. A wrong image, partition or command can erase data or leave the device unable to boot. Only continue if you know what you're doing.",
                                        "I understand"
                                    ) {
                                        advancedAcknowledged = true
                                        advancedOpen = true
                                    }
                                }
                            }
                        )
                    )
                )
            }

            if (advancedOpen) {
            item {
                SectionHeading(Icons.Filled.Memory, "Device information", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Device summary",
                            subtitle = "Product, bootloader, slot, mode, max download size...",
                            icon = Icons.Filled.Memory,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Device summary",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { deviceInfoResult = it }
                                ) { ops.deviceSummary() }
                            }
                        ),
                        ActionEntry(
                            title = "All variables (getvar all)",
                            icon = Icons.Filled.Info,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "getvar all",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { deviceInfoResult = it }
                                ) { ops.rawCommandWithResponse("getvar all") }
                            }
                        ),
                        ActionEntry(
                            title = "Partition list",
                            subtitle = "Name, type, size and logical flag as reported by the bootloader",
                            icon = Icons.Filled.Storage,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Partition list",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { deviceInfoResult = it }
                                ) { ops.listPartitions() }
                            }
                        )
                    )
                )
                ResultCard(deviceInfoResult) { deviceInfoResult = "" }
            }

            item {
                SectionHeading(Icons.Filled.Bolt, "Flash more partitions", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    moreFlashablePartitions.map { p ->
                        ActionEntry(
                            title = "Flash ${p.label}",
                            subtitle = when {
                                p.risky -> "Firmware/critical - asks for confirmation"
                                p.logical -> "Logical partition - needs FastbootD"
                                else -> null
                            },
                            icon = if (p.risky) Icons.Filled.Warning else Icons.Filled.Bolt,
                            enabled = !busy,
                            onClick = {
                                if (p.risky) {
                                    confirmAction = PendingConfirm(
                                        "Flash ${p.label}?",
                                        "Flashing '${p.label}' with an image that doesn't match this exact device can leave it unable to boot. Only continue if you're sure the image is correct.",
                                        "Choose image"
                                    ) { requestFlash(p) }
                                } else {
                                    requestFlash(p)
                                }
                            }
                        )
                    } + ActionEntry(
                        title = "Flash custom partition...",
                        subtitle = "Type any partition name, e.g. boot_a",
                        icon = Icons.Filled.Bolt,
                        enabled = !busy,
                        onClick = { partitionDialog = PartitionDialog.FLASH_CUSTOM }
                    )
                )
                Text(
                    "Images larger than the device's max download size are refused up front - automatic sparse splitting isn't supported yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            item {
                SectionHeading(Icons.Filled.DeleteSweep, "Erase & wipe", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Erase cache",
                            icon = Icons.Filled.DeleteSweep,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Erase cache", { busy = it }) { ops.erase("cache") } }
                        ),
                        ActionEntry(
                            title = "Erase metadata",
                            subtitle = "Encryption metadata - data becomes unreadable",
                            icon = Icons.Filled.Warning,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Erase metadata?",
                                    "This removes the encryption metadata. Any existing user data becomes unreadable.",
                                    "Erase"
                                ) { scope.launchWithFeedback(snackbarHostState, "Erase metadata", { busy = it }) { ops.erase("metadata") } }
                            }
                        ),
                        ActionEntry(
                            title = "Erase userdata",
                            subtitle = "Deletes ALL personal data",
                            icon = Icons.Filled.Warning,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Erase userdata?",
                                    "All apps, photos and settings on the device will be permanently deleted.",
                                    "Erase"
                                ) { scope.launchWithFeedback(snackbarHostState, "Erase userdata", { busy = it }) { ops.erase("userdata") } }
                            }
                        ),
                        ActionEntry(
                            title = "Wipe data (like fastboot -w)",
                            subtitle = "userdata + cache + metadata",
                            icon = Icons.Filled.Warning,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Wipe all data?",
                                    "Erases userdata, then cache and metadata. All personal data will be permanently deleted.",
                                    "Wipe"
                                ) { scope.launchWithFeedback(snackbarHostState, "Wipe data", { busy = it }) { ops.wipeUserData() } }
                            }
                        ),
                        ActionEntry(
                            title = "Erase custom partition...",
                            icon = Icons.Filled.DeleteSweep,
                            enabled = !busy,
                            onClick = { partitionDialog = PartitionDialog.ERASE_CUSTOM }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.LockOpen, "Bootloader lock", modifier = Modifier.padding(start = 4.dp))
                fun runFlashing(sub: String, label: String) {
                    scope.launchWithTextFeedback(
                        snackbarHostState, label,
                        isSuccess = { textOk(it) },
                        setBusy = { busy = it },
                        onResult = { lockResult = it }
                    ) { ops.rawCommandWithResponse("flashing $sub") }
                }
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Check unlock ability",
                            subtitle = "flashing get_unlock_ability",
                            icon = Icons.Filled.Info,
                            enabled = !busy,
                            onClick = { runFlashing("get_unlock_ability", "Check unlock ability") }
                        ),
                        ActionEntry(
                            title = "Unlock bootloader",
                            subtitle = "Wipes data - confirm on the device screen",
                            icon = Icons.Filled.LockOpen,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Unlock bootloader?",
                                    "Unlocking erases all data and may void the warranty. Some devices (e.g. Xiaomi) also need an approved unlock - use Mi Unlock for those. You may need to confirm on the device.",
                                    "Unlock"
                                ) { runFlashing("unlock", "Unlock bootloader") }
                            }
                        ),
                        ActionEntry(
                            title = "Lock bootloader",
                            subtitle = "Only with a fully stock, matching ROM",
                            icon = Icons.Filled.Lock,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Lock bootloader?",
                                    "Locking with a modified or mismatched ROM can leave the device unable to boot, and wipes data. Only continue with a stock ROM that matches the device.",
                                    "Lock"
                                ) { runFlashing("lock", "Lock bootloader") }
                            }
                        ),
                        ActionEntry(
                            title = "Unlock critical partitions",
                            icon = Icons.Filled.LockOpen,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Unlock critical partitions?",
                                    "This allows flashing bootloader-level partitions and may wipe data.",
                                    "Unlock"
                                ) { runFlashing("unlock_critical", "Unlock critical") }
                            }
                        ),
                        ActionEntry(
                            title = "Lock critical partitions",
                            icon = Icons.Filled.Lock,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Lock critical partitions?",
                                    "Only continue if the device is running matching stock firmware.",
                                    "Lock"
                                ) { runFlashing("lock_critical", "Lock critical") }
                            }
                        )
                    )
                )
                ResultCard(lockResult) { lockResult = "" }
            }

            item {
                SectionHeading(Icons.Filled.Layers, "Dynamic partitions (FastbootD)", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Check mode (is-userspace)",
                            subtitle = "yes = FastbootD, no = bootloader fastboot",
                            icon = Icons.Filled.Info,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Check mode",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { dynamicResult = it }
                                ) { ops.rawCommandWithResponse("getvar is-userspace") }
                            }
                        ),
                        ActionEntry(
                            title = "Create logical partition...",
                            icon = Icons.Filled.Layers,
                            enabled = !busy,
                            onClick = { partitionDialog = PartitionDialog.CREATE_LP }
                        ),
                        ActionEntry(
                            title = "Resize logical partition...",
                            icon = Icons.Filled.Layers,
                            enabled = !busy,
                            onClick = { partitionDialog = PartitionDialog.RESIZE_LP }
                        ),
                        ActionEntry(
                            title = "Delete logical partition...",
                            icon = Icons.Filled.Warning,
                            enabled = !busy,
                            onClick = { partitionDialog = PartitionDialog.DELETE_LP }
                        ),
                        ActionEntry(
                            title = "Snapshot update: merge",
                            subtitle = "Finish a pending virtual A/B merge",
                            icon = Icons.Filled.Layers,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithTextFeedback(
                                    snackbarHostState, "Snapshot merge",
                                    isSuccess = { textOk(it) },
                                    setBusy = { busy = it },
                                    onResult = { dynamicResult = it }
                                ) { ops.rawCommandWithResponse("snapshot-update merge") }
                            }
                        ),
                        ActionEntry(
                            title = "Snapshot update: cancel",
                            subtitle = "Can leave the device unable to boot",
                            icon = Icons.Filled.Warning,
                            enabled = !busy,
                            onClick = {
                                confirmAction = PendingConfirm(
                                    "Cancel snapshot update?",
                                    "Cancelling a pending virtual A/B update can leave the system unable to boot.",
                                    "Cancel update"
                                ) {
                                    scope.launchWithTextFeedback(
                                        snackbarHostState, "Snapshot cancel",
                                        isSuccess = { textOk(it) },
                                        setBusy = { busy = it },
                                        onResult = { dynamicResult = it }
                                    ) { ops.rawCommandWithResponse("snapshot-update cancel") }
                                }
                            }
                        )
                    )
                )
                ResultCard(dynamicResult) { dynamicResult = "" }
            }

            }


        }
        // Floating progress + live log while an action runs, so it's visible without scrolling down.
        OperationOverlay(
            busy = busy,
            logRepository = logRepository,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
      }
    }
}
