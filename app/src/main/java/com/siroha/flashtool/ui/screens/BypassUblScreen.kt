package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FlashOperations
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.launch

@Composable
fun BypassUblScreen(
    executorProvider: ExecutorProvider,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bypass UBL — Redmi 4A (rolex)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "This flow is specific to the Redmi 4A (\"rolex\") using the bundled MIUI " +
                            "10.2.3.0 firehose loader and partition map. Running it on any other model " +
                            "or ROM version can brick the device. Only proceed if this is the exact " +
                            "device you intend to service.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Text("Put the Redmi 4A into EDL (9008) mode and connect via USB OTG before starting.")

            Button(
                enabled = !running,
                onClick = {
                    scope.launch {
                        running = true
                        output = listOf()
                        val executor = executorProvider.detect()
                        val ops = FlashOperations(context, executor, logRepository)
                        ops.runBypassUblRedmi4A().collect { line -> output = output + line }
                        running = false
                    }
                }
            ) { Text(if (running) "Running..." else "Start Bypass UBL") }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(output) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
