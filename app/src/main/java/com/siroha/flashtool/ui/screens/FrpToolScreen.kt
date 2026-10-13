package com.siroha.flashtool.ui.screens

import com.siroha.flashtool.ui.components.OperationOverlay
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import androidx.compose.foundation.layout.PaddingValues

@Composable
fun FrpToolScreen(fastbootOperations: FastbootOperations, adbOperations: AdbOperations, logRepository: LogRepository, onOpenAdb: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val fastboot = fastbootOperations
    val adb = adbOperations
    var busy by remember { mutableStateOf(false) }
    var adbConnected by remember { mutableStateOf(adb.isConnected()) }

    Scaffold(
        topBar = { SirohaTopBar("FRP Remove Tool", icon = Icons.Filled.Shield, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
            modifier = Modifier.fillMaxSize(), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeading(Icons.Filled.Usb, "SPRD FRP - via fastboot", modifier = Modifier.padding(start = 4.dp))
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Connect fastboot device",
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { fastboot.connect() } }
                        ),
                        ActionEntry(
                            title = "Erase persist (SPRD FRP reset)",
                            icon = Icons.Filled.Lock,
                            enabled = !busy,
                            onClick = { scope.launchWithFeedback(snackbarHostState, "Erase persist", { busy = it }) { fastboot.erase("persist") } }
                        )
                    )
                )
            }

            item {
                SectionHeading(Icons.Filled.PhoneAndroid, "Samsung / SPRD-MTK FRP - via ADB", modifier = Modifier.padding(start = 4.dp))
                Text(
                    "Uses this app's from-scratch ADB-over-USB client. First connection needs you to tap " +
                        "\"Allow USB debugging\" on the target device's screen, then tap Connect again. " +
                        "Need a manual command or a sideload instead? Use ADB tools from Tools.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (adbConnected) "Reconnect ADB device" else "Connect ADB device",
                            subtitle = if (adbConnected) "Connected" else null,
                            icon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithFeedback(snackbarHostState, "Connect ADB", { busy = it }) {
                                    adb.connect().also { adbConnected = it }
                                }
                            }
                        ),
                        ActionEntry(
                            title = "Samsung FRP reset",
                            icon = Icons.Filled.Lock,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithFeedback(snackbarHostState, "Samsung FRP reset", { busy = it }) {
                                    adb.shell("am start -n com.google.android.gsf.login/")
                                    adb.shell("am start -n com.google.android.gsf.login.LoginActivity")
                                    val last = adb.shellWithOutcome("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
                                    last.success
                                }
                            }
                        ),
                        ActionEntry(
                            title = "SPRD/MTK FRP reset",
                            icon = Icons.Filled.Lock,
                            enabled = !busy,
                            onClick = {
                                scope.launchWithFeedback(snackbarHostState, "SPRD/MTK FRP reset", { busy = it }) {
                                    adb.shellWithOutcome("content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1").success
                                }
                            }
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
                            subtitle = "Manual commands and ZIP sideload - now in one place",
                            icon = Icons.Filled.Terminal,
                            onClick = onOpenAdb
                        )
                    )
                )
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
