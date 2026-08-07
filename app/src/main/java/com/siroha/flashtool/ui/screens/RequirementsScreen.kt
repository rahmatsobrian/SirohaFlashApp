package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.BinaryManager
import com.siroha.flashtool.core.ExecutionMode
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch

private data class Check(val label: String, val ok: Boolean, val hint: String)

@Composable
private fun CheckRow(check: Check) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(
            if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(check.label, style = MaterialTheme.typography.bodyLarge)
            if (!check.ok) Text(check.hint, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Replaces flash.sh's menu_install — that module was Termux package
 * management (pkg install adb/python3/etc.), which doesn't apply once this
 * is a native APK. What still matters natively is: is root or Shizuku
 * ready, is a USB device visible, is the right qdl binary bundled.
 */
@Composable
fun RequirementsScreen(executorProvider: ExecutorProvider, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { SirohaTopBar("Requirements & Status", icon = Icons.Filled.TaskAlt, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                enabled = !running,
                onClick = {
                    scope.launch {
                        running = true
                        val rootReady = executorProvider.root.requestAccess()
                        val shizukuReady = executorProvider.shizuku.isReady()
                        val usbDevices = UsbDeviceHelper.listDevices(context)
                        val qdl = BinaryManager.qdlPath(context)
                        checks = listOf(
                            Check("Root (su) access", rootReady, "Grant this app superuser access in Magisk/KernelSU/APatch, or use Shizuku instead."),
                            Check("Shizuku access", shizukuReady, "Start Shizuku (pairing or one adb command) then grant permission from Settings."),
                            Check(
                                "Privileged execution available",
                                rootReady || shizukuReady,
                                "Root or Shizuku is required for QDL flashing and shell-based operations."
                            ),
                            Check(
                                "qdl binary present for this ABI",
                                qdl != null,
                                "Missing libqdl.so for ABI(s): ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
                            ),
                            Check(
                                "USB device visible",
                                usbDevices.isNotEmpty(),
                                "No USB device detected — connect the target via OTG (see USB/OTG Fix)."
                            ),
                        )
                        running = false
                    }
                }
            ) { Text(if (running) "Checking..." else "Run checks") }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (checks.isEmpty()) {
                        Text("Tap \"Run checks\" to see current status.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        checks.forEach { CheckRow(it) }
                    }
                }
            }
        }
    }
}
