package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level ADB-over-USB actions built on [AdbUsbClient] — backs the
 * Samsung/SPRD FRP methods from flash.sh's menu_frp (both are plain
 * `adb shell` commands against the target) and general shell access.
 *
 * NOT implemented: ADB sideload (a separate chunked/flow-controlled
 * protocol, not a plain shell command — see README).
 */
class AdbOperations(
    private val context: Context,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "Adb"
    }

    private var client: AdbUsbClient? = null

    /**
     * Connects and completes the auth handshake. Returns true once CNXN
     * succeeds. If this is the first time pairing with this target, the
     * device will show an "Allow USB debugging?" dialog — this function
     * logs that and returns false; call it again after the user accepts.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
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

    fun disconnect() {
        client?.close()
        client = null
    }

    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext ""
        }
        log.info(TAG, "adb shell $command")
        val output = c.runService("shell:$command")
        if (output.isNotBlank()) log.info(TAG, output.trim())
        output
    }
}
