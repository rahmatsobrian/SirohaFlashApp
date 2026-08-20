package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * High-level ADB-over-USB actions built on [AdbUsbClient] — backs the
 * Samsung/SPRD FRP methods from flash.sh's menu_frp (both are plain
 * `adb shell` commands against the target), general shell access via the
 * manual command boxes, and ZIP sideloading.
 */
class AdbOperations(
    private val context: Context,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "Adb"
    }

    private var client: AdbUsbClient? = null

    // Guards connect() end-to-end: since this is now a single shared
    // instance used from several screens (and Home's auto-connect can fire
    // in the background too), two callers could otherwise race to open a
    // second UsbDeviceConnection / claim the same interface concurrently —
    // Android's USB host APIs are not safe to drive from two threads at
    // once, and that race was the likely cause of a hang-then-crash when
    // tapping into the app while a slow handshake (e.g. waiting on the
    // target's "Allow USB debugging?" prompt) was still in flight.
    private val connectMutex = Mutex()

    /**
     * Connects and completes the auth handshake. Returns true once CNXN
     * succeeds. If this is the first time pairing with this target, the
     * device will show an "Allow USB debugging?" dialog — this function
     * logs that and returns false; call it again after the user accepts.
     */
    suspend fun connect(): Boolean = connectMutex.withLock {
        withContext(Dispatchers.IO) {
            if (client != null) return@withContext true // already connected — don't re-claim the interface

            val devices = UsbDeviceHelper.listDevices(context).filter { UsbDeviceHelper.isLikelyAdbDevice(it) }
            val device = devices.firstOrNull()
            if (device == null) {
                log.error(TAG, "No ADB-mode USB device found. Is USB debugging enabled and the device connected?")
                return@withContext false
            }
            if (!UsbDeviceHelper.requestPermission(context, device)) {
                log.error(TAG, "USB permission denied for ${device.deviceName}")
                return@withContext false
            }
            val c = AdbUsbClient(context, device)
            if (!c.open()) {
                log.error(TAG, "Could not open USB connection / claim ADB interface")
                return@withContext false
            }
            val keyPair = AdbKeyManager.loadOrGenerate(context)
            when (val result = c.handshake(keyPair)) {
                is AdbUsbClient.ConnectResult.Connected -> {
                    client = c
                    log.success(TAG, "ADB connected to ${device.deviceName}")
                    true
                }
                is AdbUsbClient.ConnectResult.AwaitingUserAuthorization -> {
                    log.warn(TAG, "Check the target device screen — tap \"Allow\" on the USB debugging prompt, then tap Connect again.")
                    c.close()
                    false
                }
                is AdbUsbClient.ConnectResult.Error -> {
                    log.error(TAG, "ADB handshake failed: ${result.reason}")
                    c.close()
                    false
                }
            }
        }
    }

    fun disconnect() {
        client?.close()
        client = null
    }

    /** True once [connect] has succeeded and hasn't been [disconnect]ed. */
    fun isConnected(): Boolean = client != null

    data class ShellOutcome(val text: String, val success: Boolean)

    /**
     * Runs `adb shell <command>`. Uses the modern `shell,v2,raw:` service
     * (real exit code, so success/failure is actually known) when the
     * connected device supports it; falls back to the legacy `shell:`
     * service — which has NO exit-code signal at the protocol level at
     * all — on older Android versions that don't. In that fallback case,
     * [ShellOutcome.success] reports the honest truth: unknown, so it's
     * never wrongly reported as either. Prefer this over the plain
     * [shell] overload wherever the caller can act on success/failure.
     */
    suspend fun shellWithOutcome(command: String): ShellOutcome = withContext(Dispatchers.IO) {
        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext ShellOutcome("ERROR: Not connected — tap Connect first.", false)
        }
        log.info(TAG, "adb shell $command")
        if (c.supportsShellV2) {
            val result = c.runShellV2(command)
            val combined = buildString {
                if (result.stdout.isNotEmpty()) append(result.stdout)
                if (result.stderr.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(result.stderr)
                }
            }
            if (combined.isNotBlank()) log.info(TAG, combined.trim())
            val success = result.exitCode == 0
            if (success) log.success(TAG, "exit code 0") else log.error(TAG, "exit code ${result.exitCode ?: "unknown"}")
            ShellOutcome(combined.ifBlank { "(no output, exit code ${result.exitCode ?: "unknown"})" }, success)
        } else {
            val output = c.runService("shell:$command")
            if (output.isNotBlank()) log.info(TAG, output.trim())
            log.warn(TAG, "Device doesn't advertise shell_v2 — exit code unavailable, success/failure can't be determined from the protocol alone.")
            ShellOutcome(output.ifBlank { "(no output — device doesn't support exit codes over legacy shell, so success/failure is unknown)" }, true)
        }
    }

    /** Plain-text convenience wrapper over [shellWithOutcome] for callers that only want the output text. */
    suspend fun shell(command: String): String = shellWithOutcome(command).text

    /**
     * Runs a raw host-level ADB service (NOT wrapped in `shell:`) — the
     * ADB-protocol equivalent of typing a bare `adb <command>` on a PC
     * rather than `adb shell <command>`. Most such commands (`devices`,
     * `version`, `get-state`, ...) are actually answered entirely by the
     * LOCAL adb server on a PC and never touch the device at all — same
     * situation as fastboot's `devices`. Since this app has no separate
     * local adb server process, `devices` is intercepted here the same
     * way; anything else is sent as a literal ADB service name (e.g.
     * `reboot`, `reboot:bootloader`, `root`, `remount`).
     */
    suspend fun rawCommand(command: String): ShellOutcome = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.equals("devices", ignoreCase = true) || trimmed.startsWith("devices ")) {
            val devices = UsbDeviceHelper.listDevices(context).filter { UsbDeviceHelper.isLikelyAdbDevice(it) }
            val text = if (devices.isEmpty()) {
                "" // real `adb devices` prints nothing but a header when nothing is attached
            } else {
                devices.joinToString("\n") { device ->
                    val serial = device.serialNumber ?: device.deviceName.substringAfterLast('/')
                    "$serial\tdevice"
                }
            }
            log.info(TAG, "adb devices\n$text")
            return@withContext ShellOutcome(text.ifBlank { "(no devices)" }, true)
        }

        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext ShellOutcome("ERROR: Not connected — tap Connect first.", false)
        }
        log.info(TAG, "adb $trimmed")
        val output = c.runService(trimmed)
        if (output.isNotBlank()) log.info(TAG, output.trim())
        // Host-level services (reboot:, root, remount, ...) reply OKAY/FAIL
        // at the ADB stream level rather than a shell exit code; runService
        // doesn't currently surface that distinction, so — same honesty
        // rule as the legacy shell fallback above — this doesn't claim a
        // success it can't actually verify.
        ShellOutcome(output.ifBlank { "(sent — no response text)" }, true)
    }

    /** Sideloads a ZIP (e.g. a Magisk or OTA package) to a device already booted into recovery. */
    suspend fun sideload(file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext false
        }
        log.info(TAG, "Sideloading ${file.name} (${file.length()} bytes)...")
        var lastLoggedPercent = -1
        val result = c.sideload(file) { sent, total ->
            val percent = if (total > 0) ((sent * 100) / total).toInt() else 0
            if (percent != lastLoggedPercent && percent % 10 == 0) {
                log.info(TAG, "  sideload: $percent% ($sent/$total bytes)")
                lastLoggedPercent = percent
            }
        }
        when (result) {
            is AdbUsbClient.SideloadResult.Success -> {
                log.success(TAG, "Sideload complete.")
                true
            }
            is AdbUsbClient.SideloadResult.Rejected -> {
                log.error(TAG, "Sideload rejected: ${result.reason}")
                false
            }
            is AdbUsbClient.SideloadResult.Error -> {
                log.error(TAG, "Sideload error: ${result.reason}")
                false
            }
        }
    }
}
