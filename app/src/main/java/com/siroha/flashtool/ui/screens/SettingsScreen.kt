package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.ExecutionMode
import com.siroha.flashtool.core.ExecutorProvider
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(executorProvider: ExecutorProvider, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Not checked yet") }
    var activeMode by remember { mutableStateOf<ExecutionMode?>(executorProvider.current()?.mode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.padding(4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Execution backend", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Active: ${activeMode?.name ?: "none"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(onClick = {
                scope.launch {
                    status = "Checking root access..."
                    val ready = executorProvider.root.requestAccess()
                    if (ready) {
                        executorProvider.setPreferred(executorProvider.root)
                        activeMode = ExecutionMode.ROOT
                        status = "Root granted. Using su for all commands."
                    } else {
                        status = "Root not available — grant this app superuser access in Magisk/KernelSU/APatch, or use Shizuku below."
                    }
                }
            }) { Text("Use Root (su)") }

            Button(onClick = {
                scope.launch {
                    status = "Requesting Shizuku permission..."
                    val ready = executorProvider.shizuku.requestAccess()
                    if (ready) {
                        executorProvider.setPreferred(executorProvider.shizuku)
                        activeMode = ExecutionMode.SHIZUKU
                        status = "Shizuku granted. No root needed."
                    } else {
                        status = "Shizuku isn't running. Start it first: pair via Wireless debugging " +
                            "(Android 11+) or run 'adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh' from a PC once."
                    }
                }
            }) { Text("Use Shizuku (no root)") }

            Text(
                "The app tries root first, then falls back to Shizuku automatically on launch. " +
                    "Use these buttons to force a specific backend or re-check permissions.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
