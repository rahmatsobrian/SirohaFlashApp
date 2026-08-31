package com.siroha.flashtool.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.siroha.flashtool.ui.theme.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.FlashOperations
import com.siroha.flashtool.core.RawProgramPartition
import com.siroha.flashtool.core.RawProgramXml
import com.siroha.flashtool.core.SafFiles
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.groupRowShape
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private data class QdlOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val warning: Boolean,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

/** One flashing-option toggle, clustered-list styled like Settings' SwitchRow - a caution option (Finalize provisioning, debug log) gets a tertiary "warning" tint on its subtitle+icon instead of plain neutral text. */
@Composable
private fun QdlOptionRow(option: QdlOption, shape: Shape) {
    val accent = if (option.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                option.icon,
                contentDescription = null,
                tint = if (option.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(option.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = accent, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(checked = option.checked, onCheckedChange = option.onCheckedChange)
        }
    }
}

@Composable
fun QdlFlashScreen(
    fastbootOperations: FastbootOperations,
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
    // qdl no longer needs root/Shizuku - the no-root USB bridge is always
    // used now (see FlashOperations.runQdlNoRootBridge), so there's no
    // toggle for it anymore.
    val output = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    
    // --- TAMBAHAN UNTUK ANDROID 11+ (API 30 - 35) ---
    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true // Android 10 ke bawah cukup pakai READ_EXTERNAL_STORAGE
            }
        )
    }

    // Fungsi untuk melempar user ke Settings All Files Access
    val requestAllFilesAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback jika device OEM memodifikasi intent bawaan
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        }
    }

    // Auto-refresh state saat user kembali ke aplikasi dari Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasStoragePermission = Environment.isExternalStorageManager()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // --- AKHIR TAMBAHAN ---

    val loaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { loaderUri = it }
    val rawprogramPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        rawprogramUri = uri
        if (uri != null) {
            scope.launch {
                val path = SafFiles.copyToCache(context, uri, "rawprogram.xml")
                rawprogramLocalPath = path
                val parsed = runCatching { RawProgramXml.parsePartitions(File(path)) }.getOrDefault(emptyList())
                partitions = parsed
                selected = parsed.map { it.label }.toSet() // default: everything selected, matches flash.sh's full flash
            }
        }
    }
    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { patchUri = it }

    // Scans a picked folder for a firehose loader + rawprogram/patch XML by
    // filename convention, so the person doesn't have to pick all three
    // files one by one when a firmware dump already has them together.
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
                    scope.launch {
                        val path = SafFiles.copyToCache(context, raw.uri, "rawprogram.xml")
                        rawprogramLocalPath = path
                        val parsed = runCatching { RawProgramXml.parsePartitions(File(path)) }.getOrDefault(emptyList())
                        partitions = parsed
                        selected = parsed.map { it.label }.toSet()
                    }
                }
            }
        }
    }

    fun startFlash() {
        if (!hasStoragePermission) {
            requestAllFilesAccess()
            return
        }
        
        scope.launch {
            running = true
            output.clear()

            val ops = FlashOperations(context, logRepository)
            val loaderPath = SafFiles.copyToCache(context, loaderUri!!, "loader")
            val patchPath = SafFiles.copyToCache(context, patchUri!!, "patch.xml")
            val decodedUri = Uri.decode(rawprogramUri.toString())
            val includeDir = if (decodedUri.contains("primary:")) {
                val cleanPath = decodedUri.substringAfterLast("primary:").substringBeforeLast("/")
                "/storage/emulated/0/$cleanPath"
            } else null

            ops.runQdlNoRootBridge(
                loaderPath = loaderPath,
                rawprogramPaths = listOf(rawprogramLocalPath!!),
                patchPaths = listOf(patchPath),
                selectedLabels = if (partitions.isEmpty()) null else selected,
                storage = storage,
                includeFolder = includeDir,
                allowMissing = allowMissing,
                dryRun = dryRun,
                finalizeProvisioning = finalizeProvisioning,
                debugLog = debugLog
            ).collect { line -> output.add(line) }

            running = false
            val hadError = output.any { it.contains("[error]", ignoreCase = true) }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                if (hadError) "QDL Flash - failed (see output below)" else "QDL Flash - finished (see output below)"
            )
        }
    }

    val filesReady = loaderUri != null && rawprogramLocalPath != null && patchUri != null &&
        (partitions.isEmpty() || selected.isNotEmpty())

    Scaffold(
        topBar = { SirohaTopBar("QDL Flash (EDL 9008)", icon = Icons.Filled.Bolt, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Put the target device into EDL (9008) mode, connect via USB OTG, then pick the " +
                        "firehose loader and rawprogram/patch XML files for its ROM.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // --- TAMBAHKAN BANNER INI ---
            if (!hasStoragePermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = "Warning")
                                Text("Storage Permission Required", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Android 11+ requires 'All Files Access' to read large firmware image files directly during flashing. Without this, the flash will fail.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            com.siroha.flashtool.ui.theme.FilledTonalButton(
                                onClick = { requestAllFilesAccess() },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Grant Permission")
                            }
                        }
                    }
                }
            }
            // --- AKHIR BANNER ---
            
            item {
                val segmentHaptic = com.siroha.flashtool.ui.theme.LocalHapticTrigger.current
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        selected = storage == "emmc",
                        onClick = { segmentHaptic(); storage = "emmc" },
                        label = { Text("eMMC") }
                    )
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        selected = storage == "ufs",
                        onClick = { segmentHaptic(); storage = "ufs" },
                        label = { Text("UFS") }
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(Icons.Filled.Bolt, "QDL flashing options", modifier = Modifier.padding(start = 4.dp))
                    val options = listOf(
                        QdlOption(
                            title = "Dry run",
                            subtitle = "Simulate the flash without writing to the target - no EDL device required.",
                            icon = Icons.Filled.Info,
                            warning = false,
                            checked = dryRun,
                            onCheckedChange = { dryRun = it }
                        ),
                        QdlOption(
                            title = "Allow missing files",
                            subtitle = "Skip program/patch entries whose file isn't found instead of failing.",
                            icon = Icons.Filled.HelpOutline,
                            warning = false,
                            checked = allowMissing,
                            onCheckedChange = { allowMissing = it }
                        ),
                        QdlOption(
                            title = "Finalize provisioning",
                            subtitle = "Irreversible on the target device - qdl warns before starting. Only enable if you know what this does.",
                            icon = Icons.Filled.Warning,
                            warning = true,
                            checked = finalizeProvisioning,
                            onCheckedChange = { finalizeProvisioning = it }
                        ),
                        QdlOption(
                            title = "Enable debug log",
                            subtitle = "Saves a huge raw log to the app's cache folder (qdl_debug.log). Only filtered logs are shown here to prevent crashes.",
                            icon = Icons.Filled.Article,
                            warning = true,
                            checked = debugLog,
                            onCheckedChange = { debugLog = it }
                        )
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        options.forEachIndexed { index, option ->
                            QdlOptionRow(option, groupRowShape(index, options.size))
                        }
                    }
                }
            }

            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Auto-Load Firmware Folder",
                            subtitle = "Scans a folder for the loader + rawprogram/patch XML by filename",
                            icon = Icons.Filled.Folder,
                            onClick = { folderPicker.launch(null) }
                        ),
                        ActionEntry(
                            title = "Pick firehose loader (.mbn/.elf)",
                            subtitle = loaderUri?.lastPathSegment ?: "Not selected yet",
                            icon = Icons.Filled.Bolt,
                            onClick = { loaderPicker.launch(arrayOf("*/*")) }
                        ),
                        ActionEntry(
                            title = "Pick rawprogram*.xml",
                            subtitle = rawprogramUri?.lastPathSegment ?: "Not selected yet",
                            icon = Icons.Filled.Article,
                            onClick = { rawprogramPicker.launch(arrayOf("text/xml", "*/*")) }
                        ),
                        ActionEntry(
                            title = "Pick patch*.xml",
                            subtitle = patchUri?.lastPathSegment ?: "Not selected yet",
                            icon = Icons.Filled.Article,
                            onClick = { patchPicker.launch(arrayOf("text/xml", "*/*")) }
                        )
                    )
                )
            }

            if (partitions.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Partitions (${selected.size}/${partitions.size} selected)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.siroha.flashtool.ui.theme.FilledTonalButton(
                                onClick = { selected = partitions.map { it.label }.toSet() },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                                Text("All")
                            }
                            com.siroha.flashtool.ui.theme.OutlinedButton(
                                onClick = { selected = emptySet() },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                                Text("None")
                            }
                        }
                    }
                }    

                
                // Dibungkus ke dalam satu item agar bisa dikontrol jaraknya
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        val haptic = com.siroha.flashtool.ui.theme.LocalHapticTrigger.current
                        partitions.forEachIndexed { index, partition ->
                            val checked = partition.label in selected
                            Surface(shape = groupRowShape(index, partitions.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = checked,
                                            role = Role.Checkbox,
                                            onValueChange = { isChecked ->
                                                haptic()
                                                selected = if (isChecked) selected + partition.label else selected - partition.label
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Column {
                                        Text(partition.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(partition.filename, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }


            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (running) "Running..." else if (dryRun) "Start Dry Run" else "Start QDL Flash",
                            subtitle = when {
                                running -> "Please wait, this can take a while"
                                !filesReady -> "Pick the loader, rawprogram, and patch files first"
                                dryRun -> "Simulates the flash - no EDL device needed"
                                else -> "Flashes via the no-root USB bridge - no root/Shizuku needed"
                            },
                            icon = Icons.Filled.PlayArrow,
                            enabled = !running && filesReady,
                            onClick = { startFlash() }
                        )
                    )
                )
            }

            item { SectionHeading(Icons.Filled.Article, "Output", modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
            item {
                if (output.isEmpty()) {
                    Text(
                        "Output will appear here once you start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Tambahkan Modifier.fillMaxWidth() di sini
                    Card(
                        modifier = Modifier.fillMaxWidth(), 
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp), // Tambahkan juga di Column agar aman
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
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
