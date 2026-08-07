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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.siroha.flashtool.core.RawProgramPartition
import com.siroha.flashtool.core.RawProgramXml
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var rawprogramLocalPath by remember { mutableStateOf<String?>(null) }
    var partitions by remember { mutableStateOf<List<RawProgramPartition>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var storage by remember { mutableStateOf("emmc") } // matches flash.sh menu 1 (emmc) / menu 2 (ufs)
    var output by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    val loaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { loaderUri = it }
    val rawprogramPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        rawprogramUri = uri
        if (uri != null) {
            val path = SafFiles.copyToCache(context, uri, "rawprogram.xml")
            rawprogramLocalPath = path
            val parsed = runCatching { RawProgramXml.parsePartitions(File(path)) }.getOrDefault(emptyList())
            partitions = parsed
            selected = parsed.map { it.label }.toSet() // default: everything selected, matches flash.sh's full flash
        }
    }
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = storage == "emmc", onClick = { storage = "emmc" }, label = { Text("eMMC") })
                FilterChip(selected = storage == "ufs", onClick = { storage = "ufs" }, label = { Text("UFS") })
            }

            OutlinedButton(onClick = { loaderPicker.launch(arrayOf("*/*")) }) {
                Text(loaderUri?.lastPathSegment ?: "Pick firehose loader (.mbn/.elf)")
            }
            OutlinedButton(onClick = { rawprogramPicker.launch(arrayOf("text/xml", "*/*")) }) {
                Text(rawprogramUri?.lastPathSegment ?: "Pick rawprogram*.xml")
            }
            OutlinedButton(onClick = { patchPicker.launch(arrayOf("text/xml", "*/*")) }) {
                Text(patchUri?.lastPathSegment ?: "Pick patch*.xml")
            }

            if (partitions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Partitions (${selected.size}/${partitions.size} selected)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row {
                        TextButton(onClick = { selected = partitions.map { it.label }.toSet() }) { Text("All") }
                        TextButton(onClick = { selected = emptySet() }) { Text("None") }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(partitions) { partition ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = partition.label in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + partition.label else selected - partition.label
                                }
                            )
                            Column {
                                Text(partition.label, style = MaterialTheme.typography.bodyLarge)
                                Text(partition.filename, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            Button(
                enabled = !running && loaderUri != null && rawprogramLocalPath != null && patchUri != null &&
                    (partitions.isEmpty() || selected.isNotEmpty()),
                onClick = {
                    scope.launch {
                        running = true
                        output = listOf()
                        val executor = executorProvider.detect()
                        val ops = FlashOperations(context, executor, logRepository)

                        val loaderPath = SafFiles.copyToCache(context, loaderUri!!, "loader")
                        val patchPath = SafFiles.copyToCache(context, patchUri!!, "patch.xml")

                        ops.runQdl(
                            loaderPath = loaderPath,
                            rawprogramPaths = listOf(rawprogramLocalPath!!),
                            patchPaths = listOf(patchPath),
                            selectedLabels = if (partitions.isEmpty()) null else selected,
                            storage = storage
                        ).collect { line -> output = output + line }
                        running = false
                    }
                }
            ) { Text(if (running) "Flashing..." else "Start QDL Flash") }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(output) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
