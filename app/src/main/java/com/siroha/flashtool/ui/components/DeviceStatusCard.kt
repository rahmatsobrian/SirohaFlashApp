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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.ui.theme.HapticIconButton
import com.siroha.flashtool.ui.theme.sirohaCardShadow
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

/** What kind of USB mode the screen showing this card actually cares about — changes the hint text when the wrong mode is detected instead. */
enum class ExpectedUsbMode { ANY, EDL_ONLY }

/**
 * Always-visible, auto-refreshing status strip: what's plugged in over USB
 * right now (EDL 9008 / fastboot / ADB / nothing) — including whether a
 * device sitting in EDL mode is currently detected, since that's not
 * otherwise visible anywhere until you go run a flash. Polls every 2s while
 * on screen. This app never needs root or Shizuku for anything, so there's
 * no privileged-execution status to show here.
 */
@Composable
fun DeviceStatusCard(
    fastbootOperations: FastbootOperations? = null,
    adbOperations: AdbOperations? = null,
    expectedMode: ExpectedUsbMode = ExpectedUsbMode.ANY
) {
    val context = LocalContext.current
    var usbStatus by remember { mutableStateOf<UsbStatus>(UsbStatus.None) }
    var tick by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        isRefreshing = true
        val devices = UsbDeviceHelper.listDevices(context)
        usbStatus = when {
            devices.isEmpty() -> UsbStatus.None
            devices.any { UsbDeviceHelper.isEdlDevice(it) } ->
                devices.first { UsbDeviceHelper.isEdlDevice(it) }.let { UsbStatus.Edl(it.vendorId, it.productId) }
            devices.any { UsbDeviceHelper.isLikelyFastbootDevice(it) } -> UsbStatus.Fastboot
            devices.any { UsbDeviceHelper.isLikelyAdbDevice(it) } -> UsbStatus.Adb
            else -> UsbStatus.Unrecognized
        }
        isRefreshing = false
    }

    // Auto-connect as soon as a fastboot/ADB device is detected here, so a
    // device that's already "connected" per Home doesn't then need a
    // separate manual Connect tap on every other tool screen too — they all
    // share the same FastbootOperations/AdbOperations instance, so
    // connecting once from wherever it's first detected is enough. Each
    // category is only auto-attempted once per detection (resets if the
    // device disappears) so a permission denial doesn't get retried in a
    // tight loop every poll.
    var fastbootAutoConnectTried by remember { mutableStateOf(false) }
    var adbAutoConnectTried by remember { mutableStateOf(false) }
    LaunchedEffect(usbStatus, fastbootOperations, adbOperations) {
        when (usbStatus) {
            is UsbStatus.Fastboot -> {
                if (fastbootOperations != null && !fastbootOperations.isConnected() && !fastbootAutoConnectTried) {
                    fastbootAutoConnectTried = true
                    fastbootOperations.connect()
                    tick++ // refresh the displayed status right away instead of waiting for the next poll
                }
            }
            is UsbStatus.Adb -> {
                if (adbOperations != null && !adbOperations.isConnected() && !adbAutoConnectTried) {
                    adbAutoConnectTried = true
                    adbOperations.connect()
                    tick++
                }
            }
            else -> {
                fastbootAutoConnectTried = false
                adbAutoConnectTried = false
            }
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

    val (usbLabel, usbColor) = when (val s = usbStatus) {
        is UsbStatus.None -> "No USB device connected" to MaterialTheme.colorScheme.onSurfaceVariant
        is UsbStatus.Edl -> "EDL (9008) mode detected — %04x:%04x".format(s.vendorId, s.productId) to MaterialTheme.colorScheme.primary
        is UsbStatus.Fastboot ->
            when {
                expectedMode == ExpectedUsbMode.EDL_ONLY ->
                    "Fastboot-mode device connected, but this screen needs EDL (9008) mode — reboot the target into EDL" to MaterialTheme.colorScheme.error
                fastbootOperations?.isConnected() == true ->
                    "Fastboot connected — ready in every menu" to MaterialTheme.colorScheme.primary
                else ->
                    "Fastboot-mode device detected — not connected yet" to MaterialTheme.colorScheme.primary
            }
        is UsbStatus.Adb ->
            when {
                expectedMode == ExpectedUsbMode.EDL_ONLY ->
                    "ADB-mode device connected, but this screen needs EDL (9008) mode — reboot the target into EDL" to MaterialTheme.colorScheme.error
                adbOperations?.isConnected() == true ->
                    "ADB connected — ready in every menu" to MaterialTheme.colorScheme.primary
                else ->
                    "ADB-mode device detected — not connected yet" to MaterialTheme.colorScheme.primary
            }
        is UsbStatus.Unrecognized -> "USB device connected (not EDL/fastboot/ADB)" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .sirohaCardShadow(
                if (com.siroha.flashtool.ui.theme.LocalShadowsEnabled.current) com.siroha.flashtool.ui.theme.LocalCardElevation.current else 0.dp
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isRefreshing) "Live status — refreshing..." else "Live status",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                HapticIconButton(onClick = { tick++ }, modifier = Modifier.size(28.dp), enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh status", modifier = Modifier.size(18.dp))
                    }
                }
            }
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
