package com.siroha.flashtool.ui.screens

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.siroha.flashtool.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar
import kotlinx.coroutines.launch
import com.siroha.flashtool.ui.components.ActionEntry
import com.siroha.flashtool.ui.components.ActionListGroup

// Same "good state" accent used on Home/Settings, so a granted USB
// permission reads as the same "everything's fine" green everywhere.
private val ActiveGreen = Color(0xFF84D996)

private data class UsbKind(val label: String, val icon: ImageVector)

private fun UsbDevice.kind(): UsbKind = when {
    UsbDeviceHelper.isEdlDevice(this) -> UsbKind("Qualcomm EDL (9008)", Icons.Filled.Bolt)
    UsbDeviceHelper.isLikelyFastbootDevice(this) -> UsbKind("Likely fastboot", Icons.Filled.Build)
    UsbDeviceHelper.isLikelyAdbDevice(this) -> UsbKind("Likely ADB", Icons.Filled.PhoneAndroid)
    else -> UsbKind("Unrecognized USB device", Icons.Filled.HelpOutline)
}

@Composable
private fun UsbDeviceRow(device: UsbDevice, manager: UsbManager, refreshTick: Int, onPermissionChange: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val kind = device.kind()
    // Re-evaluated whenever refreshTick changes (bumped after a grant
    // request completes) - UsbManager.hasPermission() has no Flow/listener
    // of its own, so this is what makes a fresh grant show up immediately.
    val hasPermission = remember(refreshTick, device) { manager.hasPermission(device) }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(device.deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                Text(
                    "VID:PID = %04x:%04x".format(device.vendorId, device.productId),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (hasPermission) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ActiveGreen, modifier = Modifier.size(16.dp))
                    Text("Granted", style = MaterialTheme.typography.labelLarge, color = ActiveGreen)
                }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        UsbDeviceHelper.requestPermission(context, device)
                        onPermissionChange()
                    }
                }) { Text("Grant access") }
            }
        }
    }
}

@Composable
fun UsbFixScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    var devices by remember { mutableStateOf(UsbDeviceHelper.listDevices(context)) }
    // Bumped after a permission request completes so device rows - whose
    // "Granted" state is read live from UsbManager rather than stored in
    // `devices` - actually recompose and reflect the new grant.
    var refreshTick by remember { mutableIntStateOf(0) }
    fun rescan() { devices = UsbDeviceHelper.listDevices(context); refreshTick++ }

    Scaffold(
        topBar = { SirohaTopBar("USB / OTG Fix", icon = Icons.Filled.Cable, onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ActionListGroup(
                    listOf(
                        ActionEntry(
                            title = "Rescan USB devices",
                            subtitle = "Refresh the list of connected OTG cables/adapters", // Tambahan subtitle opsional biar makin cakep
                            icon = Icons.Filled.Refresh,
                            onClick = { rescan() }
                        )
                    )
                )
            }

            if (devices.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Filled.Cable, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text("No USB device detected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Check your OTG cable/adapter is connected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(devices, key = { "${it.vendorId}:${it.productId}:${it.deviceName}" }) { device ->
                    UsbDeviceRow(device = device, manager = manager, refreshTick = refreshTick, onPermissionChange = { refreshTick++ })
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    SectionHeading(Icons.Filled.Info, "Troubleshooting", modifier = Modifier.padding(start = 4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Text(
                            "• USB not detected → confirm the host phone supports USB Host/OTG, try a different cable\n" +
                                "• Target not responding → confirm it's actually in EDL (9008), fastboot, or ADB mode\n" +
                                "• Permission dialog doesn't appear → reconnect the cable and retry the action that needs USB\n" +
                                "• ADB keeps re-prompting → tap \"Allow\" on the target's screen, then retry Connect",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
