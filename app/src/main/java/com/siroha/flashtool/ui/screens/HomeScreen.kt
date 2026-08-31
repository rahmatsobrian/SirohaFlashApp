package com.siroha.flashtool.ui.screens

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siroha.flashtool.BuildConfig
import com.siroha.flashtool.core.AdbOperations
import com.siroha.flashtool.core.FastbootOperations
import com.siroha.flashtool.core.UsbDeviceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.siroha.flashtool.R
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import com.siroha.flashtool.ui.theme.HapticIconButton
import com.siroha.flashtool.ui.theme.LocalHapticTrigger


private const val POLL_INTERVAL_MS = 3000L

private sealed class HomeUsbStatus {
    object None : HomeUsbStatus()
    data class Edl(val vendorId: Int, val productId: Int) : HomeUsbStatus()
    object Fastboot : HomeUsbStatus()
    object Adb : HomeUsbStatus()
    object Unrecognized : HomeUsbStatus()
}

private data class InfoRow(val label: String, val value: String)

private data class HomeActiveState(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color
)

/** Reads the kernel release string without needing root - same value `uname -r` reports. */
private suspend fun readKernelVersion(): String = withContext(Dispatchers.IO) {
    runCatching {
        File("/proc/version").readText()
            .substringAfter("Linux version ")
            .substringBefore(" ")
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getProperty("os.version")
        ?: "Unknown"
}

private fun chipsetName(): String = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.isNotBlank() -> Build.SOC_MODEL
    else -> Build.HARDWARE.ifBlank { Build.BOARD }
}

/**
 * Home tab: not a menu - an at-a-glance device/app status screen (app
 * version, kernel, model, chipset, Android version) plus a live
 * Active/Ready banner, mirroring the reference app's own Home tab.
 * Tool menus live under the Tools/Guide/Utilities tabs instead.
 */
