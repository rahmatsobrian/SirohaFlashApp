package com.siroha.flashtool.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.OperationStatus
import com.siroha.flashtool.core.PartitionInfo
import com.siroha.flashtool.core.PartitionTable
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.OperationOverlay
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.theme.Checkbox
import com.siroha.flashtool.ui.theme.FilledTonalButton
import com.siroha.flashtool.ui.theme.HapticIconButton
import com.siroha.flashtool.ui.theme.LocalCardCornerRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val LARGE_BACKUP_BYTES = 1L shl 30

/** Where the partitions are read from: the fastboot `fetch` command, or an ADB shell (recovery / root). */
private enum class BackupSource { FASTBOOT, ADB }

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Segmented-list corners like the menu lists, only tighter: a smaller outer
 * radius than [LocalCardCornerRadius] so a long partition list stays compact.
 */
@Composable
private fun compactGroupShape(index: Int, count: Int): Shape {
    val outer: Dp = (LocalCardCornerRadius.current * 0.7f).coerceAtLeast(8.dp)
    val inner = 3.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

@Composable
private fun CompactPartitionRow(
    partition: PartitionInfo,
    checked: Boolean,
    enabled: Boolean,
    shape: Shape,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        enabled = enabled,
        shape = shape,
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The stock 48dp touch target would make every row tall; the whole row is the tap target anyway.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            Text(
                partition.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val detail = listOfNotNull(
                if (partition.logical) "logical" else null,
                partition.type,
                partition.sizeBytes?.let { fmtSize(it) }
            ).joinToString(" | ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** A lazy-list item followed by the standard gap, so the tight partition cluster below can use 0 spacing. */
private fun LazyListScope.sectionItem(content: @Composable () -> Unit) {
    item { Column(modifier = Modifier.padding(bottom = 12.dp)) { content() } }
}

/**
 * Backs up partitions from the connected device. The screen reads the
 * device's own partition list, lets the person tick any number of them
 * (or select all), and saves each one as <name>.img into a folder they
 * choose. Nothing on the device is modified.
 *
 * Two ways to read, picked automatically from what is plugged in:
 *  - Fastboot: the `fetch` command. Many bootloaders (especially older
 *    Qualcomm LK ones) don't implement it at all and answer "unknown command".
 *  - ADB: `cat` on the by-name block devices. Works from a recovery such as
 *    TWRP (boot it from Fastboot > Boot TWRP image - that runs from RAM and
 *    writes nothing) or on a rooted device.
 */
@Composable
fun BackupPartitionScreen(
    fastbootOperations: FastbootOperations,
    adbOperations: AdbOperations,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    val adb = adbOperations
    var source by remember { mutableStateOf<BackupSource?>(if (ops.isConnected()) BackupSource.FASTBOOT else null) }
    var connecting by remember { mutableStateOf(false) }
    var fbDenied by remember { mutableStateOf(false) }
    var adbDenied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var partitions by remember { mutableStateOf<List<PartitionInfo>>(emptyList()) }
    var loadingList by remember { mutableStateOf(false) }
    var listMessage by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var largeConfirm by remember { mutableStateOf<Pair<List<String>, Long>?>(null) }
    var pendingNames by remember { mutableStateOf<List<String>>(emptyList()) }

    var resultText by remember { mutableStateOf("") }
    var stopRequested by remember { mutableStateOf(false) }
    val stopFlag = remember { AtomicBoolean(false) }
    val entries by logRepository.entries.collectAsState()
    val connected = source != null

    suspend fun loadPartitions() {
        loadingList = true
        listMessage = ""
        val table = when (source) {
            BackupSource.FASTBOOT -> ops.readPartitionTable()
            BackupSource.ADB -> adb.readPartitionTable()
            null -> PartitionTable(emptyList(), "Not connected")
        }
        partitions = table.partitions
        listMessage = table.message.orEmpty()
        // Drop selections that no longer exist (different device / reload).
        val known = table.partitions.map { it.name }.toSet()
        selected = selected.filter { it in known }.toSet()
        loadingList = false
    }

    // Auto-connect to whichever transport is present: a fastboot device, or
    // an ADB one (recovery / USB debugging). Also notices an unplug or a mode
    // switch (e.g. fastboot -> TWRP) and drops the stale list.
    LaunchedEffect(Unit) {
        while (isActive) {
            val devices = UsbDeviceHelper.listDevices(context)
            val fbDev = devices.firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
            val adbDev = devices.firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }
            when (source) {
                BackupSource.FASTBOOT -> if (fbDev == null || !ops.isConnected()) {
                    ops.disconnect()
                    source = null
                    partitions = emptyList()
                    selected = emptySet()
                }
                BackupSource.ADB -> if (adbDev == null || !adb.isConnected()) {
                    adb.disconnect()
                    source = null
                    partitions = emptyList()
                    selected = emptySet()
                }
                null -> {
                    if (fbDev == null) fbDenied = false
                    if (adbDev == null) adbDenied = false
                    if (!connecting) {
                        if (fbDev != null && !fbDenied) {
                            connecting = true
                            val ok = ops.connect()
                            connecting = false
                            if (ok) source = BackupSource.FASTBOOT else fbDenied = true
                        } else if (adbDev != null && !adbDenied) {
                            connecting = true
                            val ok = adb.connect()
                            connecting = false
                            if (ok) source = BackupSource.ADB else adbDenied = true
                        }
                    }
                }
            }
            delay(1500)
        }
    }

    // Read the partition list as soon as a device is connected.
    LaunchedEffect(source) {
        if (source != null && partitions.isEmpty() && !loadingList && !busy) loadPartitions()
    }

    // Set when a fastboot backup fails because the bootloader has no `fetch` (an AtomicBoolean so it survives recomposition).
    val noFetchSupport = remember { AtomicBoolean(false) }

    suspend fun backupOne(parent: Uri, name: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var created: Uri? = null
        try {
            created = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", "$name.img")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // handled below: created stays null
        }
        val doc = created ?: return@withContext false to "FAILED  $name - couldn't create the file in that folder"

        val expected = partitions.firstOrNull { it.name == name }?.sizeBytes
        var bytes = 0L
        val ok = try {
            context.contentResolver.openOutputStream(doc, "w")?.use { raw ->
                val out = BufferedOutputStream(raw, 256 * 1024)
                val done = when (source) {
                    BackupSource.FASTBOOT -> ops.fetchPartition(name, out) { d, _ -> bytes = d }
                    BackupSource.ADB -> adb.backupPartition(name, out, expected) { d, _ -> bytes = d }
                    null -> false
                }
                out.flush()
                done
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) {
            true to "OK      $name (${fmtSize(bytes)})"
        } else {
            // Don't leave an empty/partial file behind.
            try {
                DocumentsContract.deleteDocument(context.contentResolver, doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best effort
            }
            val reason = when (source) {
                BackupSource.FASTBOOT -> ops.lastFetchFailure
                BackupSource.ADB -> adb.lastBackupFailure
                null -> null
            }
            if (source == BackupSource.FASTBOOT && reason?.contains("unknown command", ignoreCase = true) == true) {
                noFetchSupport.set(true)
            }
            false to "FAILED  $name - ${reason ?: "refused or unsupported (see Recent activity)"}"
        }
    }

    fun runBackup(treeUri: Uri, names: List<String>) {
        scope.launch {
            busy = true
            stopFlag.set(false)
            stopRequested = false
            noFetchSupport.set(false)
            resultText = ""
            val lines = mutableListOf<String>()
            var okCount = 0
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                for ((index, name) in names.withIndex()) {
                    if (stopFlag.get()) {
                        lines += "Stopped - ${names.size - index} partition(s) not backed up"
                        break
                    }
                    OperationStatus.begin("Backup (${index + 1}/${names.size}) $name")
                    val (ok, line) = backupOne(parent, name)
                    if (ok) okCount++
                    lines += line
                }
            } finally {
                OperationStatus.end()
                busy = false
                stopRequested = false
            }
            if (noFetchSupport.get()) {
                lines += ""
                lines += "This bootloader has no 'fetch' command, so it can't dump partitions itself (being unlocked doesn't change that)."
                lines += "Workaround: Fastboot > Boot TWRP image (runs from RAM, writes nothing). When TWRP is up, this screen switches to ADB mode and can read the partitions."
            }
            resultText = lines.joinToString("\n") + "\n\n$okCount of ${names.size} backed up"
            snackbarHostState.showSnackbar(
                if (okCount == names.size) "Backup finished" else "Backup finished with $okCount of ${names.size} saved"
            )
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null && pendingNames.isNotEmpty()) runBackup(uri, pendingNames)
    }

    fun proceedToFolder(names: List<String>) {
        pendingNames = names
        treePicker.launch(null)
    }

    fun requestBackup() {
        if (!connected) {
            scope.launch { snackbarHostState.showSnackbar("Connect a device first") }
            return
        }
        val names = partitions.filter { it.name in selected }.map { it.name }
        if (names.isEmpty()) return
        val total = partitions.filter { it.name in selected }.sumOf { it.sizeBytes ?: 0L }
        if (total > LARGE_BACKUP_BYTES) largeConfirm = names to total else proceedToFolder(names)
    }

    largeConfirm?.let { (names, total) ->
        ConfirmActionDialog(
            title = "Large backup",
            message = "${names.size} partitions, about ${fmtSize(total)} in total. This can take a long time over USB and needs that much free space in the folder you choose.",
            confirmLabel = "Choose folder",
            onConfirm = { largeConfirm = null; proceedToFolder(names) },
            onDismiss = { largeConfirm = null }
        )
    }

    if (showAddDialog) {
        PartitionInputDialog(
            title = "Add partition manually",
            description = "Use this if the list doesn't show a partition you need. Type the exact name (e.g. boot_b); it is added to the list and selected.",
            confirmLabel = "Add",
            showSize = false,
            onConfirm = { name, _ ->
                showAddDialog = false
                if (partitions.none { it.name == name }) {
                    partitions = (partitions + PartitionInfo(name, null, null, false)).sortedBy { it.name }
                }
                selected = selected + name
            },
            onDismiss = { showAddDialog = false }
        )
    }

    val visible = partitions.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
    val selectedInfos = partitions.filter { it.name in selected }
    val selectedBytes = selectedInfos.sumOf { it.sizeBytes ?: 0L }

    Scaffold(
        topBar = { SirohaTopBar("Backup Partition", icon = Icons.Filled.Backup, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                sectionItem {
                    SectionHeading(Icons.Filled.SystemUpdate, "Device Status", modifier = Modifier.padding(start = 4.dp))
                    val rows = mutableListOf(
                        ActionEntry(
                            title = when {
                                source == BackupSource.FASTBOOT -> "Connected (fastboot)"
                                source == BackupSource.ADB -> "Connected (ADB)"
                                connecting -> "Connecting..."
                                fbDenied || adbDenied -> "Permission needed"
                                else -> "Waiting for device..."
                            },
                            subtitle = when {
                                connected || connecting -> null
                                fbDenied || adbDenied -> "USB permission or ADB authorization was denied - tap Connect to try again"
                                else -> "Connect a device in fastboot mode, or in recovery/TWRP with ADB"
                            },
                            icon = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Usb,
                            onClick = {}
                        )
                    )
                    if (!connected && !connecting) {
                        rows += ActionEntry(
                            title = "Connect device",
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    fbDenied = false
                                    adbDenied = false
                                    connecting = true
                                    val devices = UsbDeviceHelper.listDevices(context)
                                    val fbDev = devices.firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
                                    val adbDev = devices.firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }
                                    var ok = false
                                    if (fbDev != null && ops.connect()) {
                                        source = BackupSource.FASTBOOT
                                        ok = true
                                    } else if (adbDev != null && adb.connect()) {
                                        source = BackupSource.ADB
                                        ok = true
                                    }
                                    connecting = false
                                    if (!ok) {
                                        snackbarHostState.showSnackbar("Couldn't connect - check the device mode, USB permission and the USB debugging prompt")
                                    }
                                }
                            }
                        )
                    }
                    ActionListGroup(rows)
                }

                sectionItem {
                    SectionHeading(Icons.Filled.Storage, "Partitions on device", modifier = Modifier.padding(start = 4.dp))
                    ActionListGroup(
                        listOf(
                            ActionEntry(
                                title = if (loadingList) "Reading partitions..." else "Read partition list",
                                subtitle = "Asks the device which partitions it has",
                                icon = Icons.Filled.Refresh,
                                enabled = connected && !busy && !loadingList,
                                onClick = { scope.launch { loadPartitions() } }
                            ),
                            ActionEntry(
                                title = "Add partition manually...",
                                subtitle = "If the list is empty or missing one",
                                icon = Icons.Filled.Save,
                                enabled = !busy,
                                onClick = { showAddDialog = true }
                            )
                        )
                    )
                    if (listMessage.isNotBlank() && partitions.isEmpty()) {
                        Text(
                            listMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (listMessage.startsWith("ERROR") || listMessage.startsWith("FAILED")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                    }
                    Text(
                        when (source) {
                            BackupSource.ADB -> "ADB mode: partitions are read from /dev/block with a root shell - this works in a recovery such as TWRP or on a rooted device."
                            else -> "Fastboot mode uses the \"fetch\" command, which many bootloaders don't have. If a backup says unknown command, boot TWRP from the Fastboot menu and come back - this screen switches to ADB mode by itself."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                    )
                }

                if (partitions.isNotEmpty()) {
                    sectionItem {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    placeholder = { Text("Filter partitions (e.g. boot)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = !busy && visible.isNotEmpty(),
                                        onClick = { selected = selected + visible.map { it.name } }
                                    ) { Text(if (filter.isBlank()) "Select all" else "Select shown", maxLines = 1) }
                                    FilledTonalButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = !busy && selected.isNotEmpty(),
                                        onClick = { selected = emptySet() }
                                    ) { Text("Clear", maxLines = 1) }
                                }
                                Text(
                                    "${selected.size} of ${partitions.size} selected" +
                                        if (selected.isNotEmpty()) " - ${fmtSize(selectedBytes)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = selected.isNotEmpty() && !busy && connected,
                                    onClick = { requestBackup() }
                                ) {
                                    Icon(Icons.Filled.Backup, contentDescription = null)
                                    Text("  Backup selected (${selected.size})", maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (resultText.isNotBlank()) {
                    sectionItem {
                        val failed = resultText.contains("FAILED")
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        resultText,
                                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                }
                                HapticIconButton(onClick = { resultText = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss result")
                                }
                            }
                        }
                    }
                }

                // Segmented partition list: one tight cluster, big corners only at its two ends.
                itemsIndexed(visible, key = { _, p -> p.name }) { index, p ->
                    val checked = p.name in selected
                    Column(modifier = Modifier.padding(bottom = 2.dp)) {
                        CompactPartitionRow(
                            partition = p,
                            checked = checked,
                            enabled = !busy,
                            shape = compactGroupShape(index, visible.size),
                            onToggle = { selected = if (checked) selected - p.name else selected + p.name }
                        )
                    }
                }
                item { Column(modifier = Modifier.height(12.dp)) {} }

                sectionItem { SectionHeading(Icons.Filled.Info, "Recent activity", modifier = Modifier.padding(start = 4.dp)) }
                item {
                    val recent = entries.takeLast(12)
                    if (recent.isEmpty()) {
                        Text(
                            "No activity yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
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

            OperationOverlay(
                busy = busy,
                logRepository = logRepository,
                modifier = Modifier.align(Alignment.BottomCenter),
                actions = {
                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !stopRequested,
                        onClick = { stopFlag.set(true); stopRequested = true }
                    ) { Text(if (stopRequested) "Stopping after this partition..." else "Stop after this partition") }
                }
            )
        }
    }
}
