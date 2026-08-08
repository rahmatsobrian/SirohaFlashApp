package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FrpToolScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fastboot = remember { FastbootOperations(context, logRepository) }
    val adb = remember { AdbOperations(context, logRepository) }
    var busy by remember { mutableStateOf(false) }
    var adbConnected by remember { mutableStateOf(false) }
    val entries by logRepository.entries.collectAsState()

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = com.siroha.flashtool.core.SafFiles.copyToCache(context, uri, "sideload.zip")
        scope.launch { busy = true; adb.sideload(File(path)); busy = false }
    }

    Scaffold(
        topBar = { SirohaTopBar("FRP Remove Tool", icon = Icons.Filled.Shield, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SectionHeading(Icons.Filled.WarningAmber, "Only your own device", MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "Removing Factory Reset Protection on a device that isn't yours may be illegal. " +
                            "Only use this on a device you own or are explicitly authorized to service.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            SectionHeading(Icons.Filled.Usb, "SPRD FRP — via fastboot")
            FilledTonalButton(enabled = !busy, onClick = { scope.launch { busy = true; fastboot.connect(); busy = false } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Usb, contentDescription = null); Text("  Connect fastboot device")
            }
            FilledTonalButton(enabled = !busy, onClick = { scope.launch { busy = true; fastboot.erase("persist"); busy = false } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Lock, contentDescription = null); Text("  Erase persist (SPRD FRP reset)")
            }

            SectionHeading(Icons.Filled.PhoneAndroid, "Samsung / SPRD-MTK FRP — via ADB")
            Text(
                "Uses this app's from-scratch ADB-over-USB client. First connection needs you to tap " +
                    "\"Allow USB debugging\" on the target device's screen, then tap Connect again.",
                style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(enabled = !busy, onClick = { scope.launch { busy = true; adbConnected = adb.connect(); busy = false } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Usb, contentDescription = null); Text(if (adbConnected) "  Reconnect ADB device" else "  Connect ADB device")
            }
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        adb.shell("am start -n com.google.android.gsf.login/")
                        adb.shell("am start -n com.google.android.gsf.login.LoginActivity")
                        adb.shell("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
                        busy = false
                    }
                }
            ) { Icon(Icons.Filled.Lock, contentDescription = null); Text("  Samsung FRP reset") }
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        adb.shell("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
                        busy = false
                    }
                }
            ) { Icon(Icons.Filled.Lock, contentDescription = null); Text("  SPRD/MTK FRP reset") }

            SectionHeading(Icons.Filled.CloudUpload, "ADB Sideload")
            FilledTonalButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null); Text("  Sideload ZIP")
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
