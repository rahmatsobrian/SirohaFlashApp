package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.ui.theme.Button
import com.siroha.flashtool.ui.theme.TextButton

/** Partition names are limited to what real bootloaders use: letters, digits, '_' and '-'. */
private val PartitionNameRegex = Regex("^[A-Za-z0-9_-]+$")

/**
 * "Are you sure?" popup used before every command that can wipe data,
 * relock the bootloader or otherwise leave the phone unbootable.
 */
@Composable
internal fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Asks for a partition name and, when [showSize] is set, a size in bytes.
 * Used by "Flash custom partition", "Erase custom partition" and the
 * logical-partition create/resize/delete actions. The confirm button stays
 * disabled until the input is valid, so callers never get a malformed name.
 */
@Composable
internal fun PartitionInputDialog(
    title: String,
    description: String,
    confirmLabel: String,
    showSize: Boolean,
    onConfirm: (name: String, sizeBytes: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf("") }
    val nameOk = PartitionNameRegex.matches(name.trim())
    val size = sizeText.trim().toLongOrNull()
    val sizeOk = !showSize || (size != null && size > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("e.g. boot_a, dtbo, system") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameOk,
                    modifier = Modifier.fillMaxWidth()
                )
                if (showSize) {
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { sizeText = it.filter(Char::isDigit) },
                        placeholder = { Text("Size in bytes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = sizeText.isNotEmpty() && !sizeOk,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), if (showSize) size else null) },
                enabled = nameOk && sizeOk,
                shape = RoundedCornerShape(50)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Asks which slot a flash should go to. Offered for partitions that have
 * A/B slots: the plain name, or an explicit _a / _b target. [onChoose]
 * receives the suffix to append ("" , "_a" or "_b").
 */
@Composable
internal fun SlotChoiceDialog(
    partition: String,
    onChoose: (suffix: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flash $partition to...") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick the exact target. Use the plain name on non-A/B devices.",
                    style = MaterialTheme.typography.bodyMedium
                )
                listOf("" to partition, "_a" to "${partition}_a", "_b" to "${partition}_b").forEach { (suffix, label) ->
                    Button(
                        onClick = { onChoose(suffix) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
