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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
    import androidx.documentfile.provider.DocumentFile
    import android.content.Intent
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.FlashOperations
import com.siroha.flashtool.core.RawProgramPartition
import com.siroha.flashtool.core.RawProgramXml
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun QdlFlashScreen(
    executorProvider: ExecutorProvider,
    fastbootOperations: com.siroha.flashtool.core.FastbootOperations,
    logRepository: LogRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var loaderUri by remember { mutableStateOf<Uri?>(null) }
    var rawprogramUri by remember { mutableStateOf<Uri?>(null) }
    var patchUri by remember { mutableStateOf<Uri?>(null) }
    var rawprogramLocalPath by remember { mutableStateOf<String?>(null) }
    var partitions by remember { mutableStateOf<List<RawProgramPartition>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var storage by remember { mutableStateOf("emmc") } // matches flash.sh menu 1 (emmc) / menu 2 (ufs)
    var dryRun by remember { mutableStateOf(false) }
    var allowMissing by remember { mutableStateOf(false) }
    var finalizeProvisioning by remember { mutableStateOf(false) }
    var debugLog by remember { mutableStateOf(false) }
    // HAPUS BARIS INI: var output by remember { mutableStateOf(listOf<String>()) }
    // GANTI MENJADI:
    val output = remember { androidx.compose.runtime.mutableStateListOf<String>() }
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

    // --- TEMPEL DI SINI ---
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val documentTree = DocumentFile.fromTreeUri(context, uri)
            if (documentTree != null) {
                val foundLoader = documentTree.listFiles().find {
                    val name = it.name?.lowercase() ?: ""
                    (name.endsWith(".mbn") || name.endsWith(".elf")) &&
                    (name.contains("prog") || name.contains("firehose"))
                }
                val foundRawprogram = documentTree.listFiles().find {
                    val name = it.name?.lowercase() ?: ""
                    name.startsWith("rawprogram") && name.endsWith(".xml")
                }
                val foundPatch = documentTree.listFiles().find {
                    val name = it.name?.lowercase() ?: ""
                    name.startsWith("patch") && name.endsWith(".xml")
                }

                foundLoader?.let { loaderUri = it.uri }
                foundPatch?.let { patchUri = it.uri }
                foundRawprogram?.let { raw ->
                    rawprogramUri = raw.uri
                    val path = SafFiles.copyToCache(context, raw.uri, "rawprogram.xml")
                    rawprogramLocalPath = path
                    val parsed = runCatching { RawProgramXml.parsePartitions(File(path)) }.getOrDefault(emptyList())
                    partitions = parsed
                    selected = parsed.map { it.label }.toSet()
                }
            }
        }
    }
    // -----------------------

    Scaffold(
        topBar = { SirohaTopBar("QDL Flash (EDL 9008)", icon = Icons.Filled.Bolt, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                com.siroha.flashtool.ui.components.DeviceStatusCard(
                    executorProvider,
                    fastbootOperations = fastbootOperations,
                    expectedMode = com.siroha.flashtool.ui.components.ExpectedUsbMode.EDL_ONLY
                )
            }

            item {
                Text(
                    "Put the target device into EDL (9008) mode, connect via USB OTG, then pick the " +
                        "firehose loader and rawprogram/patch XML files for its ROM.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = storage == "emmc", onClick = { storage = "emmc" }, label = { Text("eMMC") })
                    FilterChip(selected = storage == "ufs", onClick = { storage = "ufs" }, label = { Text("UFS") })
                }
            }

            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        // Ini rahasianya: otomatis memberi jarak kosong 18dp antar opsi
                        verticalArrangement = Arrangement.spacedBy(18.dp) 
                    ) {
                        // 1. Judul yang diperbaiki (Lebih besar & tegas)
                        Text(
                            text = "QDL FLASHING OPTIONS",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 2. Option: Dry run
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Dry run", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Simulate the flash without writing to the target — no EDL device required.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = dryRun, onCheckedChange = { dryRun = it })
                        }

                        // 3. Option: Allow missing files
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Allow missing files", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Skip program/patch entries whose file isn't found instead of failing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = allowMissing, onCheckedChange = { allowMissing = it })
                        }

                        // 4. Option: Finalize provisioning
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Finalize provisioning", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "⚠️ Irreversible on the target device — qdl warns before starting. Only enable if you know what this does.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Switch(checked = finalizeProvisioning, onCheckedChange = { finalizeProvisioning = it })
                        }
                        
                        // 5. Option: Debug output (Dengan Warning)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable debug log", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "⚠️ Saves huge raw log to app cache folder (qdl_debug.log). Will only show filtered logs to prevent crashes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    // Gunakan warna error (merah) agar user lebih waspada
                                    color = MaterialTheme.colorScheme.error 
                                )
                            }
                            Switch(checked = debugLog, onCheckedChange = { debugLog = it })
                        }
                    } // Tutup Column
                } // Tutup OutlinedCard
            } // Tutup item


            // --- TEMPEL TOMBOL DI SINI ---
            item {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    onClick = { folderPicker.launch(null) }
                ) {
                    Text("Auto-Load Firmware Folder")
                }
            }
            // -----------------------------

            item {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { loaderPicker.launch(arrayOf("*/*")) }) {
                    Text(loaderUri?.lastPathSegment ?: "Pick firehose loader (.mbn/.elf)")
                }
            }
            item {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { rawprogramPicker.launch(arrayOf("text/xml", "*/*")) }) {
                    Text(rawprogramUri?.lastPathSegment ?: "Pick rawprogram*.xml")
                }
            }
            item {
                FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { patchPicker.launch(arrayOf("text/xml", "*/*")) }) {
                    Text(patchUri?.lastPathSegment ?: "Pick patch*.xml")
                }
            }

            if (partitions.isNotEmpty()) {
                item {
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
                }
                // Flattened directly into the outer LazyColumn — a nested
                // LazyColumn here would be a scrollable-inside-a-scrollable
                // of the same orientation, which Compose can't size correctly.
                items(partitions) { partition ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
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

            item {
                Button(
                    enabled = !running && loaderUri != null && rawprogramLocalPath != null && patchUri != null &&
                        (partitions.isEmpty() || selected.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            running = true
// GANTI JADI INI:
output.clear()
                            val executor = executorProvider.detect()
                            val ops = FlashOperations(context, executor, logRepository)

                            // qdl on this app's arm64-v8a build prints the misleading
                            // "unable to load programmer" error when no 9008/EDL device
                            // is present at all — check first so that case gets a clear
                            // message instead of looking like a bad loader/XML file.
                            // Skipped for dry runs: the whole point of dry-run is testing
                            // the loader/XML set without a device connected.
                            if (!dryRun && ops.checkEdlDevice().isEmpty()) {
// GANTI JADI INI (Gunakan .add):
output.add("[error] No EDL (9008) device detected — connect the device in EDL mode before starting QDL flash.")
                                running = false
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("QDL Flash — no EDL device connected")
                                return@launch
                            }

                            val loaderPath = SafFiles.copyToCache(context, loaderUri!!, "loader")
                            val patchPath = SafFiles.copyToCache(context, patchUri!!, "patch.xml")

                            // --- TAMBAHKAN KODE INI AGAR QDL TAHU LOKASI FILE .IMG ASLINYA ---
                            val decodedUri = Uri.decode(rawprogramUri.toString())
                            val includeDir = if (decodedUri.contains("primary:")) {
                                // UBAH BARIS DI BAWAH INI:
                                val cleanPath = decodedUri.substringAfterLast("primary:").substringBeforeLast("/")
                                "/storage/emulated/0/$cleanPath"
                            } else null
                            // -----------------------------------------------------------------

                            ops.runQdl(
                                loaderPath = loaderPath,
                                rawprogramPaths = listOf(rawprogramLocalPath!!),
                                patchPaths = listOf(patchPath),
                                selectedLabels = if (partitions.isEmpty()) null else selected,
                                storage = storage,
                                includeFolder = includeDir, // <--- JANGAN LUPA MASUKKAN KE SINI
                                dryRun = dryRun,
                                allowMissing = allowMissing,
                                finalizeProvisioning = finalizeProvisioning,
                                debugLog = debugLog
// GANTI JADI INI:
).collect { line -> output.add(line) }
                            
                            running = false
                            val hadError = output.any { it.contains("[error]", ignoreCase = true) }
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                if (hadError) "QDL Flash — failed (see output below)" else "QDL Flash — finished (see output below)"
                            )
                        }
                    }
                ) { Text(if (running) "Flashing..." else if (dryRun) "Start Dry Run" else "Start QDL Flash") }
            }

            item { SectionHeading(Icons.Filled.Bolt, "Output") }
            items(output) { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
