package com.siroha.flashtool.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.PartitionInfo
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.theme.Checkbox
import com.siroha.flashtool.ui.theme.FilledTonalButton
import com.siroha.flashtool.ui.theme.HapticIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val LARGE_BACKUP_BYTES = 1L shl 30

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Backs up partitions from a device in fastboot mode. The screen reads the
 * device's own partition list, lets the person tick any number of them
 * (or select all), and saves each one as <name>.img into a folder they
 * choose. Nothing on the device is modified.
 *
 * Reading uses the fastboot `fetch` command, which not every bootloader
 * implements (many only allow it when unlocked, and logical partitions
 * generally need FastbootD). A refused partition is reported and skipped;
 * the bootloader's own message is in Recent activity.
 */
@Composable
fun BackupPartitionScreen(
    fastbootOperations: FastbootOperations,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    var connected by remember { mutableStateOf(ops.isConnected()) }
    var connecting by remember { mutableStateOf(false) }
    var autoConnectDenied by remember { mutableStateOf(false) }
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
    var currentLabel by remember { mutableStateOf("") }
    var progressDone by remember { mutableLongStateOf(0L) }
    var progressTotal by remember { mutableLongStateOf(0L) }
    var stopRequested by remember { mutableStateOf(false) }
    val stopFlag = remember { AtomicBoolean(false) }
    val entries by logRepository.entries.collectAsState()

    suspend fun loadPartitions() {
        loadingList = true
        listMessage = ""
        val table = ops.readPartitionTable()
        partitions = table.partitions
        listMessage = table.message.orEmpty()
        // Drop selections that no longer exist (different device / reload).
        val known = table.partitions.map { it.name }.toSet()
        selected = selected.filter { it in known }.toSet()
        loadingList = false
    }

    // Same idea as the Fastboot screen: pick up an already-open connection,
    // auto-connect when a fastboot device shows up, and notice an unplug.
    LaunchedEffect(Unit) {
        while (isActive) {
            val live = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
            if (connected) {
                if (live == null || !ops.isConnected()) {
                    ops.disconnect()
                    connected = false
                    partitions = emptyList()
                    selected = emptySet()
                }
            } else if (live == null) {
                autoConnectDenied = false
            } else if (!autoConnectDenied && !connecting) {
                connecting = true
                connected = ops.connect()
                connecting = false
                if (!connected) autoConnectDenied = true
            }
            delay(1500)
        }
    }

    // Read the partition list as soon as a device is connected.
    LaunchedEffect(connected) {
        if (connected && partitions.isEmpty() && !loadingList && !busy) loadPartitions()
    }

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

        var lastShown = 0L
        var bytes = 0L
        val ok = try {
            context.contentResolver.openOutputStream(doc, "w")?.use { raw ->
                val out = BufferedOutputStream(raw, 256 * 1024)
                val fetched = ops.fetchPartition(name, out) { done, total ->
                    bytes = done
                    // Throttle UI updates - this fires once per 16 KB chunk.
                    if (done - lastShown >= 512 * 1024 || done == total) {
                        lastShown = done
                        progressDone = done
                        progressTotal = total
                    }
                }
                out.flush()
                fetched
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
            false to "FAILED  $name - refused or unsupported (see Recent activity)"
        }
    }

    fun runBackup(treeUri: Uri, names: List<String>) {
        scope.launch {
            busy = true
            stopFlag.set(false)
            stopRequested = false
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
                    currentLabel = "(${index + 1}/${names.size}) $name"
                    progressDone = 0L
                    progressTotal = 0L
                    val (ok, line) = backupOne(parent, name)
                    if (ok) okCount++
                    lines += line
                }
            } finally {
                busy = false
                currentLabel = ""
                stopRequested = false
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
            scope.launch { snackbarHostState.showSnackbar("Connect a fastboot device first") }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeading(Icons.Filled.SystemUpdate, "Device Status", modifier = Modifier.padding(start = 4.dp))
                val rows = mutableListOf(
                    ActionEntry(
                        title = when {
                            connected -> "Connected (fastboot)"
                            connecting -> "Connecting..."
                            autoConnectDenied -> "USB permission needed"
                            else -> "Waiting for device..."
                        },
                        subtitle = when {
                            connected || connecting -> null
                            autoConnectDenied -> "USB permission was denied - tap Connect to ask again"
                            else -> "Connect a device in fastboot mode"
                        },
                        icon = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Usb,
                        onClick = {}
                    )
                )
                if (!connected && !connecting) {
                    rows += ActionEntry(
                        title = "Connect fastboot device",
                        icon = Icons.Filled.Usb,
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                autoConnectDenied = false
                                connecting = true
                                connected = ops.connect()
                                connecting = false
                                if (!connected) {
                                    autoConnectDenied = true
                                    snackbarHostState.showSnackbar("Couldn't connect - make sure the device is in fastboot mode and USB permission is granted")
                                }
                            }
                        }
                    )
                }
                ActionListGroup(rows)
            }

            item {
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
                    "Backups use the fastboot \"fetch\" command, which not every bootloader supports - some only allow it on an unlocked bootloader, and logical partitions usually need FastbootD. Refused partitions are skipped and reported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            if (partitions.isNotEmpty()) {
                item {
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

            if (busy && currentLabel.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                currentLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            if (progressTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (progressDone.toFloat() / progressTotal).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${fmtSize(progressDone)} / ${fmtSize(progressTotal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !stopRequested,
                                onClick = { stopFlag.set(true); stopRequested = true }
                            ) { Text(if (stopRequested) "Stopping after this partition..." else "Stop after this partition") }
                        }
                    }
                }
            }

            if (resultText.isNotBlank()) {
                item {
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

            items(visible, key = { it.name }) { p ->
                val checked = p.name in selected
                Surface(
                    onClick = { selected = if (checked) selected - p.name else selected + p.name },
                    enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val detail = listOfNotNull(
                                p.type,
                                p.sizeBytes?.let { fmtSize(it) },
                                if (p.logical) "logical - FastbootD" else null
                            ).joinToString(" | ")
                            if (detail.isNotEmpty()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeading(Icons.Filled.Info, "Recent activity", modifier = Modifier.padding(start = 4.dp)) }
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
    }
}
