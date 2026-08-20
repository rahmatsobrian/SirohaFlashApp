package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
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
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FlashOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch

@Composable
fun BypassUblScreen(
    executorProvider: ExecutorProvider,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var output by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { SirohaTopBar("Bypass UBL — Redmi 4A", icon = Icons.Filled.Shield, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(Icons.Filled.Warning, "Device-specific — Redmi 4A (rolex) only", MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "This flow uses the bundled MIUI 10.2.3.0 firehose loader and partition map. " +
                                "Running it on any other model or ROM version can brick the device. Only " +
                                "proceed if this is the exact device you intend to service.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item { Text("Put the Redmi 4A into EDL (9008) mode and connect via USB OTG before starting.") }

            item {
                Button(
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            running = true
                            output = listOf()
                            val executor = executorProvider.detect()
                            val ops = FlashOperations(context, executor, logRepository)
                            ops.runBypassUblRedmi4A().collect { line -> output = output + line }
                            running = false
                            val hadError = output.any { it.contains("[error]", ignoreCase = true) }
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                if (hadError) "Bypass UBL — failed (see output below)" else "Bypass UBL — finished (see output below)"
                            )
                        }
                    }
                ) { Text(if (running) "Running..." else "Start Bypass UBL") }
            }

            item { SectionHeading(Icons.Filled.Article, "Output") }
            items(output) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
