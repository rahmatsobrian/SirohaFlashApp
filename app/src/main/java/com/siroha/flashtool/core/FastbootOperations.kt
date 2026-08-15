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
        if (client != null) return@withContext true // already connected — don't re-claim the interface

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

    /** True once [connect] has succeeded and hasn't been [disconnect]ed or lost via reboot. */
    fun isConnected(): Boolean = client != null

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
        handleResult(c.command("erase:$partition", onInfo = { logInfoLine(it) }), "erase $partition")
    }

    suspend fun deleteLogicalPartition(name: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("delete-logical-partition:$name", onInfo = { logInfoLine(it) }), "delete-logical-partition $name")
    }

    suspend fun createLogicalPartition(name: String, sizeBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("create-logical-partition:$name:$sizeBytes", onInfo = { logInfoLine(it) }), "create-logical-partition $name ($sizeBytes bytes)")
    }

    suspend fun resizeLogicalPartition(name: String, sizeBytes: Long): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("resize-logical-partition:$name:$sizeBytes", onInfo = { logInfoLine(it) }), "resize-logical-partition $name ($sizeBytes bytes)")
    }

    /**
     * A bounded, honestly-scoped "wipe super" for GSI flashing: deletes the
     * OPTIONAL dynamic partitions (product, system_ext, odm — the ones
     * every community GSI guide has you clear out, on both A/B slots when
     * present) so a GSI can claim that space. This is NOT the same thing as
     * the real `fastboot` host tool's `--wipe`/`update --wipe`, which parses
     * a super_empty.img's liblp metadata and reconciles the on-device
     * partition table against it byte-for-byte — that's a substantial
     * binary-format parser in its own right and isn't implemented here.
     * vendor/system/boot are deliberately left alone since GSI flashing
     * overwrites system directly rather than deleting it first, and wiping
     * vendor can leave a device unable to boot.
     */
    suspend fun wipeOptionalDynamicPartitions(): List<Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        val targets = listOf(
            "product", "product_a", "product_b",
            "system_ext", "system_ext_a", "system_ext_b",
            "odm", "odm_a", "odm_b"
        )
        targets.map { name ->
            val c = client
            if (c == null) {
                logAndReturnDisconnected()
                name to false
            } else {
                // A partition that doesn't exist on this device/layout will
                // just FAIL harmlessly (e.g. odm on a device with no odm
                // partition, or _a/_b on a non-A/B device) — that's expected
                // and not treated as a fatal error for the batch.
                val ok = handleResult(c.command("delete-logical-partition:$name", onInfo = { logInfoLine(it) }), "delete-logical-partition $name")
                name to ok
            }
        }
    }

    suspend fun setActiveSlot(slot: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("set_active:$slot", onInfo = { logInfoLine(it) }), "set_active $slot")
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
        handleResult(c.command("oem $command", onInfo = { logInfoLine(it) }), "oem $command")
    }

    /** Sends an arbitrary raw fastboot command verbatim — backs the manual command field. */
    suspend fun rawCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command(command, onInfo = { logInfoLine(it) }), command)
    }

    /**
     * Runs any raw command and returns output formatted like the real
     * `fastboot` CLI does: every INFO packet on its own "(bootloader) ..."
     * line, followed by the final OKAY/FAIL status and elapsed time. This
     * is what actually shows for things like `oem device-info` — a
     * previous version silently discarded every INFO line and only
     * displayed the final (often blank, or a short opaque token) OKAY
     * message, which is why the same command could look completely
     * different, and useless, from one run to the next.
     */
    suspend fun rawCommandWithResponse(command: String): String = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext "Not connected — tap Connect first." }

        // `fastboot devices` is a HOST-side (PC-side) subcommand that lists
        // locally attached USB devices — it is never sent over the wire to
        // the phone at all, so intercept it locally instead of forwarding
        // it (the real bootloader has no such command and would just FAIL
        // with "unknown command").
        if (command.trim().equals("devices", ignoreCase = true) || command.trim().startsWith("devices ")) {
            return@withContext localDevicesOutput()
        }

        val startNanos = System.nanoTime()
        val infoLines = mutableListOf<String>()
        val result = c.command(command) { line ->
            infoLines += line
            logInfoLine(line)
        }
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

        val body = infoLines.joinToString("") { "(bootloader) $it\n" }
        val status = when (result) {
            is FastbootUsbClient.FastbootResponse.Okay -> {
                log.success(TAG, "$command: OKAY${if (result.message.isNotBlank()) " (${result.message})" else ""}")
                "OKAY${if (result.message.isNotBlank()) " ${result.message}" else ""} [ %.3fs ]".format(elapsedSeconds)
            }
            is FastbootUsbClient.FastbootResponse.Fail -> {
                log.error(TAG, "$command: FAILED — ${result.message}")
                "FAILED (${result.message})"
            }
            is FastbootUsbClient.FastbootResponse.Error -> {
                log.error(TAG, "$command: ERROR — ${result.reason}")
                "ERROR (${result.reason})"
            }
        }
        "$body$status\nFinished. Total time: %.3fs".format(elapsedSeconds)
    }

    /** Lists locally-visible fastboot-mode USB devices, matching the real `fastboot devices` output shape. */
    private fun localDevicesOutput(): String {
        val devices = UsbDeviceHelper.listDevices(context).filter { UsbDeviceHelper.isLikelyFastbootDevice(it) }
        if (devices.isEmpty()) return "" // real fastboot prints nothing when no device is attached
        return devices.joinToString("\n") { device ->
            val serial = device.serialNumber ?: device.deviceName.substringAfterLast('/')
            "$serial\tfastboot"
        }
    }

    private fun logInfoLine(line: String) = log.info(TAG, "(bootloader) $line")

    /** getvar:token, falling back to `oem get_token` — matches Mi Unlock's own two-step probe. */
    suspend fun getDeviceToken(): String = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext logAndReturnDisconnected()
        val viaGetvar = c.command("getvar:token")
        if (viaGetvar is FastbootUsbClient.FastbootResponse.Okay && viaGetvar.message.isNotBlank()) {
            log.info(TAG, "getvar:token = ${viaGetvar.message}")
            return@withContext viaGetvar.message
        }
        val viaOem = c.command("oem get_token")
        if (viaOem is FastbootUsbClient.FastbootResponse.Okay && viaOem.message.isNotBlank()) {
            log.info(TAG, "oem get_token = ${viaOem.message}")
            return@withContext viaOem.message
        }
        log.error(TAG, "Could not retrieve device token via getvar:token or oem get_token")
        ""
    }

    /** Stages a file into the device's download buffer without an immediate flash/boot follow-up. */
    suspend fun stageFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.download(file), "download ${file.name} (${file.length()} bytes)")
    }

    suspend fun reboot(target: FastbootRebootTarget): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        val cmd = when (target) {
            FastbootRebootTarget.BOOTLOADER -> "reboot-bootloader"
            FastbootRebootTarget.RECOVERY -> "reboot-recovery"
            FastbootRebootTarget.SYSTEM -> "reboot"
            FastbootRebootTarget.FASTBOOTD -> "reboot-fastboot"
        }
        val ok = handleResult(c.command(cmd, onInfo = { logInfoLine(it) }), "reboot ${target.name}")
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
