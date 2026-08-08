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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.MiToolOperations
import com.siroha.flashtool.data.LogRepository
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch

@Composable
fun MiToolScreen(logRepository: LogRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mitool = remember { MiToolOperations(context, logRepository) }
    val fastboot = remember { FastbootOperations(context, logRepository) }
    var busy by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(listOf<MiToolOperations.RomImage>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var lockAfter by remember { mutableStateOf(false) }
    var romUrl by remember { mutableStateOf("") }
    var entryName by remember { mutableStateOf("") }
    val entries by logRepository.entries.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val found = mitool.scanFastbootRom(uri)
        images = found
        selected = found.map { it.partitionName }.toSet()
    }

    Scaffold(
        topBar = { SirohaTopBar("MiTool", icon = Icons.Filled.Extension, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeading(Icons.Filled.Info, "Two of four MiTool tools", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "\"Unlock Bootloader\" and \"Mi Assistant\" both need Xiaomi's private, " +
                            "account-based servers (and an undocumented external binary the original " +
                            "project never open-sourced either) — not implementable here. What's below " +
                            "are the two tools that don't depend on Xiaomi's private API.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SectionHeading(Icons.Filled.RocketLaunch, "Flash Fastboot ROM")
            Text(
                "Pick the folder you extracted a Xiaomi fastboot ROM into (the one containing " +
                    "images/). Every *.img found gets matched to a same-named partition — exactly " +
                    "how Xiaomi's own flash_all.sh scripts work.",
                style = MaterialTheme.typography.bodyMedium
            )
            FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { folderPicker.launch(null) }) {
                Text(if (images.isEmpty()) "Pick ROM folder" else "${images.size} image(s) found — pick a different folder")
            }

            if (images.isNotEmpty()) {
                Card {
                    Column(modifier = Modifier.padding(8.dp)) {
                        images.forEach { image ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = image.partitionName in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + image.partitionName else selected - image.partitionName
                                    }
                                )
                                Text("${image.partitionName}  (${image.sizeBytes / 1024 / 1024} MB)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text("  Lock bootloader after flashing", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = lockAfter, onCheckedChange = { lockAfter = it })
                }
                FilledTonalButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { busy = true; fastboot.connect(); busy = false } }
                ) { Icon(Icons.Filled.Usb, contentDescription = null); Text("  Connect fastboot device") }
                FilledTonalButton(
                    enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true
                            mitool.flashFastbootRom(images.filter { it.partitionName in selected }, lockAfter, fastboot)
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Flashing..." else "Flash selected images") }
            }

            SectionHeading(Icons.Filled.CloudDownload, "Firmware Content Extractor")
            Text(
                "Downloads a ROM ZIP and pulls out one named file (e.g. boot.img). Unlike the " +
                    "original's range-request extractor, this downloads the full ZIP first — simpler, " +
                    "but slower for multi-gigabyte ROMs.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = romUrl,
                onValueChange = { romUrl = it },
                label = { Text("ROM ZIP URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = entryName,
                onValueChange = { entryName = it },
                label = { Text("File to extract") },
                placeholder = { Text("boot.img") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                enabled = !busy && romUrl.isNotBlank() && entryName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        mitool.downloadAndExtractFromZip(romUrl.trim(), entryName.trim())
                        busy = false
                    }
                }
            ) { Text(if (busy) "Working..." else "Download & extract") }

            SectionHeading(Icons.Filled.Info, "Recent activity")
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.takeLast(20)) { e -> Text(e.format(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
