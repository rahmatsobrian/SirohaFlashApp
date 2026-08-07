package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbDevice
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reboot targets exposed by the fastboot protocol / flash.sh's reboot menu. */
enum class FastbootRebootTarget {
    BOOTLOADER, RECOVERY, SYSTEM, FASTBOOTD
}

/**
 * High-level fastboot actions, built on [FastbootUsbClient]. Every screen
 * that used to be a `termux-fastboot ...` line in flash.sh (menu_fastboot,
 * menu_gsi, menu_ab, and the fastboot half of menu_frp) goes through here so
 * logging and connection handling stay in one place.
 */
class FastbootOperations(
    private val context: Context,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "Fastboot"
    }

    private var client: FastbootUsbClient? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val devices = UsbDeviceHelper.listDevices(context).filter { UsbDeviceHelper.isLikelyFastbootDevice(it) }
        val device = devices.firstOrNull()
        if (device == null) {
            log.error(TAG, "No fastboot-mode USB device found. Is it connected and in fastboot mode?")
            return@withContext false
        }
        if (!UsbDeviceHelper.requestPermission(context, device)) {
            log.error(TAG, "USB permission denied for ${device.deviceName}")
            return@withContext false
        }
        val c = FastbootUsbClient(context, device)
        if (!c.open()) {
            log.error(TAG, "Could not open USB connection / claim fastboot interface")
            return@withContext false
        }
        client = c
        log.success(TAG, "Connected to fastboot device ${device.deviceName} (${device.vendorId.toHexId()}:${device.productId.toHexId()})")
        true
    }

    fun disconnect() {
        client?.close()
        client = null
    }

    private fun Int.toHexId() = "%04x".format(this)

    suspend fun getVar(name: String): String = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext logAndReturnDisconnected()
        when (val r = c.command("getvar:$name")) {
            is FastbootUsbClient.FastbootResponse.Okay -> {
                log.info(TAG, "getvar $name = ${r.message}")
                r.message
            }
            is FastbootUsbClient.FastbootResponse.Fail -> {
                log.error(TAG, "getvar $name failed: ${r.message}")
                ""
            }
            is FastbootUsbClient.FastbootResponse.Error -> {
                log.error(TAG, "getvar $name error: ${r.reason}")
                ""
            }
        }
    }

    suspend fun flashPartition(partition: String, file: File): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        log.info(TAG, "Flashing $partition from ${file.name} (${file.length()} bytes)...")
        val result = c.flashPartition(
            partition = partition,
            file = file,
            onInfo = { log.info(TAG, it) },
            onProgress = { sent, total -> if (sent == total) log.info(TAG, "  $partition: transferred $total bytes") }
        )
        handleResult(result, "flash $partition")
    }

    /** Mirrors flash_partition() in flash.sh: flashes an image, then offers to flash vbmeta too. */
    suspend fun flashPartitionWithOptionalVbmeta(partition: String, file: File, vbmeta: File?): Boolean {
        val ok = flashPartition(partition, file)
        if (ok && vbmeta != null) {
            return flashVbmeta(vbmeta, disableVerity = true)
        }
        return ok
    }

    suspend fun flashVbmeta(file: File, disableVerity: Boolean, slotSuffix: String = ""): Boolean = withContext(Dispatchers.IO) {
        // The real fastboot host tool turns --disable-verity/--disable-verification
        // into extra oem-style flags before the flash command; the wire-level
        // equivalent most gadgets accept is flashing the already-patched vbmeta
        // image itself (which is what flash.sh's callers pass in), so we just
        // flash it directly onto vbmeta[_a|_b].
        flashPartition("vbmeta$slotSuffix", file)
    }

    suspend fun erase(partition: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        log.warn(TAG, "Erasing partition '$partition'...")
        handleResult(c.command("erase:$partition"), "erase $partition")
    }

    suspend fun deleteLogicalPartition(name: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("delete-logical-partition:$name"), "delete-logical-partition $name")
    }

    suspend fun setActiveSlot(slot: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("set_active:$slot"), "set_active $slot")
    }

    suspend fun boot(file: File): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        log.info(TAG, "Booting ${file.name} without flashing (temporary boot, e.g. TWRP)...")
        val result = c.downloadThen(
            file = file,
            finalCommand = "boot",
            onInfo = { log.info(TAG, it) }
        )
        handleResult(result, "boot ${file.name}")
    }

    suspend fun oem(command: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("oem $command"), "oem $command")
    }

    /** Sends an arbitrary raw fastboot command verbatim — backs the manual command field. */
    suspend fun rawCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command(command), command)
    }

    suspend fun reboot(target: FastbootRebootTarget): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        val cmd = when (target) {
            FastbootRebootTarget.BOOTLOADER -> "reboot-bootloader"
            FastbootRebootTarget.RECOVERY -> "reboot-recovery"
            FastbootRebootTarget.SYSTEM -> "reboot"
            FastbootRebootTarget.FASTBOOTD -> "reboot-fastboot"
        }
        val ok = handleResult(c.command(cmd), "reboot ${target.name}")
        if (ok) disconnect() // device is gone until it re-enumerates
        ok
    }

    private fun handleResult(result: FastbootUsbClient.FastbootResponse, what: String): Boolean =
        when (result) {
            is FastbootUsbClient.FastbootResponse.Okay -> {
                log.success(TAG, "$what: OK${if (result.message.isNotBlank()) " (${result.message})" else ""}")
                true
            }
            is FastbootUsbClient.FastbootResponse.Fail -> {
                log.error(TAG, "$what: FAILED — ${result.message}")
                false
            }
            is FastbootUsbClient.FastbootResponse.Error -> {
                log.error(TAG, "$what: ERROR — ${result.reason}")
                false
            }
        }

    private fun logAndReturnDisconnected(): String {
        log.error(TAG, "Not connected — call connect() first.")
        return ""
    }
}
