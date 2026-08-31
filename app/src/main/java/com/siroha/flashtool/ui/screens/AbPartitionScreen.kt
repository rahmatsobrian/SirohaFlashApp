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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import com.siroha.flashtool.ui.theme.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.theme.LocalHapticTrigger
import java.io.File
import androidx.compose.foundation.layout.PaddingValues

/** label shown in the list + actual partition name passed to fastboot. */
private data class SlotPartition(val label: String, val partition: String)

private val slotPartitions = listOf(
    SlotPartition("boot (active)", "boot"),
    SlotPartition("boot_a", "boot_a"),
    SlotPartition("boot_b", "boot_b"),
    SlotPartition("init_boot_a", "init_boot_a"),
    SlotPartition("init_boot_b", "init_boot_b"),
    SlotPartition("recovery (active)", "recovery"),
    SlotPartition("recovery_a", "recovery_a"),
    SlotPartition("recovery_b", "recovery_b"),
    SlotPartition("vendor_boot_a", "vendor_boot_a"),
    SlotPartition("vendor_boot_b", "vendor_boot_b"),
    SlotPartition("vbmeta_a", "vbmeta_a"),
    SlotPartition("vbmeta_b", "vbmeta_b"),
)

@Composable
fun AbPartitionScreen(fastbootOperations: FastbootOperations, logRepository: LogRepository, onOpenAdb: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticTrigger.current
    val ops = fastbootOperations
    var busy by remember { mutableStateOf(false) }
    var pendingFlash by remember { mutableStateOf<SlotPartition?>(null) }
    var slotDropdownExpanded by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableStateOf(slotPartitions.first()) }
    val entries by logRepository.entries.collectAsState()

    // One shared picker for every "Flash by slot" row — each row just records which
    // partition it's for, then launches this, instead of each row owning its own
    // ActivityResultLauncher (which doesn't fit the data-driven ActionListGroup shape).
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
        topBar = { SirohaTopBar("A/B Partition Tool", icon = Icons.Filled.SwapHoriz, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Connect fastboot device",
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { ops.connect() } }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.RocketLaunch, "Flash by slot", modifier = Modifier.padding(start = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = slotDropdownExpanded,
                            onExpandedChange = { slotDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedSlot.label,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Partition") },
                                leadingIcon = { Icon(Icons.Filled.RocketLaunch, contentDescription = null) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slotDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                                    .fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = slotDropdownExpanded,
                                onDismissRequest = { slotDropdownExpanded = false },
                                modifier = Modifier.exposedDropdownSize()
                            ) {
                                slotPartitions.forEach { slot ->
                                    DropdownMenuItem(
                                        text = { Text(slot.label) },
                                        onClick = { haptic(); selectedSlot = slot; slotDropdownExpanded = false }
                                    )
                                }
                            }
                        }
                        FilledTonalButton(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { pendingFlash = selectedSlot; flashPicker.launch(arrayOf("*/*")) }
                        ) {
                            Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                            Text("  Flash ${selectedSlot.label}")
                        }
                    }
                }
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
