package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbDevice
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One partition as reported by the bootloader's `getvar all`. */
data class PartitionInfo(
    val name: String,
    val type: String?,
    val sizeBytes: Long?,
    /** Logical (dynamic) partitions generally can only be read/written from FastbootD. */
    val logical: Boolean
)

/** Result of [FastbootOperations.readPartitionTable]; [message] explains an empty list. */
data class PartitionTable(val partitions: List<PartitionInfo>, val message: String? = null)

/** Reboot targets exposed by the fastboot protocol / flash.sh's reboot menu. */
enum class FastbootRebootTarget {
    BOOTLOADER, RECOVERY, SYSTEM, FASTBOOTD,

    /** `continue` — resume the normal boot process from the bootloader. */
    CONTINUE,

    /** `powerdown` — power the device off (not every bootloader implements it). */
    POWEROFF,

    /**
     * Qualcomm EDL (9008). There is no standard fastboot command for this;
     * vendors use `oem edl`, `oem reboot-edl` or `reboot-edl`, so each is
     * tried in turn.
     */
    EDL
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

    // See AdbOperations' connectMutex for why this exists: this instance is
    // now shared across screens plus Home's background auto-connect, so
    // without a lock two callers could race to open/claim the same USB
    // interface concurrently.
    private val connectMutex = Mutex()

