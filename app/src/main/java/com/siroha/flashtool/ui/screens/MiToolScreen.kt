package com.siroha.flashtool.ui.screens

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup
import com.siroha.flashtool.ui.components.DismissibleSnackbarHost
import com.siroha.flashtool.ui.components.GroupRowSpacing
import com.siroha.flashtool.ui.components.groupRowShape
import com.siroha.flashtool.ui.theme.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.MiToolOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import com.siroha.flashtool.ui.components.launchWithFeedback
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.launch

@Composable
fun MiToolScreen(fastbootOperations: FastbootOperations, logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val mitool = remember { MiToolOperations(context, logRepository) }
    val fastboot = fastbootOperations
    var busy by remember { mutableStateOf(false) }
    var romTreeUri by remember { mutableStateOf<Uri?>(null) }
    var scripts by remember { mutableStateOf(listOf<DocumentFile>()) }
    var selectedScript by remember { mutableStateOf<DocumentFile?>(null) }
    var plan by remember { mutableStateOf<MiToolOperations.FlashPlan?>(null) }
    var planImages by remember { mutableStateOf(listOf<MiToolOperations.RomImage>()) }
    // True while a script is being read + its images/ folder resolved (both
    // do SAF/DocumentFile I/O - see MiToolOperations doc). Used to disable
    // the radio row so rapid taps between flash_all*.sh variants don't pile
    // up overlapping parses, which was the actual source of the
    // lag/stutter when switching scripts: parsing used to run unguarded,
    // synchronously, straight from the click handler.
    var parsingScript by remember { mutableStateOf(false) }
    // Tracks the in-flight parse job so a newer selection can cancel a
    // still-running older one instead of letting both race to update
    // `plan`/`planImages` (whichever finished last used to "win",
    // regardless of which script the person actually tapped last).
    var parseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var autoReboot by remember { mutableStateOf(true) }
    var romUrl by remember { mutableStateOf("") }
    var entryName by remember { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    fun selectScript(uri: Uri, script: DocumentFile) {
        selectedScript = script
        plan = null
        planImages = emptyList()
        parseJob?.cancel()
        parseJob = scope.launch {
            parsingScript = true
            val parsed = mitool.parseFlashScript(script)
            plan = parsed
            planImages = if (parsed != null) mitool.resolvePlanImages(uri, parsed) else emptyList()
            parsingScript = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        romTreeUri = uri
        plan = null
        planImages = emptyList()
        selectedScript = null
        parseJob?.cancel()
        scope.launch {
            parsingScript = true
            val found = mitool.findFlashScripts(uri)
            scripts = found
            // Auto-select the plain flash_all.sh (no lock, keeps userdata) as the
            // safest default when it's present, matching what most users want.
            val default = found.firstOrNull { it.name == "flash_all.sh" } ?: found.firstOrNull()
            if (default != null) {
                selectedScript = default
                val parsed = mitool.parseFlashScript(default)
                plan = parsed
                planImages = if (parsed != null) mitool.resolvePlanImages(uri, parsed) else emptyList()
            }
            parsingScript = false
        }
    }

    Scaffold(
        topBar = { SirohaTopBar("MiTool", icon = Icons.Filled.Extension, onBack = onBack) },
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionHeading(Icons.Filled.RocketLaunch, "Flash Fastboot ROM")
                Text(
                    "Pick the folder you extracted a Xiaomi fastboot ROM into (the one containing " +
                        "flash_all*.sh and images/). Choose which flash_all*.sh variant to run - the " +
                        "exact partition order and image files come straight from that script, not a " +
                        "hardcoded list, so this works for any device's ROM.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (scripts.isEmpty()) "Pick ROM folder" else "${scripts.size} flash_all*.sh found - pick a different folder",
                            subtitle = "Folder must contain flash_all*.sh and an images/ subfolder",
                            icon = Icons.Filled.FolderOpen,
                            onClick = { folderPicker.launch(null) }
                        )
                    )
                )
            }

            if (scripts.isNotEmpty()) {
                item {
                    SectionHeading(Icons.Filled.Description, if (parsingScript) "Flash script - reading…" else "Flash script")
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(GroupRowSpacing)) {
                        scripts.forEachIndexed { index, script ->
                            val isSelected = script.uri == selectedScript?.uri
                            Surface(
                                shape = groupRowShape(index, scripts.size),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        // Disabled while a parse is already in flight - rapid taps
                                        // between scripts used to each kick off their own blocking
                                        // parse on the UI thread; this (plus the suspend/IO move in
                                        // MiToolOperations) is what actually fixes the stutter.
                                        enabled = !parsingScript,
                                        onClick = { romTreeUri?.let { selectScript(it, script) } }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RadioButton(selected = isSelected, onClick = null, enabled = !parsingScript)
                                    Text(script.name ?: "flash_all.sh", style = MaterialTheme.typography.bodyMedium)
                                    if (isSelected && parsingScript) {
                                        Spacer(Modifier.width(8.dp))
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }

                val currentPlan = plan
                if (selectedScript != null && currentPlan == null && !parsingScript) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Text(
                                    "Couldn't parse this script - it doesn't look like a recognized flash_all.sh.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                if (currentPlan != null) {
                    val missing = planImages.filter { it.sizeBytes < 0 }
                    val totalMb = planImages.filter { it.sizeBytes > 0 }.sumOf { it.sizeBytes } / 1024 / 1024
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Plan summary", style = MaterialTheme.typography.labelLarge)
                                Text("• ${currentPlan.steps.size} partition(s) to flash (~$totalMb MB)", style = MaterialTheme.typography.bodyMedium)
                                if (currentPlan.eraseSteps.isNotEmpty()) {
                                    Text("• Erases: ${currentPlan.eraseSteps.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    if (currentPlan.locksBootloaderAfter) "• Locks the bootloader (oem lock) at the end" else "• Does NOT lock the bootloader",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (currentPlan.locksBootloaderAfter) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                currentPlan.expectedProduct?.let {
                                    Text("• Expects device product: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (missing.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                        Text(
                                            "${missing.size} image file(s) missing from images/:",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    missing.forEach {
                                        Text("• ${it.partitionName}", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 34.dp))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = null)
                            Text(
                                "Reboot automatically when flashing success",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(checked = autoReboot, onCheckedChange = { autoReboot = it })
                        }
                    }
                    item {
                        ActionListGroup(
                            listOf(
                                ActionEntry(
                                    title = "Connect fastboot device",
                                    subtitle = "Checks that a device in fastboot mode is reachable over USB",
                                    icon = Icons.Filled.Usb,
                                    enabled = !busy,
                                    onClick = { scope.launchWithFeedback(snackbarHostState, "Connect fastboot", { busy = it }) { fastboot.connect() } }
                                ),
                                ActionEntry(
                                    title = if (busy) "Flashing..." else "Run ${currentPlan.scriptName}",
                                    subtitle = if (missing.isEmpty()) "Flashes every partition in this script, in its original order" else "Fix the missing image file(s) above first",
                                    icon = Icons.Filled.FlashOn,
                                    enabled = !busy && missing.isEmpty(),
                                    onClick = {
                                        scope.launchWithFeedback(snackbarHostState, "Flash ${currentPlan.scriptName}", { busy = it }) {
                                            mitool.flashPlan(currentPlan, planImages, fastboot, autoReboot = autoReboot)
                                        }
                                    }
                                )
                            )
                        )
                    }
                }
            }

            item {
                SectionHeading(Icons.Filled.CloudDownload, "Firmware Content Extractor")
                Text(
                    "Downloads a ROM ZIP and pulls out one named file (e.g. boot.img). Unlike the " +
                        "original's range-request extractor, this downloads the full ZIP first - simpler, " +
                        "but slower for multi-gigabyte ROMs.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                OutlinedTextField(
                    value = romUrl,
                    onValueChange = { romUrl = it },
                    label = { Text("ROM ZIP URL") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = entryName,
                    onValueChange = { entryName = it },
                    label = { Text("File to extract") },
                    placeholder = { Text("boot.img") },
                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = if (busy) "Working..." else "Download & extract",
                            subtitle = if (romUrl.isBlank() || entryName.isBlank()) "Fill in the ROM ZIP URL and file to extract first" else "Downloads the ZIP, then pulls out $entryName",
                            icon = Icons.Filled.CloudDownload,
                            enabled = !busy && romUrl.isNotBlank() && entryName.isNotBlank(),
                            onClick = {
                                scope.launchWithFeedback(snackbarHostState, "Download & extract", { busy = it }) {
                                    mitool.downloadAndExtractFromZip(romUrl.trim(), entryName.trim()) != null
                                }
                            }
                        )
                    )
                )
            }

            item { SectionHeading(Icons.Filled.Info, "Recent activity") }
            item {
                val recent = entries.takeLast(20)
                if (recent.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "No activity yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            recent.forEach { e ->
                                Text(
                                    e.format(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
