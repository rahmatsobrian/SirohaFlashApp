package com.siroha.flashtool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.ExecutorProvider
import com.siroha.flashtool.core.UsbDeviceHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val POLL_INTERVAL_MS = 2000L

private sealed class UsbStatus {
    object None : UsbStatus()
    data class Edl(val vendorId: Int, val productId: Int) : UsbStatus()
    object Fastboot : UsbStatus()
    object Adb : UsbStatus()
    object Unrecognized : UsbStatus()
}

/**
 * Always-visible, auto-refreshing status strip: which execution backend is
 * actually active right now (root/Shizuku/neither) and what's plugged in
 * over USB right now (EDL 9008 / fastboot / ADB / nothing) — including
 * whether a device sitting in EDL mode is currently detected, since that's
 * not otherwise visible anywhere until you go run a flash. Polls every 2s
 * while on screen; uses only non-prompting checks so it never pops a
 * root/Shizuku permission dialog on its own.
 */
@Composable
fun DeviceStatusCard(executorProvider: ExecutorProvider) {
    val context = LocalContext.current
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var shizukuReady by remember { mutableStateOf(false) }
    var usbStatus by remember { mutableStateOf<UsbStatus>(UsbStatus.None) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        val status = executorProvider.passiveStatus()
        rootGranted = status.rootGranted
        shizukuReady = status.shizukuReady

        val devices = UsbDeviceHelper.listDevices(context)
        usbStatus = when {
            devices.isEmpty() -> UsbStatus.None
            devices.any { UsbDeviceHelper.isEdlDevice(it) } ->
                devices.first { UsbDeviceHelper.isEdlDevice(it) }.let { UsbStatus.Edl(it.vendorId, it.productId) }
            devices.any { UsbDeviceHelper.isLikelyFastbootDevice(it) } -> UsbStatus.Fastboot
            devices.any { UsbDeviceHelper.isLikelyAdbDevice(it) } -> UsbStatus.Adb
            else -> UsbStatus.Unrecognized
        }
    }

    // Auto-poll while this card is part of the composition (e.g. while Home
    // is on screen) — cancels automatically when the user navigates away.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            tick++
        }
    }

    val executionLabel = when {
        rootGranted == true -> "Working with root"
        shizukuReady -> "Working with Shizuku"
        rootGranted == false && !shizukuReady -> "No root, no Shizuku — grant one in Settings"
        else -> "Checking execution backend..."
    }
    val executionColor = if (rootGranted == true || shizukuReady) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    val (usbLabel, usbColor) = when (val s = usbStatus) {
        is UsbStatus.None -> "No USB device connected" to MaterialTheme.colorScheme.onSurfaceVariant
        is UsbStatus.Edl -> "EDL (9008) mode detected — %04x:%04x".format(s.vendorId, s.productId) to MaterialTheme.colorScheme.primary
        is UsbStatus.Fastboot -> "Fastboot-mode device detected" to MaterialTheme.colorScheme.primary
        is UsbStatus.Adb -> "ADB-mode device detected" to MaterialTheme.colorScheme.primary
        is UsbStatus.Unrecognized -> "USB device connected (not EDL/fastboot/ADB)" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { tick++ }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh status", modifier = Modifier.size(18.dp))
                }
            }
            StatusRow(Icons.Filled.Security, executionLabel, executionColor)
            StatusRow(Icons.Filled.Bolt, usbLabel, usbColor)
        }
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