    suspend fun connect(): Boolean = connectMutex.withLock {
        withContext(Dispatchers.IO) {
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
        // Flows made of several steps set their own "Flashing x (3/12)" line; only fill it in when nobody did.
        if (OperationStatus.stage.value == null) OperationStatus.setStage("Flashing $partition")
        val result = c.flashPartition(
            partition = partition,
            file = file,
            onInfo = { log.info(TAG, it) },
            onProgress = { sent, total ->
                OperationStatus.reportProgress(sent, total)
                if (sent == total) log.info(TAG, "  $partition: transferred $total bytes")
            }
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
        if (OperationStatus.stage.value == null) OperationStatus.setStage("Sending ${file.name} to the device")
        val result = c.downloadThen(
            file = file,
            finalCommand = "boot",
            onInfo = { log.info(TAG, it) },
            onProgress = { sent, total -> OperationStatus.reportProgress(sent, total) }
        )
        handleResult(result, "boot ${file.name}")
    }

    suspend fun oem(command: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        handleResult(c.command("oem $command", onInfo = { logInfoLine(it) }), "oem $command")
    }

    /** Sends an arbitrary raw fastboot command — backs the manual command field. */
    suspend fun rawCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        val normalized = normalizeFastbootCommand(command)
        handleResult(c.command(normalized, onInfo = { logInfoLine(it) }), normalized)
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
        val c = client ?: run { logAndReturnDisconnected(); return@withContext "ERROR: Not connected — tap Connect first." }

        // `fastboot devices` is a HOST-side (PC-side) subcommand that lists
        // locally attached USB devices — it is never sent over the wire to
        // the phone at all, so intercept it locally instead of forwarding
        // it (the real bootloader has no such command and would just FAIL
        // with "unknown command").
        if (command.trim().equals("devices", ignoreCase = true) || command.trim().startsWith("devices ")) {
            return@withContext localDevicesOutput()
        }

        val normalized = normalizeFastbootCommand(command)
        val startNanos = System.nanoTime()
        val infoLines = mutableListOf<String>()
        val result = c.command(normalized) { line ->
            infoLines += line
            logInfoLine(line)
        }
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0

        val body = infoLines.joinToString("") { "(bootloader) $it\n" }
        val status = when (result) {
            is FastbootUsbClient.FastbootResponse.Okay -> {
                log.success(TAG, "$normalized: OKAY${if (result.message.isNotBlank()) " (${result.message})" else ""}")
                "OKAY${if (result.message.isNotBlank()) " ${result.message}" else ""} [ %.3fs ]".format(elapsedSeconds)
            }
            is FastbootUsbClient.FastbootResponse.Fail -> {
                log.error(TAG, "$normalized: FAILED — ${result.message}")
                "FAILED (${result.message})"
            }
            is FastbootUsbClient.FastbootResponse.Error -> {
                log.error(TAG, "$normalized: ERROR — ${result.reason}")
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
        handleResult(c.download(file) { sent, total -> OperationStatus.reportProgress(sent, total) }, "download ${file.name} (${file.length()} bytes)")
    }

    suspend fun reboot(target: FastbootRebootTarget): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        if (target == FastbootRebootTarget.EDL) {
            val ok = rebootToEdl(c)
            if (ok) disconnect()
            return@withContext ok
        }
        val cmd = when (target) {
            FastbootRebootTarget.BOOTLOADER -> "reboot-bootloader"
            FastbootRebootTarget.RECOVERY -> "reboot-recovery"
            FastbootRebootTarget.SYSTEM -> "reboot"
            FastbootRebootTarget.FASTBOOTD -> "reboot-fastboot"
            FastbootRebootTarget.CONTINUE -> "continue"
            FastbootRebootTarget.POWEROFF -> "powerdown"
            FastbootRebootTarget.EDL -> "oem edl" // handled above; kept so the when stays exhaustive
        }
        val ok = handleResult(c.command(cmd, onInfo = { logInfoLine(it) }), "reboot ${target.name}")
        if (ok) disconnect() // device is gone until it re-enumerates
        ok
    }

    /**
     * Tries the known vendor spellings of "reboot to EDL" until one is
     * accepted. A device that really does reboot usually drops USB before it
     * can send OKAY, so a dropped connection counts as "probably worked" -
     * check that a Qualcomm 9008 device shows up afterwards.
     */
    private fun rebootToEdl(c: FastbootUsbClient): Boolean {
        for (cmd in listOf("oem edl", "oem reboot-edl", "reboot-edl")) {
            when (val r = c.command(cmd, onInfo = { logInfoLine(it) })) {
                is FastbootUsbClient.FastbootResponse.Okay -> {
                    log.success(TAG, "'$cmd' accepted - device should now be in EDL (9008)")
                    return true
                }
                is FastbootUsbClient.FastbootResponse.Error -> {
                    log.warn(TAG, "'$cmd': connection dropped before a reply (${r.reason}) - assuming the device is rebooting into EDL")
                    return true
                }
                is FastbootUsbClient.FastbootResponse.Fail -> {
                    log.warn(TAG, "'$cmd' not accepted: ${r.message}")
                }
            }
        }
        log.error(TAG, "None of the EDL reboot commands were accepted - this bootloader may not support rebooting to EDL from fastboot")
        return false
    }

    // ------------------------------------------------------------------
    // Device info / partition table
    // ------------------------------------------------------------------

    /** Silent single-variable query (no error logging) — used where "unsupported" is a normal answer. */
    private fun queryVar(c: FastbootUsbClient, name: String): String? =
        (c.command("getvar:$name") as? FastbootUsbClient.FastbootResponse.Okay)
            ?.message?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Reads the handful of bootloader variables people actually look at and
     * formats them as a readable block. Variables the bootloader doesn't
     * report are shown as "(not reported)" instead of failing the whole call.
     */
    suspend fun deviceSummary(): String = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext "ERROR: Not connected — tap Connect first." }
        val vars = listOf(
            "product" to "Product",
            "variant" to "Variant",
            "serialno" to "Serial",
            "version-bootloader" to "Bootloader version",
            "version-baseband" to "Baseband version",
            "version" to "Protocol version",
            "unlocked" to "Unlocked",
            "secure" to "Secure",
            "is-userspace" to "Mode",
            "current-slot" to "Current slot",
            "slot-count" to "Slot count",
            "max-download-size" to "Max download size",
            "super-partition-name" to "Super partition",
            "snapshot-update-status" to "Snapshot update status"
        )
        log.info(TAG, "Reading device summary (${vars.size} variables)...")
        val text = vars.joinToString("\n") { (key, label) ->
            val raw = queryVar(c, key)
            val shown = when {
                raw == null -> "(not reported)"
                key == "is-userspace" -> if (raw.equals("yes", true)) "FastbootD (userspace)" else "Bootloader fastboot"
                key == "max-download-size" -> parseHexSize(raw)?.let { "$raw  (${formatBytes(it)})" } ?: raw
                else -> raw
            }
            "$label: $shown"
        }
        log.success(TAG, "Device summary read")
        text
    }

