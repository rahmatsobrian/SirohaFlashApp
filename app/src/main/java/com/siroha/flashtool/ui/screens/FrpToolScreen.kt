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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import com.siroha.flashtool.ui.components.launchWithTextFeedback
import java.io.File

@Composable
fun FrpToolScreen(fastbootOperations: FastbootOperations, adbOperations: AdbOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val fastboot = fastbootOperations
    val adb = adbOperations
    var busy by remember { mutableStateOf(false) }
    var adbConnected by remember { mutableStateOf(adb.isConnected()) }
    var adbCommand by rememberSaveable { mutableStateOf("") }
    var adbResult by rememberSaveable { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SafFiles.copyToCache(context, uri, "sideload.zip")
        scope.launchWithFeedback(snackbarHostState, "Sideload", { busy = it }) { adb.sideload(File(path)) }
    }

    Scaffold(
        topBar = { SirohaTopBar("FRP Remove Tool", icon = Icons.Filled.Shield, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
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
            }

            item {
                SectionHeading(Icons.Filled.Usb, "SPRD FRP — via fastboot")
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { fastboot.connect() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null); Text("  Connect fastboot device")
                }
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { scope.launchWithFeedback(snackbarHostState, "Erase persist", { busy = it }) { fastboot.erase("persist") } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null); Text("  Erase persist (SPRD FRP reset)")
                }
            }

            item {
                SectionHeading(Icons.Filled.PhoneAndroid, "Samsung / SPRD-MTK FRP — via ADB")
                Text(
                    "Uses this app's from-scratch ADB-over-USB client. First connection needs you to tap " +
                        "\"Allow USB debugging\" on the target device's screen, then tap Connect again.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Connect ADB", { busy = it }) {
                            adb.connect().also { adbConnected = it }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null); Text(if (adbConnected) "  Reconnect ADB device" else "  Connect ADB device")
                }
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launchWithFeedback(snackbarHostState, "Samsung FRP reset", { busy = it }) {
                            adb.shell("am start -n com.google.android.gsf.login/")
                            adb.shell("am start -n com.google.android.gsf.login.LoginActivity")
                            adb.shell("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
                            true
                        }
                    }
                ) { Icon(Icons.Filled.Lock, contentDescription = null); Text("  Samsung FRP reset") }
            }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launchWithFeedback(snackbarHostState, "SPRD/MTK FRP reset", { busy = it }) {
                            adb.shell("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
                            true
                        }
                    }
                ) { Icon(Icons.Filled.Lock, contentDescription = null); Text("  SPRD/MTK FRP reset") }
            }

            item { SectionHeading(Icons.Filled.CloudUpload, "ADB Sideload") }
            item {
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { sideloadPicker.launch(arrayOf("application/zip", "*/*")) }
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null); Text("  Sideload ZIP")
                }
            }

            item {
                SectionHeading(Icons.Filled.Terminal, "Manual ADB shell command")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Runs as  adb shell <what you type>  — needs ADB connected above first.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = adbCommand,
                            onValueChange = { adbCommand = it },
                            placeholder = { Text("getprop ro.build.version.release") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilledTonalButton(
                                enabled = adbCommand.isNotBlank() && !busy,
                                onClick = {
                                    scope.launchWithTextFeedback(
                                        snackbarHostState, "Send ADB command",
                                        isSuccess = { !it.startsWith("ERROR:") },
                                        setBusy = { busy = it },
                                        onResult = { adbResult = it.ifBlank { "(no output)" } }
                                    ) { adb.shell(adbCommand.trim()) }
                                }
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null)
                                Text("  Send")
                            }
                        }
                        if (adbResult.isNotBlank()) {
                            Text(adbResult, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
