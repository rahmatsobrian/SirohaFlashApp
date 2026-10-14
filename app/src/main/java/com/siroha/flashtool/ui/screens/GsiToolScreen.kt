package com.siroha.flashtool.ui.screens

import com.siroha.flashtool.ui.components.OperationOverlay
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.siroha.flashtool.core.FastbootRebootTarget
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import java.io.File
import androidx.compose.foundation.layout.PaddingValues

@Composable
fun GsiToolScreen(fastbootOperations: FastbootOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ops = fastbootOperations
    var busy by remember { mutableStateOf(false) }

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchWithFeedback(snackbarHostState, "Flash GSI image", { busy = it }) {
            val path = SafFiles.prepareFile(context, uri, "gsi_system.img")
            ops.flashPartition("system", File(path))
        }
    }
    val vbmetaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launchWithFeedback(snackbarHostState, "Flash VBMETA", { busy = it }) {
            val path = SafFiles.prepareFile(context, uri, "vbmeta.img")
            ops.flashVbmeta(File(path), disableVerity = true)
        }
    }

    // Same 7 steps flash.sh always ran in this exact order - kept as data so the
    // "Step N of 7" subtitle below can't drift out of sync with the row order.
    val steps = listOf(
        ActionEntry(
            title = "Flash VBMETA (disable-verity)",
            subtitle = "Step 1 of 7",
            icon = Icons.Filled.Shield,
            enabled = !busy,
            onClick = { vbmetaPicker.launch(arrayOf("*/*")) }
        ),
        ActionEntry(
            title = "Reboot → FastbootD",
            subtitle = "Step 2 of 7",
            icon = Icons.Filled.RestartAlt,
            enabled = !busy,
            onClick = { scope.launchWithFeedback(snackbarHostState, "Reboot to FastbootD", { busy = it }) { ops.reboot(FastbootRebootTarget.FASTBOOTD) } }
        ),
        ActionEntry(
            title = "Check is-userspace",
            subtitle = "Step 3 of 7",
            icon = Icons.Filled.CheckCircle,
            enabled = !busy,
            onClick = { scope.launchWithFeedback(snackbarHostState, "Check is-userspace", { busy = it }) { ops.getVar("is-userspace").isNotBlank() } }
        ),
        ActionEntry(
            title = "Erase system",
            subtitle = "Step 4 of 7",
            icon = Icons.Filled.DeleteSweep,
            enabled = !busy,
            onClick = { scope.launchWithFeedback(snackbarHostState, "Erase system", { busy = it }) { ops.erase("system") } }
        ),
        ActionEntry(
            title = "Wipe Super (product/system_ext/odm)",
            subtitle = "Step 5 of 7",
            icon = Icons.Filled.DeleteSweep,
            enabled = !busy,
            onClick = {
                scope.launchWithFeedback(snackbarHostState, "Wipe Super", { busy = it }) {
                    val results = ops.wipeOptionalDynamicPartitions()
                    val ok = results.count { it.second }
                    logRepository.info("GSI", "Wipe optional dynamic partitions: $ok/${results.size} succeeded (failures on partitions this device doesn't have are expected)")
                    ok > 0
                }
            }
        ),
        ActionEntry(
            title = "Flash GSI system image",
            subtitle = "Step 6 of 7",
            icon = Icons.Filled.RocketLaunch,
            enabled = !busy,
            onClick = { gsiPicker.launch(arrayOf("*/*")) }
        ),
        ActionEntry(
            title = "Reboot → Recovery",
            subtitle = "Step 7 of 7",
            icon = Icons.Filled.RestartAlt,
            enabled = !busy,
            onClick = { scope.launchWithFeedback(snackbarHostState, "Reboot to Recovery", { busy = it }) { ops.reboot(FastbootRebootTarget.RECOVERY) } }
        ),
    )

    Scaffold(
        topBar = { SirohaTopBar("GSI ROM Flash Tool", icon = Icons.Filled.RocketLaunch, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
            modifier = Modifier.fillMaxSize(), // Hapus .padding(16.dp) dari sini
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
                SectionHeading(Icons.Filled.RocketLaunch, "Flash steps", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(steps)
            }
        }
        OperationOverlay(
            busy = busy,
            logRepository = logRepository,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }
}