    /**
     * Runs `getvar all` and turns the partition-size / partition-type /
     * is-logical lines into one entry per partition. Only what the
     * bootloader itself reports is returned - nothing is guessed. When the
     * list can't be read, [PartitionTable.message] says why.
     */
    suspend fun readPartitionTable(): PartitionTable = withContext(Dispatchers.IO) {
        val c = client ?: run {
            logAndReturnDisconnected()
            return@withContext PartitionTable(emptyList(), "ERROR: Not connected — tap Connect first.")
        }
        val lines = mutableListOf<String>()
        val result = c.command("getvar:all") { lines += it }
        if (result is FastbootUsbClient.FastbootResponse.Fail) {
            log.error(TAG, "getvar all failed: ${result.message}")
            return@withContext PartitionTable(emptyList(), "FAILED (${result.message})")
        }
        if (result is FastbootUsbClient.FastbootResponse.Error) {
            log.error(TAG, "getvar all error: ${result.reason}")
            return@withContext PartitionTable(emptyList(), "ERROR (${result.reason})")
        }

        val pattern = Regex("^(partition-size|partition-type|is-logical|has-slot):([^:]+):\\s*(.*)$")
        val sizes = mutableMapOf<String, String>()
        val types = mutableMapOf<String, String>()
        val logical = mutableMapOf<String, String>()
        for (raw in lines) {
            val m = pattern.find(raw.trim()) ?: continue
            val (key, name, value) = m.destructured
            when (key) {
                "partition-size" -> sizes[name] = value.trim()
                "partition-type" -> types[name] = value.trim()
                "is-logical" -> logical[name] = value.trim()
            }
        }
        val names = (sizes.keys + types.keys + logical.keys).toSortedSet()
        if (names.isEmpty()) {
            log.warn(TAG, "getvar all returned ${lines.size} lines but no partition-* entries")
            return@withContext PartitionTable(
                emptyList(),
                "No partition info reported by this bootloader (${lines.size} variables returned)."
            )
        }
        log.success(TAG, "Read ${names.size} partitions")
        PartitionTable(
            names.map { n ->
                PartitionInfo(
                    name = n,
                    type = types[n],
                    sizeBytes = sizes[n]?.let { parseHexSize(it) },
                    logical = logical[n].equals("yes", ignoreCase = true)
                )
            }
        )
    }

    /** Human-readable partition table (see [readPartitionTable]). */
    suspend fun listPartitions(): String {
        val table = readPartitionTable()
        if (table.partitions.isEmpty()) {
            return (table.message ?: "No partitions reported") +
                if (table.message?.startsWith("No partition info") == true) "\nTry the terminal: getvar partition-size:boot" else ""
        }
        val header = "%-22s %-8s %-12s %s".format("PARTITION", "TYPE", "SIZE", "LOGICAL")
        val rows = table.partitions.map { p ->
            "%-22s %-8s %-12s %s".format(p.name, p.type ?: "-", p.sizeBytes?.let { formatBytes(it) } ?: "-", if (p.logical) "yes" else "")
        }
        return (listOf(header) + rows).joinToString("\n") + "\n\n${table.partitions.size} partitions"
    }

    /**
     * Whether [partition] has _a/_b slots, per `getvar has-slot:`. Null when
     * the bootloader doesn't answer. A name that already ends in _a/_b is
     * treated as "no further slot to pick".
     */
    suspend fun partitionHasSlot(partition: String): Boolean? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        if (partition.endsWith("_a") || partition.endsWith("_b")) return@withContext false
        when (queryVar(c, "has-slot:$partition")?.lowercase()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    }

