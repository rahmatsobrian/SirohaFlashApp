package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import java.io.File

/** A file-picker button that flashes the picked file straight to [partition] once tapped. */
@Composable
private fun FlashButton(label: String, partition: String, ops: FastbootOperations, busy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "$partition.img")
        scope.launch { busy(true); ops.flashPartition(partition, File(path)); busy(false) }
    }
    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(label) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AbPartitionScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember { FastbootOperations(context, logRepository) }
    var busy by remember { mutableStateOf(false) }
    val entries by logRepository.entries.collectAsState()

    Scaffold(
        topBar = { SirohaTopBar("A/B Partition Tool", icon = Icons.Filled.SwapHoriz, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { busy = true; ops.connect(); busy = false } }
            ) { Text("Connect fastboot device") }

            SectionHeading(Icons.Filled.RocketLaunch, "Flash by slot")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashButton("boot (active)", "boot", ops) { busy = it }
                FlashButton("boot_a", "boot_a", ops) { busy = it }
                FlashButton("boot_b", "boot_b", ops) { busy = it }
                FlashButton("init_boot_a", "init_boot_a", ops) { busy = it }
                FlashButton("init_boot_b", "init_boot_b", ops) { busy = it }
                FlashButton("recovery (active)", "recovery", ops) { busy = it }
                FlashButton("recovery_a", "recovery_a", ops) { busy = it }
                FlashButton("recovery_b", "recovery_b", ops) { busy = it }
                FlashButton("vendor_boot_a", "vendor_boot_a", ops) { busy = it }
                FlashButton("vendor_boot_b", "vendor_boot_b", ops) { busy = it }
                FlashButton("vbmeta_a", "vbmeta_a", ops) { busy = it }
                FlashButton("vbmeta_b", "vbmeta_b", ops) { busy = it }
            }

            SectionHeading(Icons.Filled.SwapHoriz, "Slot control")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { scope.launch { busy = true; ops.getVar("current-slot"); busy = false } }) { Text("Check active slot") }
                FilledTonalButton(onClick = { scope.launch { busy = true; ops.setActiveSlot("a"); busy = false } }) { Text("Set active: A") }
                FilledTonalButton(onClick = { scope.launch { busy = true; ops.setActiveSlot("b"); busy = false } }) { Text("Set active: B") }
            }

            SectionHeading(Icons.Filled.SystemUpdate, "Boot without flashing (e.g. TWRP)")
            val twrpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                val path = SafFiles.copyToCache(context, uri, "twrp.img")
                scope.launch { busy = true; ops.boot(File(path)); busy = false }
            }
            FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { twrpPicker.launch(arrayOf("*/*")) }) { Text("Boot TWRP image") }

            Text(
                "ADB Sideload isn't implemented here (separate chunked transfer protocol, not a plain " +
                    "shell command) — use a dedicated ADB app for ZIP sideloading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            SectionHeading(Icons.Filled.Usb, "Recent activity")
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
