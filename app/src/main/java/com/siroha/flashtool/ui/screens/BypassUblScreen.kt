package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FlashOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.HelpOutline

@Composable
fun BypassUblScreen(
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var output by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }
    // Lets this be tested without an actual EDL-capable device on hand -
    // see FlashOperations.runBypassUblRedmi4ASimulated for what "dry run"
    // means here (it never touches the executor/USB/qdl at all, unlike
    // qdl's own --dry-run flag which still needs a real device to
    // handshake with).
    var dryRun by remember { mutableStateOf(false) }
    var simulateFailure by remember { mutableStateOf(false) }
        // Tambahan state untuk allow missing (default true untuk bypass)
    var allowMissing by remember { mutableStateOf(false) }
    // qdl no longer needs root/Shizuku - the no-root USB bridge is always
    // used now (see FlashOperations.runQdlNoRootBridge), so there's no
    // toggle for it anymore.

    Scaffold(
        topBar = { SirohaTopBar("Bypass UBL - Redmi 4A", icon = Icons.Filled.Shield, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(Icons.Filled.Warning, "Device-specific - Redmi 4A (rolex) only", MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "This flow uses the bundled MIUI 10.2.3.0 firehose loader and partition map. " +
                                "Running it on any other model or ROM version can brick the device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Prerequisite step, same tonal-card treatment as Fastboot's manual-command hint -
            // reads as "read this before you tap the button" instead of a stray line of text.
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        Text(
                            "Put the Redmi 4A into EDL (9008) mode and connect via USB OTG before starting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Test-only controls - lets this flow be exercised (including its
            // failure-path UI) without an EDL-capable device on hand.
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Dry run (no device needed)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "Simulates the output below without touching a real device, USB, or qdl.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Switch(checked = dryRun, onCheckedChange = { dryRun = it; if (!it) simulateFailure = false }, enabled = !running)
                        }
                        if (dryRun) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 34.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Simulate a failure instead",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        "To test the failed-run output/snackbar too",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                Switch(checked = simulateFailure, onCheckedChange = { simulateFailure = it }, enabled = !running)
                            }
                        }
                    }
                }
            }
            
             // Opsi Allow Missing Files (SISIPKAN DI SINI)
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.HelpOutline, 
                            contentDescription = null, 
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Allow missing files",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Skip program/patch entries whose file isn't found in the bypass folder instead of failing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowMissing, 
                            onCheckedChange = { allowMissing = it }, 
                            enabled = !running
                        )
                    }
                }
            }

            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (running) "Running..." else if (dryRun) "Start Bypass UBL (dry run)" else "Start Bypass UBL",
                            subtitle = if (running) "Please wait, this can take a while" else "Flashes the bundled Redmi 4A (rolex) loader and partition map",
                            icon = Icons.Filled.PlayArrow,
                            enabled = !running,
                            onClick = {
                                scope.launch {
                                    running = true
                                    output = listOf()
                                    if (dryRun) {
                                        val ops = FlashOperations(context, logRepository)
                                        ops.runBypassUblRedmi4ASimulated(simulateFailure).collect { line -> output = output + line }
                                    } else {
                                        val ops = FlashOperations(context, logRepository)
                                        ops.runBypassUblRedmi4ANoRootBridge(allowMissing).collect { line -> output = output + line }
                                    }
                                    running = false
                                    val hadError = output.any { it.contains("[error]", ignoreCase = true) }
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(
                                        if (hadError) "Bypass UBL - failed (see output below)" else "Bypass UBL - finished (see output below)"
                                    )
                                }
                            }
                        )
                    )
                )
            }

            item { SectionHeading(Icons.Filled.Article, "Output", modifier = Modifier.padding(top = 4.dp)) }

            item {
                if (output.isEmpty()) {
                    Text(
                        "Output will appear here once you start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            output.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (line.contains("[error]", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
