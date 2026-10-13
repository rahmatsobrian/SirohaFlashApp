package com.siroha.flashtool.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.UsbDeviceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** What the attached phone currently looks like on USB. */
enum class UsbMode { NONE, EDL, FASTBOOT, ADB, OTHER }

data class UsbModeInfo(val mode: UsbMode, val detail: String? = null)

private fun readUsbMode(context: Context, adbState: String?): UsbModeInfo {
    val devices = UsbDeviceHelper.listDevices(context)
    devices.firstOrNull { UsbDeviceHelper.isEdlDevice(it) }?.let { return UsbModeInfo(UsbMode.EDL, "Qualcomm 9008") }
    devices.firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }?.let { return UsbModeInfo(UsbMode.FASTBOOT) }
    devices.firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }?.let { return UsbModeInfo(UsbMode.ADB, adbState) }
    devices.firstOrNull()?.let { return UsbModeInfo(UsbMode.OTHER, "%04x:%04x".format(it.vendorId, it.productId)) }
    return UsbModeInfo(UsbMode.NONE)
}

/**
 * Live view of the attached device's mode, refreshed every second, so a
 * screen can show "EDL / Fastboot / ADB (recovery) / nothing" without the
 * person leaving and re-entering it. [adbState] is optional extra detail for
 * ADB (the banner state of an open connection, e.g. "recovery").
 */
@Composable
fun rememberUsbModeInfo(adbState: () -> String? = { null }): State<UsbModeInfo> {
    val context = LocalContext.current
    val currentAdbState = rememberUpdatedState(adbState)
    return produceState(initialValue = UsbModeInfo(UsbMode.NONE)) {
        while (isActive) {
            val info = withContext(Dispatchers.Default) { readUsbMode(context, currentAdbState.value()) }
            if (info != value) value = info
            delay(1000)
        }
    }
}

private fun title(info: UsbModeInfo): String = when (info.mode) {
    UsbMode.EDL -> "EDL (9008) mode"
    UsbMode.FASTBOOT -> "Fastboot mode"
    UsbMode.ADB -> "ADB" + (info.detail?.let { " - $it" } ?: "")
    UsbMode.OTHER -> "Unknown USB device"
    UsbMode.NONE -> "No device connected"
}

private fun modeName(mode: UsbMode): String = when (mode) {
    UsbMode.EDL -> "EDL (9008)"
    UsbMode.FASTBOOT -> "fastboot"
    UsbMode.ADB -> "ADB"
    else -> "a supported"
}

/**
 * Device status in the same list style as the Fastboot screen. When the
 * screen needs a specific mode ([expected], e.g. EDL for QDL), a wrong or
 * missing mode says what to do instead of just "waiting".
 */
@Composable
fun UsbModeStatusCard(info: UsbModeInfo, expected: UsbMode? = null) {
    val ok = expected == null || info.mode == expected
    val subtitle = when {
        info.mode == UsbMode.NONE -> if (expected != null) "Connect the device in ${modeName(expected)} mode over USB OTG" else "Connect a device over USB OTG"
        !ok -> "Detected ${title(info)} - this screen needs ${modeName(expected!!)} mode"
        info.mode == UsbMode.EDL -> "Ready - Qualcomm download mode"
        info.mode == UsbMode.OTHER -> info.detail
        else -> null
    }
    ActionListGroup(
        listOf(
            ActionEntry(
                title = title(info),
                subtitle = subtitle,
                icon = when {
                    info.mode == UsbMode.NONE -> Icons.Filled.Usb
                    ok -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.Warning
                },
                onClick = {}
            )
        )
    )
}

/** Compact one-line status (dot + mode) for tight places such as the terminal. */
@Composable
fun UsbModeChip(info: UsbModeInfo, modifier: Modifier = Modifier) {
    val dot: Color = when (info.mode) {
        UsbMode.NONE -> MaterialTheme.colorScheme.outline
        UsbMode.OTHER -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(title(info), style = MaterialTheme.typography.labelLarge)
    }
}