@Composable
fun HomeScreen(
    fastbootOperations: FastbootOperations,
    adbOperations: AdbOperations
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var usbStatus by remember { mutableStateOf<HomeUsbStatus>(HomeUsbStatus.None) }
    var kernelVersion by remember { mutableStateOf("...") }
    var tick by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    // Only the tap on the refresh button should announce completion - the
    // silent background poll every POLL_INTERVAL_MS shouldn't spam a snackbar.
    var manualRefreshPending by remember { mutableStateOf(false) }
    // Drives the icon spin + button disable. Unlike isRefreshing (true for
    // every refresh, including the silent background poll), this is only
    // true while a refresh that started from an actual tap is in flight -
    // so the icon no longer "blinks" every POLL_INTERVAL_MS on its own.
    var isSpinning by remember { mutableStateOf(false) }

    // Home used to only *enumerate* the USB device list (cheap, no
    // permission dialog) to show "Detected - Fastboot/ADB" - actually
    // claiming the interface (and the permission popup that comes with it)
    // only happened once you opened Fastboot Flash Tool. That meant the
    // popup showed up "late" and Fastboot Flash's own status card started
    // on "Waiting" every time until you left and came back. This runs the
    // real connect() from here instead, in its own loop (deliberately NOT
    // sharing the `tick` effect below - connect() can sit there awaiting the
    // permission dialog for a while, and `tick` re-fires every
    // POLL_INTERVAL_MS regardless, which would cancel an in-flight
    // connect()/permission wait and could end up re-prompting). Each of
    // Fastboot/ADB gets its own "stop auto-retrying after one denial" guard,
    // same reasoning as Fastboot Flash Tool's own guard: an explicit Deny
    // shouldn't turn into the popup nagging every couple seconds.
    var fastbootConnecting by remember { mutableStateOf(false) }
    var fastbootAutoConnectDenied by remember { mutableStateOf(false) }
    var adbConnecting by remember { mutableStateOf(false) }
    var adbAutoConnectDenied by remember { mutableStateOf(false) }
    // Bus path (e.g. "/dev/bus/usb/002/004") of whichever device
    // fastbootOperations/adbOperations currently holds a live connection
    // to. Needed because a recovery like TWRP re-enumerates a brand NEW
    // USB device when switching from its main-menu ADB into the "ADB
    // Sideload" screen specifically - the device class stays
    // "ADB-looking" the whole time, so type alone never notices the
    // swap, and every command after that silently keeps running against
    // a dead connection to the OLD enumeration until the user manually
    // unplugs/replugs. Comparing this catches a same-type swap, not just
    // a device disappearing outright.
    var lastFastbootDeviceName by remember { mutableStateOf<String?>(null) }
    var lastAdbDeviceName by remember { mutableStateOf<String?>(null) }
    
    // State untuk memunculkan popup QRIS
    var showQrisDialog by remember { mutableStateOf(false) }
    
    // Perbaikan penulisan LocalUriHandler dengan Huruf Kapital di awal
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        while (isActive) {
            // isConnected() only reflects "we opened the interface at some
            // point" and doesn't notice an unplug by itself, and type alone
            // isn't enough to catch a same-type device swap (e.g. TRWP's
            // main-menu ADB vs its ADB Sideload screen) - so this compares
            // against a freshly enumerated deviceName instead.
            val liveFastboot = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
            val liveAdb = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyAdbDevice(it) }

            // lastFastbootDeviceName/lastAdbDeviceName are remember{}-scoped
            // to this composable, but fastbootOperations/adbOperations are
            // app-wide singletons - so if Home gets disposed and recreated
            // (e.g. switching to Tools and back), the connection can still
            // be alive while this instance's "last known device" is back to
            // null. Treating that mismatch as staleness caused a needless
            // disconnect()+reconnect() - the visible "ADB standby, then OK
            // again" flicker (and matching log spam) every time you
            // navigated back to Home. Adopt an already-open connection the
            // first time it's observed instead of tearing it down; only
            // treat it as stale once we've actually recorded a device for
            // this instance and it no longer matches (or has vanished).
            if (fastbootOperations.isConnected()) {
                if (liveFastboot == null) {
                    // No fastboot-looking device present at all anymore -
                    // genuinely stale, not just a new enumeration.
                    fastbootOperations.disconnect()
                    lastFastbootDeviceName = null
                } else if (lastFastbootDeviceName == null) {
                    lastFastbootDeviceName = liveFastboot.deviceName
                } else if (liveFastboot.deviceName != lastFastbootDeviceName) {
                    fastbootOperations.disconnect()
                    lastFastbootDeviceName = null
                    // A genuinely new enumeration (e.g. TWRP main menu ADB
                    // -> ADB Sideload) deserves a fresh auto-connect
                    // attempt, not one that inherits a denial latched by
                    // the previous enumeration.
                    fastbootAutoConnectDenied = false
                }
            }
            if (adbOperations.isConnected()) {
                if (liveAdb == null) {
                    adbOperations.disconnect()
                    lastAdbDeviceName = null
                } else if (lastAdbDeviceName == null) {
                    lastAdbDeviceName = liveAdb.deviceName
                } else if (liveAdb.deviceName != lastAdbDeviceName) {
                    adbOperations.disconnect()
                    lastAdbDeviceName = null
                    adbAutoConnectDenied = false
                }
            }

            when (usbStatus) {
                is HomeUsbStatus.Fastboot -> {
                    if (!fastbootOperations.isConnected() && !fastbootAutoConnectDenied && !fastbootConnecting) {
                        fastbootConnecting = true
                        val ok = fastbootOperations.connect()
                        fastbootConnecting = false
                        if (ok) lastFastbootDeviceName = liveFastboot?.deviceName
                        if (!ok) fastbootAutoConnectDenied = true
                    }
                }
                is HomeUsbStatus.Adb -> {
                    if (!adbOperations.isConnected() && !adbAutoConnectDenied && !adbConnecting) {
                        adbConnecting = true
                        val ok = adbOperations.connect()
                        adbConnecting = false
                        if (ok) lastAdbDeviceName = liveAdb?.deviceName
                        if (!ok) adbAutoConnectDenied = true
                    }
                }
                else -> {
                    // Neither a fastboot- nor ADB-looking device is currently
                    // seen - clear both guards so a fresh/different device
                    // (or the same one replugged) gets its own clean attempt
                    // instead of inheriting a stale denial.
                    fastbootAutoConnectDenied = false
                    adbAutoConnectDenied = false
                }
            }
            delay(1500)
        }
    }

    // Spins the refresh icon while a refresh is in flight, snapping back to
    // 0° the moment it's done instead of settling wherever it stopped.
    val refreshRotation = remember { Animatable(0f) }
    LaunchedEffect(isSpinning) {
        if (isSpinning) {
            refreshRotation.snapTo(0f)
            refreshRotation.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(700, easing = LinearEasing))
            )
        } else {
            refreshRotation.stop()
            refreshRotation.snapTo(0f)
        }
    }

    LaunchedEffect(tick) {
        isRefreshing = true
        if (manualRefreshPending) isSpinning = true
        kernelVersion = readKernelVersion()

        val devices = UsbDeviceHelper.listDevices(context)
        usbStatus = when {
            devices.any { UsbDeviceHelper.isEdlDevice(it) } ->
                devices.first { UsbDeviceHelper.isEdlDevice(it) }.let { HomeUsbStatus.Edl(it.vendorId, it.productId) }
            devices.any { UsbDeviceHelper.isLikelyFastbootDevice(it) } -> HomeUsbStatus.Fastboot
            devices.any { UsbDeviceHelper.isLikelyAdbDevice(it) } -> HomeUsbStatus.Adb
            devices.isNotEmpty() -> HomeUsbStatus.Unrecognized
            else -> HomeUsbStatus.None
        }
        isRefreshing = false
        isSpinning = false

        if (manualRefreshPending) {
            manualRefreshPending = false
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            scope.launch { snackbarHostState.showSnackbar("Status refreshed at $time") }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            tick++
        }
    }

    val deviceConnectedNoRoot = (usbStatus is HomeUsbStatus.Fastboot && fastbootOperations.isConnected()) ||
        (usbStatus is HomeUsbStatus.Adb && adbOperations.isConnected())
    // EDL/QDL flashing (bypass-UBL, raw QDL flash) never needs root or
    // Shizuku - the no-root USB bridge (see UsbBridgeServer) handles it
    // directly. A connected EDL device is just as "ready to flash" as
    // Fastboot/ADB.
    val edlDeviceConnected = usbStatus is HomeUsbStatus.Edl

    // "Active" mirrors real capability: the app flashes over a raw
    // fastboot/ADB USB connection, or an EDL device via the no-root USB
    // bridge - nothing in this app ever needs root or Shizuku. Only truly
    // idle (nothing connected) counts as not active.
    val activeGreen = Color(0xFF84d996)
    val activeState = when {
        deviceConnectedNoRoot -> HomeActiveState("Active", "Ready for flashing", Icons.Filled.CheckCircle, activeGreen)
        edlDeviceConnected -> HomeActiveState("Active", "Ready for flashing", Icons.Filled.CheckCircle, activeGreen)
        else -> HomeActiveState("Not active", "Connect a device to EDL, Fastboot, or ADB mode", Icons.Filled.Warning, MaterialTheme.colorScheme.error)
    }

    // Which transport is actually driving the current connection - this app
    // never uses root/Shizuku, so this replaces what used to be a
    // "Backend Execution" (root/Shizuku) stat card.
    val transportLabel = when {
        edlDeviceConnected -> "EDL 9008"
        usbStatus is HomeUsbStatus.Fastboot -> "Fastboot"
        usbStatus is HomeUsbStatus.Adb -> "ADB"
        else -> "None"
    }

    val deviceStatusLabel = when (val s = usbStatus) {
        is HomeUsbStatus.None -> "Not connected"
        is HomeUsbStatus.Edl -> "EDL 9008 - OK"
        is HomeUsbStatus.Fastboot -> when {
            fastbootOperations.isConnected() -> "Fastboot - OK"
            fastbootConnecting -> "Connecting..."
            fastbootAutoConnectDenied -> "Permission needed"
            else -> "Fastboot - Standby"
        }
        is HomeUsbStatus.Adb -> when {
            adbOperations.isConnected() -> "ADB - OK"
            adbConnecting -> "Connecting..."
            adbAutoConnectDenied -> "Permission needed"
            else -> "ADB - Standby"
        }
        is HomeUsbStatus.Unrecognized -> "Connected - Unknown"
    }

    val infoRows = listOf(
        InfoRow("Application version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
        InfoRow("Kernel version", kernelVersion),
        InfoRow("Device model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
        InfoRow("Chipset", chipsetName()),
        InfoRow("Android version", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"),
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Siroha Flash Tool") },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    // Match the screen background exactly - the default M3
                    // colors use a lighter elevated tone here, which showed
                    // up as a visible gray strip above the black content.
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    HapticIconButton(
                        onClick = { manualRefreshPending = true; tick++ },
                        enabled = !isSpinning
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.graphicsLayer { rotationZ = refreshRotation.value }
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // Hapus .padding(16.dp) dari sini
            contentPadding = PaddingValues(16.dp),              // Pindahkan ke sini
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = activeState.color.copy(alpha = 0.24f * com.siroha.flashtool.ui.theme.LocalCardOpacity.current)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(activeState.icon, contentDescription = null, tint = activeState.color)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(activeState.label, style = MaterialTheme.typography.titleMedium, color = activeState.color, fontWeight = FontWeight.Bold)
                            Text(activeState.subtitle, style = MaterialTheme.typography.bodySmall, color = activeState.color)
                        }
                        // Purely decorative welcome-back chip - not a control; the
                        // functional refresh action lives in the top bar instead.
                        // Fixed pill (not theme-driven) so it reads as a badge
                        // against the colored banner in both light and dark theme.
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFFc6c6c6)) {
                            Text(
                                "おかえりなさい",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF1f1f1f),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeMiniStatCard(modifier = Modifier.weight(1f), label = "Transport", value = transportLabel)
                    HomeMiniStatCard(
                        modifier = Modifier.weight(1f),
                        label = "Device status",
                        value = deviceStatusLabel,
                        loading = fastbootConnecting || adbConnecting
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        infoRows.forEach { row ->
                            Column {
                                Text(row.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(row.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Support us", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        // --- DESKRIPSI DI SUPPORT US ---
                        Text(
                            "The app will always be free. However, if you'd like to support the development of Siroha Flash Tool, you can send a tip or donation via the platforms below:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Tombol Donasi (QRIS & SociaBuzz)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Chip QRIS (Memunculkan dialog/popup QRIS)
                            DonationChip(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.QrCode,
                                label = "QRIS",
                                onClick = { showQrisDialog = true }
                            )
                            
                            // Chip SociaBuzz (Membuka link web browser)
                            DonationChip(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Favorite,
                                label = "SociaBuzz",
                                onClick = {
                                    // Ganti dengan link SociaBuzz Anda yang sebenarnya
                                    uriHandler.openUri("https://sociabuzz.com/siroha/support") 
                                }
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Icon(Icons.Filled.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "Any damage that occurs due to using this application is not the developer's responsibility, so please understand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Dialog Popup QRIS ketika tombol QRIS ditekan
    if (showQrisDialog) {
        AlertDialog(
            onDismissRequest = { showQrisDialog = false },
            title = { Text("Scan QRIS Support") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Thank you for your support! Please scan the QRIS code below using any mobile banking or e-wallet app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Note: Anda bisa menampilkan Image composable berisi gambar QRIS Anda di sini
                    // Contoh: Image(painter = painterResource(id = R.drawable.qris_image), contentDescription = "QRIS")
                    // Menampilkan Gambar QRIS dari drawable
                    Image(
                        painter = painterResource(id = R.drawable.qris),
                        contentDescription = "QRIS Siroha",
                        modifier = Modifier
                            .fillMaxWidth()
                            // Gunakan rasio aspek asli gambar (1f karena QRIS-nya kotak) agar tidak terpotong
                            .aspectRatio(1f) 
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit // atau ContentScale.Fit jika ingin tampil seluruh isi kotak tanpa ada yg terpotong sama sekali
                    )
                }
            },
            confirmButton = {
                // Mengubah tombol menjadi Filled Pill menggunakan Button biasa + RoundedCornerShape(50)
                com.siroha.flashtool.ui.theme.Button(
                    onClick = { showQrisDialog = false },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(bottom = 8.dp, end = 8.dp)
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun HomeMiniStatCard(modifier: Modifier = Modifier, label: String, value: String, loading: Boolean = false) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun DonationChip(modifier: Modifier = Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptic = LocalHapticTrigger.current
    Surface(
        modifier = modifier,
        onClick = { haptic(); onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
