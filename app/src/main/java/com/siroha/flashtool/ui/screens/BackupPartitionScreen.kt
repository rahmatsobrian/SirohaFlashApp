package com.siroha.flashtool.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithTextFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream

private data class BackupTarget(val label: String, val partition: String, val note: String? = null)

private val backupTargets = listOf(
    BackupTarget("boot", "boot"),
    BackupTarget("init_boot", "init_boot"),
    BackupTarget("vendor_boot", "vendor_boot"),
    BackupTarget("recovery", "recovery"),
    BackupTarget("dtbo", "dtbo"),
    BackupTarget("vbmeta", "vbmeta"),
    BackupTarget("splash", "splash", note = "Boot logo - some devices name it \"logo\""),
)

private fun mib(bytes: Long) = "%.1f MiB".format(bytes / (1024.0 * 1024.0))

/**
 * Saves a partition from a device in fastboot mode to a file the person
 * picks, using the fastboot `fetch` command. Nothing on the device is
 * modified. `fetch` is not implemented by every bootloader (and many only
 * allow it when unlocked), so a refusal is expected on some devices and the
 * bootloader's own message is shown in Recent activity.
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
    var pendingBackup by remember { mutableStateOf<String?>(null) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var progressDone by remember { mutableLongStateOf(0L) }
    var progressTotal by remember { mutableLongStateOf(0L) }
    val entries by logRepository.entries.collectAsState()

    // Same idea as the Fastboot screen: pick up an already-open connection,
    // auto-connect when a fastboot device shows up, and notice an unplug.
    LaunchedEffect(Unit) {
        while (isActive) {
            val live = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
            if (connected) {
                if (live == null || !ops.isConnected()) {
                    ops.disconnect()
                    connected = false
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

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val name = pendingBackup
        pendingBackup = null
        if (uri == null || name == null) return@rememberLauncherForActivityResult
        scope.launchWithTextFeedback(
            snackbarHostState, "Backup $name",
            isSuccess = { !it.contains("FAILED") && !it.contains("ERROR") },
            setBusy = { busy = it },
            onResult = { resultText = it }
        ) {
            progressDone = 0L
            progressTotal = 0L
            var lastShown = 0L
            val ok = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                    val out = BufferedOutputStream(raw, 256 * 1024)
                    val fetched = ops.fetchPartition(name, out) { done, total ->
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
            }
            if (ok) {
                "OKAY saved ${SafFiles.displayName(context, uri, "$name.img")} (${mib(progressTotal)})"
            } else {
                // Don't leave an empty/partial file behind.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                "FAILED backup $name - the bootloader may not support fetch, may require an unlocked bootloader, " +
                    "or refused this partition. See Recent activity for its exact message."
            }
        }
    }

    fun startBackup(partition: String) {
        if (!connected) {
            scope.launch { snackbarHostState.showSnackbar("Connect a fastboot device first") }
            return
        }
        pendingBackup = partition
        createDoc.launch("$partition.img")
    }

    if (showCustomDialog) {
        PartitionInputDialog(
            title = "Backup other partition",
            description = "Enter the partition name. On A/B devices the current slot is added automatically unless you type the suffix yourself (e.g. boot_b).",
            confirmLabel = "Choose save location",
            showSize = false,
            onConfirm = { name, _ ->
                showCustomDialog = false
                startBackup(name)
            },
            onDismiss = { showCustomDialog = false }
        )
    }

    Scaffold(
        topBar = { SirohaTopBar("Backup Partition", icon = Icons.Filled.Backup, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                SectionHeading(Icons.Filled.Backup, "Backup partition", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    backupTargets.map { t ->
                        ActionEntry(
                            title = "Backup ${t.label}",
                            subtitle = t.note,
                            icon = Icons.Filled.Save,
                            enabled = !busy,
                            onClick = { startBackup(t.partition) }
                        )
                    } + ActionEntry(
                        title = "Backup other partition...",
                        subtitle = "Type any partition name",
                        icon = Icons.Filled.Save,
                        enabled = !busy,
                        onClick = { showCustomDialog = true }
                    )
                )
                Text(
                    "Reads the partition from the device and saves it as an .img file you choose. Nothing on the device is changed. " +
                        "This uses the fastboot \"fetch\" command, which not every bootloader supports - some only allow it on an unlocked bootloader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            if (busy && progressTotal > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = { (progressDone.toFloat() / progressTotal).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${mib(progressDone)} / ${mib(progressTotal)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (resultText.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Text(
                            resultText,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (resultText.contains("FAILED") || resultText.contains("ERROR")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
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
