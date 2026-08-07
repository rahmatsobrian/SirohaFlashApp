package com.siroha.flashtool.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.core.UsbDeviceHelper
import com.siroha.flashtool.ui.components.SectionHeading
import com.siroha.flashtool.ui.components.SirohaTopBar

@Composable
fun UsbFixScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf(UsbDeviceHelper.listDevices(context)) }

    Scaffold(
        topBar = { SirohaTopBar("USB / OTG Fix", icon = Icons.Filled.Cable, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = { devices = UsbDeviceHelper.listDevices(context) }) {
                Text("Rescan USB devices")
            }

            if (devices.isEmpty()) {
                Text("No USB device detected. Check your OTG cable/adapter is connected.", style = MaterialTheme.typography.bodyMedium)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    val kind = when {
                        UsbDeviceHelper.isEdlDevice(device) -> "Qualcomm EDL (9008)"
                        UsbDeviceHelper.isLikelyFastbootDevice(device) -> "Likely fastboot"
                        UsbDeviceHelper.isLikelyAdbDevice(device) -> "Likely ADB"
                        else -> "Unrecognized USB device"
                    }
                    Card {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(device.deviceName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "VID:PID = %04x:%04x — %s".format(device.vendorId, device.productId, kind),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            SectionHeading(Icons.Filled.Info, "Troubleshooting")
            Text(
                "• USB not detected → confirm the host phone supports USB Host/OTG, try a different cable\n" +
                    "• Target not responding → confirm it's actually in EDL (9008), fastboot, or ADB mode\n" +
                    "• Permission dialog doesn't appear → reconnect the cable and retry the action that needs USB\n" +
                    "• ADB keeps re-prompting → tap \"Allow\" on the target's screen, then retry Connect",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
