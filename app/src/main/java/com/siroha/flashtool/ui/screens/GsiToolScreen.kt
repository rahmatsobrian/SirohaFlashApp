package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.FastbootRebootTarget
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GsiToolScreen(fastbootOperations: FastbootOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    var busy by remember { mutableStateOf(false) }
    val entries by logRepository.entries.collectAsState()

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "gsi_system.img")
        scope.launchWithFeedback(snackbarHostState, "Flash GSI image", { busy = it }) { ops.flashPartition("system", File(path)) }
    }
    val vbmetaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "vbmeta.img")
        scope.launchWithFeedback(snackbarHostState, "Flash VBMETA", { busy = it }) { ops.flashVbmeta(File(path), disableVerity = true) }
    }

    Scaffold(
        topBar = { SirohaTopBar("GSI ROM Flash Tool", icon = Icons.Filled.RocketLaunch, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Order (from flash.sh): connect → vbmeta → reboot to FastbootD → erase system → " +
                        "delete logical partitions → flash GSI → reboot to recovery.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                FilledTonalButton(
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { ops.connect() } }
                ) { Icon(Icons.Filled.Usb, contentDescription = null); Text("  Connect fastboot device") }
            }

            item {
                SectionHeading(Icons.Filled.RocketLaunch, "Flash steps")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vbmetaPicker.launch(arrayOf("*/*")) }) { Text("Flash VBMETA (disable-verity)") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to FastbootD", { busy = it }) { ops.reboot(FastbootRebootTarget.FASTBOOTD) }
                    }) { Text("Reboot → FastbootD") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Check is-userspace", { busy = it }) { ops.getVar("is-userspace").isNotBlank() }
                    }) { Text("Check is-userspace") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Erase system", { busy = it }) { ops.erase("system") }
                    }) { Text("Erase system") }
                    FilledTonalButton(
                        onClick = {
                            scope.launchWithFeedback(snackbarHostState, "Wipe Super", { busy = it }) {
                                val results = ops.wipeOptionalDynamicPartitions()
                                val ok = results.count { it.second }
                                logRepository.info("GSI", "Wipe optional dynamic partitions: $ok/${results.size} succeeded (failures on partitions this device doesn't have are expected)")
                                ok > 0
                            }
                        }
                    ) { Text("Wipe Super (product/system_ext/odm)") }
                    FilledTonalButton(onClick = { gsiPicker.launch(arrayOf("*/*")) }) { Text("Flash GSI system image") }
                    FilledTonalButton(onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Reboot to Recovery", { busy = it }) { ops.reboot(FastbootRebootTarget.RECOVERY) }
                    }) { Text("Reboot → Recovery") }
                }
            }

            item { SectionHeading(Icons.Filled.Info, "Recent activity") }
            items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
