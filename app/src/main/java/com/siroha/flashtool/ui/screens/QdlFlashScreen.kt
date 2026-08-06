package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
fun QdlFlashScreen(
    executorProvider: ExecutorProvider,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaderUri by remember { mutableStateOf<Uri?>(null) }
    var rawprogramUri by remember { mutableStateOf<Uri?>(null) }
    var patchUri by remember { mutableStateOf<Uri?>(null) }
    var output by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    val loaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { loaderUri = it }
    val rawprogramPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { rawprogramUri = it }
    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { patchUri = it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QDL Flash (EDL 9008)") },
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
            Text(
                "Put the target device into EDL (9008) mode, connect via USB OTG, then pick the " +
                    "firehose loader and rawprogram/patch XML files for its ROM.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(onClick = { loaderPicker.launch(arrayOf("*/*")) }) {
                Text(loaderUri?.lastPathSegment ?: "Pick firehose loader (.mbn/.elf)")
            }
            OutlinedButton(onClick = { rawprogramPicker.launch(arrayOf("text/xml", "*/*")) }) {
                Text(rawprogramUri?.lastPathSegment ?: "Pick rawprogram*.xml")
            }
            OutlinedButton(onClick = { patchPicker.launch(arrayOf("text/xml", "*/*")) }) {
                Text(patchUri?.lastPathSegment ?: "Pick patch*.xml")
            }

            Button(
                enabled = !running && loaderUri != null && rawprogramUri != null && patchUri != null,
                onClick = {
                    scope.launch {
                        running = true
                        output = listOf()
                        val executor = executorProvider.detect()
                        val ops = FlashOperations(context, executor, logRepository)

                        // SAF Uris must be resolved to real filesystem paths (or copied
                        // locally) before a root/Shizuku shell process can read them.
                        val loaderPath = com.siroha.flashtool.core.SafFiles.copyToCache(context, loaderUri!!, "loader")
                        val rawprogramPath = com.siroha.flashtool.core.SafFiles.copyToCache(context, rawprogramUri!!, "rawprogram.xml")
                        val patchPath = com.siroha.flashtool.core.SafFiles.copyToCache(context, patchUri!!, "patch.xml")

                        ops.runQdl(loaderPath, listOf(rawprogramPath), listOf(patchPath)).collect { line ->
                            output = output + line
                        }
                        running = false
                    }
                }
            ) { Text(if (running) "Flashing..." else "Start QDL Flash") }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(output) { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