    /** max-download-size as bytes, or null if the bootloader doesn't report it. */
    suspend fun getMaxDownloadSize(): Long? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        queryVar(c, "max-download-size")?.let { parseHexSize(it) }
    }

    /** Fastboot reports sizes as hex, usually "0x"-prefixed. */
    private fun parseHexSize(raw: String): Long? {
        val v = raw.trim()
        return if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toLongOrNull(16) else v.toLongOrNull(16)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    /**
     * Same as [flashPartition], but first compares the file against the
     * device's max-download-size and stops early with a clear message
     * instead of letting the bootloader reject it halfway. The desktop
     * `fastboot` tool silently splits big images into sparse chunks; this
     * app does not do that yet, so oversized images have to be pre-split.
     */
    suspend fun flashPartitionChecked(partition: String, file: File): Boolean {
        val max = getMaxDownloadSize()
        if (max != null && max > 0 && file.length() > max) {
            log.error(
                TAG,
                "${file.name} is ${formatBytes(file.length())} but this device accepts at most ${formatBytes(max)} per download. " +
                    "Use a sparse/split image — automatic splitting isn't supported in this app yet."
            )
            return false
        }
        return flashPartition(partition, file)
    }

    // ------------------------------------------------------------------
    // Terminal with an attached file
    // ------------------------------------------------------------------

    /** True when [command] consumes the file attached in the terminal (`flash <partition>` / `boot`). */
    fun commandNeedsFile(command: String): Boolean {
        val parts = command.trim().split(Regex("\\s+"))
        return when (parts[0].lowercase()) {
            "flash" -> parts.size >= 2
            "boot" -> parts.size == 1
            else -> false
        }
    }

    /**
     * Terminal entry point. `flash <partition>` and `boot` use [file] (the
     * attachment); everything else behaves exactly like [rawCommandWithResponse].
     */
    suspend fun rawCommandWithFile(command: String, file: File?): String {
        val trimmed = command.trim()
        if (!commandNeedsFile(trimmed)) return rawCommandWithResponse(trimmed)
        val parts = trimmed.split(Regex("\\s+"))
        if (file == null) {
            return "ERROR: attach an image first (Attach file), then run: $trimmed"
        }
        return when (parts[0].lowercase()) {
            "flash" -> {
                if (parts.size != 2) return "ERROR: usage: flash <partition>   (the attached file is flashed)"
                val ok = flashPartitionChecked(parts[1], file)
                if (ok) "OKAY flashed ${parts[1]} from ${file.name} (${file.length()} bytes)"
                else "FAILED flash ${parts[1]} - see Recent activity for the reason"
            }
            else -> {
                val ok = boot(file)
                if (ok) "OKAY booted ${file.name} (temporary boot, nothing was written)"
                else "FAILED boot - see Recent activity for the reason"
            }
        }
    }

    // ------------------------------------------------------------------
    // Partition backup (device -> file) via `fetch`
    // ------------------------------------------------------------------

    /**
     * On A/B devices `boot` really lives at `boot_a` / `boot_b`; the PC tool
     * appends the current slot for you, so do the same here. Names that
     * already carry a slot suffix are left alone.
     */
    private fun resolveSlotName(c: FastbootUsbClient, name: String): String {
        if (name.endsWith("_a") || name.endsWith("_b")) return name
        val hasSlot = queryVar(c, "has-slot:$name")
        if (!hasSlot.equals("yes", ignoreCase = true)) return name
        val slot = queryVar(c, "current-slot") ?: return name
        val resolved = "${name}_${slot.trim().removePrefix("_")}"
        log.info(TAG, "$name is slotted - using $resolved (current slot)")
        return resolved
    }

    /**
     * Reads a partition off the device with the fastboot `fetch` command and
     * streams it into [out]. Honors `max-fetch-size` by requesting the
     * partition in ranges when the bootloader reports a limit.
     *
     * Not every bootloader implements `fetch` (it arrived with Android 11
     * era fastboot) and many only allow it on unlocked devices or for
     * certain partitions - when it's refused, the bootloader's own message
     * is logged and false is returned.
     */
    /** The bootloader's FAIL text from the most recent [fetchPartition], e.g. "unknown command" when it has no `fetch`. */
    @Volatile
    var lastFetchFailure: String? = null
        private set

    suspend fun fetchPartition(
        partition: String,
        out: java.io.OutputStream,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        lastFetchFailure = null
        val report: (Long, Long) -> Unit = { done, total ->
            OperationStatus.reportProgress(done, total)
            onProgress(done, total)
        }
        val name = resolveSlotName(c, partition)
        val size = queryVar(c, "partition-size:$name")?.let { parseHexSize(it) }
        val maxFetch = queryVar(c, "max-fetch-size")?.let { parseHexSize(it) }
        log.info(
            TAG,
            "Backing up $name" + (size?.let { " (${formatBytes(it)})" } ?: "") +
                (maxFetch?.let { ", max fetch ${formatBytes(it)}" } ?: "") + "..."
        )

        val result: FastbootUsbClient.FastbootResponse =
            if (size != null && maxFetch != null && maxFetch > 0 && size > maxFetch) {
                var offset = 0L
                var last: FastbootUsbClient.FastbootResponse = FastbootUsbClient.FastbootResponse.Okay("")
                while (offset < size) {
                    val chunk = minOf(maxFetch, size - offset)
                    val base = offset
                    last = c.fetch(
                        command = "fetch:$name:0x%08x:0x%08x".format(offset, chunk),
                        out = out,
                        onInfo = { logInfoLine(it) },
                        onProgress = { done, _ -> report(base + done, size) }
                    )
                    if (last !is FastbootUsbClient.FastbootResponse.Okay) break
                    offset += chunk
                }
                last
            } else {
                c.fetch(
                    command = "fetch:$name",
                    out = out,
                    onInfo = { logInfoLine(it) },
                    onProgress = report
                )
            }
        lastFetchFailure = (result as? FastbootUsbClient.FastbootResponse.Fail)?.message
        handleResult(result, "backup $name")
    }

    /**
     * Equivalent of `fastboot -w`: erases userdata, then cache and metadata.
     * Only userdata has to succeed - many devices have no cache partition,
     * and a missing one just FAILs harmlessly.
     */
    suspend fun wipeUserData(): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: run { logAndReturnDisconnected(); return@withContext false }
        log.warn(TAG, "Wiping user data (userdata, cache, metadata)...")
        val userdata = handleResult(c.command("erase:userdata", onInfo = { logInfoLine(it) }), "erase userdata")
        if (!userdata) return@withContext false
        for (extra in listOf("cache", "metadata")) {
            handleResult(c.command("erase:$extra", onInfo = { logInfoLine(it) }), "erase $extra")
        }
        true
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

    /**
     * Translates the natural, space-separated syntax the real `fastboot`
     * CLI accepts (`reboot bootloader`, `getvar all`, `erase cache`,
     * `set_active a`) into the colon/hyphen wire format the bootloader
     * protocol actually expects (`reboot-bootloader`, `getvar:all`,
     * `erase:cache`, `set_active:a`) — so the manual command field feels
     * like typing after `fastboot ` on a PC instead of requiring raw wire
     * syntax. Commands that are already space-based on the wire (`oem ...`,
     * `flashing ...`) and single-word commands (`continue`, `devices`) are
     * passed through untouched, and anything already containing a `:` or
     * `-` in the right place is left exactly as typed.
     */
    private fun normalizeFastbootCommand(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        val parts = trimmed.split(Regex("\\s+"))
        val head = parts[0].lowercase()
        val rest = parts.drop(1)
        if (rest.isEmpty()) return trimmed // single-word command — nothing to translate

        return when (head) {
            "reboot" -> when (rest[0].lowercase()) {
                "bootloader" -> "reboot-bootloader"
                "recovery" -> "reboot-recovery"
                "fastboot" -> "reboot-fastboot"
                else -> trimmed // unrecognized reboot target — pass through untouched
            }
            "getvar" -> "getvar:${rest.joinToString(" ")}"
            "erase" -> "erase:${rest.joinToString(" ")}"
            "set_active", "set-active" -> "set_active:${rest.joinToString(" ")}"
            "delete-logical-partition" -> "delete-logical-partition:${rest.joinToString(" ")}"
            "create-logical-partition" -> "create-logical-partition:${rest.joinToString(":")}"
            "resize-logical-partition" -> "resize-logical-partition:${rest.joinToString(":")}"
            "snapshot-update" -> "snapshot-update:${rest.joinToString(" ")}"
            "gsi" -> "gsi:${rest.joinToString(" ")}"
            // "oem ..." / "flashing ..." (unlock, lock, get_unlock_ability, ...) are
            // already space-separated on the wire — leave them exactly as typed.
            else -> trimmed
        }
    }
}
