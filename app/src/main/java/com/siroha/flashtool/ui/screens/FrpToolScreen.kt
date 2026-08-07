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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrpToolScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ops = remember { FastbootOperations(context, logRepository) }
    var busy by remember { mutableStateOf(false) }
    val entries by logRepository.entries.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FRP Remove Tool") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } }
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
                        "Only use this on a device you own or are explicitly authorized to service. " +
                            "Removing Factory Reset Protection on a device that isn't yours may be illegal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Text(
                "SPRD FRP reset (fastboot: erase persist) — the one FRP method from flash.sh that " +
                    "works over the fastboot protocol implemented in this app.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(enabled = !busy, onClick = { scope.launch { busy = true; ops.connect(); busy = false } }) {
                Text("Connect fastboot device")
            }
            Button(enabled = !busy, onClick = { scope.launch { busy = true; ops.erase("persist"); busy = false } }) {
                Text("Erase persist (SPRD FRP reset)")
            }

            Text(
                "Samsung FRP (via ADB intents) and SPRD/MTK FRP (via ADB content-provider write) from " +
                    "flash.sh both target the ADB shell of the connected device, not this app's own " +
                    "shell — they need a real ADB-over-USB client (auth handshake included), which " +
                    "isn't implemented here yet. Fastboot's protocol (implemented) and ADB's protocol " +
                    "(not implemented) are two separate things, not a matter of extra buttons.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
