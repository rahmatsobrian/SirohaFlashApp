package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GsiToolScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember { FastbootOperations(context, logRepository) }
    var busy by remember { mutableStateOf(false) }
    val entries by logRepository.entries.collectAsState()

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "gsi_system.img")
        scope.launch { busy = true; ops.flashPartition("system", File(path)); busy = false }
    }
    val vbmetaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "vbmeta.img")
        scope.launch { busy = true; ops.flashVbmeta(File(path), disableVerity = true); busy = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GSI ROM Flash Tool") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Order (from flash.sh): connect → vbmeta → reboot to FastbootD → erase system → " +
                    "delete logical partitions → flash GSI → reboot to recovery.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(enabled = !busy, onClick = { scope.launch { busy = true; ops.connect(); busy = false } }) {
                Text("Connect fastboot device")
            }
            OutlinedButton(onClick = { vbmetaPicker.launch(arrayOf("*/*")) }) { Text("Flash VBMETA (disable-verity)") }
            OutlinedButton(onClick = { scope.launch { busy = true; ops.reboot(FastbootRebootTarget.FASTBOOTD); busy = false } }) {
                Text("Reboot Fastboot → FastbootD")
            }
            OutlinedButton(onClick = { scope.launch { busy = true; ops.getVar("is-userspace"); busy = false } }) {
                Text("Check is-userspace")
            }
            OutlinedButton(onClick = { scope.launch { busy = true; ops.erase("system"); busy = false } }) {
                Text("Erase system partition")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { busy = true; ops.deleteLogicalPartition("product_a"); busy = false } }) {
                    Text("Delete product_a")
                }
                OutlinedButton(onClick = { scope.launch { busy = true; ops.deleteLogicalPartition("product_b"); busy = false } }) {
                    Text("Delete product_b")
                }
            }
            OutlinedButton(onClick = { gsiPicker.launch(arrayOf("*/*")) }) { Text("Flash GSI system image") }
            OutlinedButton(onClick = { scope.launch { busy = true; ops.reboot(FastbootRebootTarget.RECOVERY); busy = false } }) {
                Text("Reboot → Recovery")
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
