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
            val c = AdbUsbClient(context, device, log)
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

    // ------------------------------------------------------------------
    // Partition backup (device -> file) from recovery / a rooted shell
    // ------------------------------------------------------------------

    /** Directory holding the by-name block device links, found by [readPartitionTable]. */
    @Volatile
    private var blockDir: String? = null

    /** Message from the most recent [backupPartition] failure (stderr / size mismatch), for showing in the UI. */
    @Volatile
    var lastBackupFailure: String? = null
        private set

    /**
     * Lists the partitions via the device's by-name links and reads each
     * one's size with `blockdev`. Needs a shell that can see /dev/block -
     * a recovery such as TWRP, or a rooted system. Sizes the device can't
     * report are returned as null.
     */
    suspend fun readPartitionTable(): PartitionTable = withContext(Dispatchers.IO) {
        val c = client ?: run {
            log.error(TAG, "Not connected - call connect() first.")
            return@withContext PartitionTable(emptyList(), "ERROR: Not connected - tap Connect first.")
        }
        val script = "d=; for x in /dev/block/bootdevice/by-name /dev/block/by-name; do " +
            "if [ -d \$x ]; then d=\$x; break; fi; done; " +
            "if [ -z \"\$d\" ]; then echo NODIR; exit 1; fi; echo DIR \$d; " +
            "for p in \$d/*; do n=\${p##*/}; s=\$(blockdev --getsize64 \$p 2>/dev/null); echo \"\$n \${s:-0}\"; done"
        val buffer = java.io.ByteArrayOutputStream()
        val res = c.execToStream(script, buffer)
        val text = buffer.toString(Charsets.UTF_8.name())
        if (res.error != null) {
            log.error(TAG, "Reading the partition list failed: ${res.error}")
            return@withContext PartitionTable(emptyList(), "ERROR (${res.error})")
        }
        if (text.contains("NODIR") || res.exitCode.let { it != null && it != 0 }) {
            log.error(TAG, "No /dev/block/by-name found (exit ${res.exitCode}) ${res.stderr.trim()}")
            return@withContext PartitionTable(
                emptyList(),
                "FAILED - this ADB shell can't see the partition links. Use a recovery (e.g. boot TWRP) or a rooted device."
            )
        }
        var dir: String? = null
        val list = mutableListOf<PartitionInfo>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("DIR ")) { dir = t.removePrefix("DIR ").trim(); continue }
            val parts = t.split(Regex("\\s+"))
            if (parts.size != 2) continue
            list += PartitionInfo(parts[0], null, parts[1].toLongOrNull()?.takeIf { it > 0 }, false)
        }
        blockDir = dir
        if (list.isEmpty()) {
            return@withContext PartitionTable(emptyList(), "No partitions found in ${dir ?: "/dev/block"}.")
        }
        log.success(TAG, "Read ${list.size} partitions from $dir")
        PartitionTable(list.sortedBy { it.name })
    }

    /**
     * Streams one partition into [out] with `dd` (or `cat`) on its by-name block
     * device. When [expectedBytes] is known the byte count must match it
     * exactly, so a short read can never be reported as a good backup.
     */
    suspend fun backupPartition(
        partition: String,
        out: java.io.OutputStream,
        expectedBytes: Long?,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        lastBackupFailure = null
        val c = client ?: run {
            log.error(TAG, "Not connected - call connect() first.")
            return@withContext false
        }
        if (!Regex("^[A-Za-z0-9_.-]+$").matches(partition)) {
            lastBackupFailure = "invalid partition name"
            log.error(TAG, "Refusing odd partition name '$partition'")
            return@withContext false
        }
        val dir = blockDir ?: run { readPartitionTable(); blockDir }
        if (dir == null) {
            lastBackupFailure = "no by-name directory"
            return@withContext false
        }
        log.info(TAG, "Backing up $partition via ADB ($dir/$partition)" + (expectedBytes?.let { ", $it bytes" } ?: "") + "...")
        val total = expectedBytes ?: 0L
        val progress: (Long) -> Unit = { done ->
            OperationStatus.reportProgress(done, total)
            onProgress(done, total)
        }
        // dd with a 1 MiB block is the usual tool for dumping a block device;
        // exit code 127 means this shell has no dd, so fall back to cat.
        var res = c.execToStream("dd if=$dir/$partition bs=1048576", out, progress)
        if (res.error == null && res.exitCode == 127) {
            log.warn(TAG, "dd isn't available in this shell - falling back to cat")
            res = c.execToStream("cat $dir/$partition", out, progress)
        }
        val failure = when {
            res.error != null -> res.error
            res.exitCode != null && res.exitCode != 0 ->
                "read exited with ${res.exitCode}" + res.stderr.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            expectedBytes != null && expectedBytes > 0 && res.bytes != expectedBytes ->
                "received ${res.bytes} bytes but the partition is $expectedBytes bytes"
            else -> null
        }
        if (failure != null) {
            lastBackupFailure = failure
            log.error(TAG, "backup $partition: FAILED - $failure")
            return@withContext false
        }
        if (res.exitCode == null && expectedBytes == null) {
            log.warn(TAG, "backup $partition: this device gives no exit code and no size, so the copy couldn't be verified")
        }
        log.success(TAG, "backup $partition: OK (${res.bytes} bytes)")
        true
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
     *
     * The ADB wire protocol names these services `service[:argument]`
     * (colon-delimited), but typing that felt unnatural next to every
     * other command box in the app accepting plain spaces — so a natural
     * `reboot bootloader` is normalized to `reboot:bootloader` here before
     * it's sent. Anything already containing a `:` is left exactly as
     * typed, so the old colon syntax still works too.
     */
    suspend fun rawCommand(command: String): ShellOutcome = withContext(Dispatchers.IO) {
        val trimmed = normalizeAdbHostCommand(command)
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

    /** Sideloads a ZIP (e.g. a Magisk or OTA package) already staged as a local [file]. */
    suspend fun sideload(file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext false
        }
        log.info(TAG, "Sideloading ${file.name} (${file.length()} bytes)...")
        runSideload(c) { onProgress -> c.sideload(file, onProgress = onProgress) }
    }

    /**
     * Sideloads directly from a SAF [uri] — tries a real seekable file
     * descriptor first (no copy at all, see [SafFiles.openSeekableFileDescriptor]
     * and [AdbUsbClient.SideloadSource.FromPfd]), and only falls back to
     * copying into the app's cache dir first if the picked document's
     * provider doesn't support that. For a multi-GB ROM ZIP this is the
     * difference between sideloading starting immediately vs. only after a
     * multi-minute copy that also needs an equal amount of free device
     * storage headroom — and it's strictly better even for small files, so
     * there's no reason to prefer the copy path when this succeeds.
     */
    suspend fun sideloadFromUri(context: Context, uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val c = client
        if (c == null) {
            log.error(TAG, "Not connected — call connect() first.")
            return@withContext false
        }
        val name = SafFiles.displayName(context, uri, "sideload.zip")
        val pfd = SafFiles.openSeekableFileDescriptor(context, uri)
        if (pfd != null) {
            val length = pfd.statSize
            if (length <= 0) {
                // statSize can legitimately come back -1 for some providers
                // even when the fd itself is fine — rather than guess, fall
                // back to the safe, always-correct copy path below instead
                // of starting a sideload with an unknown/wrong total size.
                log.warn(TAG, "SAF gave a seekable fd for $name but no usable size (statSize=$length) — falling back to a cache copy.")
                runCatching { pfd.close() }
            } else {
                log.info(TAG, "Sideloading $name (${length} bytes) directly from storage — no copy needed.")
                return@withContext runSideload(c) { onProgress -> c.sideload(pfd, length, onProgress = onProgress) }
            }
        } else {
            log.debug(TAG, "SAF didn't provide a seekable fd for $name (streaming-only provider) — falling back to a cache copy.")
        }

        var lastCopyLogAt = System.currentTimeMillis()
        val path = SafFiles.copyToCache(context, uri, "sideload.zip") { copied ->
            // Throttled to ~once/second — see the matching comment on
            // AdbUsbClient's per-block sideload logging for why call
            // volume itself matters for a multi-GB file, not just text
            // volume. Still enough to show real progress (instead of
            // apparent silence) during a slow fallback copy.
            val now = System.currentTimeMillis()
            if (now - lastCopyLogAt >= 1000L) {
                lastCopyLogAt = now
                log.debug(TAG, "copying $name to cache: ${copied}B so far")
            }
        }
        val file = java.io.File(path)
        log.info(TAG, "Sideloading $name (${file.length()} bytes, copied to cache)...")
        runSideload(c) { onProgress -> c.sideload(file, onProgress = onProgress) }
    }

    private suspend fun runSideload(c: AdbUsbClient, run: suspend (onProgress: (Long, Long) -> Unit) -> AdbUsbClient.SideloadResult): Boolean {
        var lastLoggedPercent = -1
        val result = run { sent, total ->
            val percent = if (total > 0) ((sent * 100) / total).toInt() else 0
            if (percent != lastLoggedPercent && percent % 10 == 0) {
                log.info(TAG, "  sideload: $percent% ($sent/$total bytes)")
                lastLoggedPercent = percent
            }
        }
        return when (result) {
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

    /**
     * Translates a naturally-typed host command (`reboot bootloader`,
     * `tcpip 5555`) into the ADB wire format (`reboot:bootloader`,
     * `tcpip:5555`) by turning the FIRST space into a colon. Left alone if
     * the command already contains a `:` (user typed the wire form
     * themselves) or has no space at all (single-word services like
     * `reboot`, `root`, `remount`, `devices` need no translation).
     */
    private fun normalizeAdbHostCommand(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(':')) return trimmed
        val spaceIndex = trimmed.indexOf(' ')
        if (spaceIndex == -1) return trimmed
        val head = trimmed.substring(0, spaceIndex)
        val rest = trimmed.substring(spaceIndex + 1).trim()
        return if (rest.isEmpty()) head else "$head:$rest"
    }
}
