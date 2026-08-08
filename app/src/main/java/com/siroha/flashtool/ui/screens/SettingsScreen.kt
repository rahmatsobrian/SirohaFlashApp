package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.ExecutionMode
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.ThemeMode
import com.siroha.flashtool.core.ThemePreferences
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED (pure black)"
}

@Composable
fun SettingsScreen(
    executorProvider: ExecutorProvider,
    themePreferences: ThemePreferences,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Not checked yet") }
    var activeMode by remember { mutableStateOf<ExecutionMode?>(executorProvider.current()?.mode) }
    val themeMode by themePreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dynamicColor by themePreferences.dynamicColorEnabled.collectAsState(initial = true)

    Scaffold(
        topBar = { SirohaTopBar("Settings", icon = Icons.Filled.Settings, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- Appearance ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading(Icons.Filled.Palette, "Appearance")
                Card {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { scope.launch { themePreferences.setThemeMode(mode) } }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = themeMode == mode, onClick = { scope.launch { themePreferences.setThemeMode(mode) } })
                                Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Material You dynamic color", style = MaterialTheme.typography.bodyLarge)
                            Text("Match the app's palette to your wallpaper (Android 12+)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = dynamicColor, onCheckedChange = { scope.launch { themePreferences.setDynamicColorEnabled(it) } })
                    }
                }
            }

            // ---- Execution backend ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading(Icons.Filled.Security, "Execution backend")
                Card {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Active: ${activeMode?.name ?: "none"}", style = MaterialTheme.typography.bodyLarge)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
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
                    }
                ) { Text("Use Root (su)") }
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
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
                    }
                ) { Text("Use Shizuku (no root)") }
                Text(
                    "The app tries root first, then falls back to Shizuku automatically on launch. " +
                        "Use these buttons to force a specific backend or re-check permissions.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ---- Data ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading(Icons.Filled.Contrast, "Data")
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { logRepository.clear() }
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                    Text("  Clear session logs")
                }
            }
        }
    }
}
