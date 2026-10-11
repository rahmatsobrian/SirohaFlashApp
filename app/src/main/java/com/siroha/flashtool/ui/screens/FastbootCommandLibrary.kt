package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One entry in the in-app command handbook. [risky] entries are tinted as a warning. */
internal data class LibraryCommand(
    val command: String,
    val description: String,
    val risky: Boolean = false
)

internal data class LibraryCategory(val title: String, val commands: List<LibraryCommand>)

/**
 * Wire-level fastboot commands this app can actually send over USB, written
 * in the same "type only what comes after `fastboot`" syntax the manual
 * command field accepts. Tapping an entry only FILLS the field — nothing is
 * sent until the person presses Send.
 *
 * Deliberately left out: host-side features of the PC tool that never go
 * over the wire (flashall, update, format, sparse splitting, fetch).
 * Not every device supports every command; unsupported ones just FAIL.
 */
internal val fastbootCommandLibrary = listOf(
    LibraryCategory(
        "Info & variables", listOf(
            LibraryCommand("devices", "List fastboot devices seen by this phone (handled locally)"),
            LibraryCommand("getvar all", "Every variable the bootloader reports"),
            LibraryCommand("getvar product", "Product / codename"),
            LibraryCommand("getvar variant", "Hardware variant"),
            LibraryCommand("getvar serialno", "Serial number — don't share it"),
            LibraryCommand("getvar version-bootloader", "Bootloader version"),
            LibraryCommand("getvar version-baseband", "Baseband / modem version"),
            LibraryCommand("getvar unlocked", "Bootloader unlock state"),
            LibraryCommand("getvar secure", "Secure-boot state"),
            LibraryCommand("getvar is-userspace", "yes = FastbootD, no = bootloader fastboot"),
            LibraryCommand("getvar max-download-size", "Largest single image the device accepts"),
            LibraryCommand("getvar partition-size:boot", "Size of the boot partition"),
            LibraryCommand("getvar partition-type:boot", "Filesystem/type of the boot partition"),
            LibraryCommand("getvar has-slot:boot", "Does boot have A/B slots?"),
            LibraryCommand("getvar is-logical:system", "Is system a logical (dynamic) partition?"),
            LibraryCommand("getvar super-partition-name", "Name of the super partition"),
            LibraryCommand("getvar snapshot-update-status", "Virtual A/B update state")
        )
    ),
    LibraryCategory(
        "Boot & reboot", listOf(
            LibraryCommand("reboot", "Reboot to system"),
            LibraryCommand("reboot bootloader", "Reboot back into the bootloader"),
            LibraryCommand("reboot recovery", "Reboot to recovery"),
            LibraryCommand("reboot fastboot", "Reboot into FastbootD"),
            LibraryCommand("continue", "Continue the normal boot"),
            LibraryCommand("powerdown", "Power off (not every bootloader supports it)")
        )
    ),
    LibraryCategory(
        "A/B slots", listOf(
            LibraryCommand("getvar current-slot", "Active slot"),
            LibraryCommand("getvar slot-count", "Number of slots"),
            LibraryCommand("getvar slot-suffixes", "Slot suffixes, e.g. a,b"),
            LibraryCommand("set_active a", "Make slot A active", risky = true),
            LibraryCommand("set_active b", "Make slot B active", risky = true)
        )
    ),
    LibraryCategory(
        "Erase", listOf(
            LibraryCommand("erase cache", "Erase the cache partition"),
            LibraryCommand("erase metadata", "Erase metadata (data becomes unreadable)", risky = true),
            LibraryCommand("erase userdata", "Erase ALL user data", risky = true)
        )
    ),
    LibraryCategory(
        "Unlock & lock", listOf(
            LibraryCommand("flashing get_unlock_ability", "Is unlocking allowed (OEM unlocking toggle)?"),
            LibraryCommand("flashing unlock", "Unlock bootloader — wipes data", risky = true),
            LibraryCommand("flashing lock", "Lock bootloader — wrong ROM can brick", risky = true),
            LibraryCommand("flashing unlock_critical", "Unlock critical partitions", risky = true),
            LibraryCommand("flashing lock_critical", "Lock critical partitions", risky = true),
            LibraryCommand("oem device-info", "Vendor device info (unlock state etc.)"),
            LibraryCommand("oem help", "List vendor commands, if implemented")
        )
    ),
    LibraryCategory(
        "Dynamic partitions (FastbootD)", listOf(
            LibraryCommand("create-logical-partition NAME SIZE_BYTES", "Create a logical partition"),
            LibraryCommand("resize-logical-partition NAME SIZE_BYTES", "Resize a logical partition", risky = true),
            LibraryCommand("delete-logical-partition NAME", "Delete a logical partition", risky = true),
            LibraryCommand("snapshot-update merge", "Finish a pending virtual A/B merge"),
            LibraryCommand("snapshot-update cancel", "Cancel a pending update — can break boot", risky = true),
            LibraryCommand("gsi status", "GSI install status"),
            LibraryCommand("gsi disable", "Disable the installed GSI"),
            LibraryCommand("gsi wipe", "Remove the installed GSI", risky = true)
        )
    )
)

/**
 * Collapsible handbook. Only one category is open at a time to keep the
 * screen short on a phone. [onPick] receives the command text to place in
 * the manual command field.
 */
@Composable
internal fun FastbootCommandLibrary(onPick: (String) -> Unit) {
    var openCategory by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Tap a command to put it in the manual command field above. Nothing is sent until you press Send. " +
                "Not every device supports every command.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        fastbootCommandLibrary.forEach { category ->
            val open = openCategory == category.title
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column {
                    Surface(
                        onClick = { openCategory = if (open) null else category.title },
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                category.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${category.commands.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Icon(
                                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (open) {
                        category.commands.forEach { cmd ->
                            Surface(
                                onClick = { onPick(cmd.command) },
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        cmd.command,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (cmd.risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        cmd.description,
                                        style = MaterialTheme.typography.bodySmall,
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
}
