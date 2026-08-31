package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.siroha.flashtool.data.LogRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * A from-scratch client for Android's ADB-over-USB wire protocol: 24-byte
 * message headers (command/arg0/arg1/data_length/data_crc32/magic), the
 * CNXN/AUTH handshake, and OPEN/WRTE/OKAY/CLSE stream framing for running
 * shell commands. Like [FastbootUsbClient], this exists because no adb
 * binary was available to bundle, and it has not been exercised against
 * real hardware in the environment that built it — see [AdbKeyManager]'s
 * doc comment for the specific piece most likely to need a fix.
 */
class AdbUsbClient(
    private val context: Context,
    private val device: UsbDevice,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "AdbUsb"
        private const val A_CNXN = 0x4e584e43 // "CNXN" little-endian as int
        private const val A_AUTH = 0x48545541 // "AUTH"
        private const val A_OPEN = 0x4e45504f // "OPEN"
        private const val A_OKAY = 0x59414b4f // "OKAY"
        private const val A_CLSE = 0x45534c43 // "CLSE"
        private const val A_WRTE = 0x45545257 // "WRTE"

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 1024 * 1024

        private const val BULK_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_TIMEOUT_MS = 30_000

        // How long to wait for the NEXT block request (or DONEDONE/FAILFAIL)
        // during sideload. Deliberately much longer than BULK_TIMEOUT_MS:
        // once all data has been sent, the device runs the actual installer
        // script (patching/flashing a boot image, etc.) before it can
        // report completion, and that legitimately takes well over 15s on
        // real devices — this must not be mistaken for a dead connection.
        // 30 minutes (not 5): a full ROM zip or a large sideload package can
        // genuinely run that long on slower/older targets.
        private const val SIDELOAD_SCRIPT_TIMEOUT_MS = 30 * 60_000

        // Android's USB host bulkTransfer() is well known to be unreliable
        // (silent truncation or outright failure) for single calls above
        // roughly this size on a meaningful fraction of real devices/kernel
        // USB host-controller drivers — the safe, widely-used workaround is
        // to always split any bulk transfer, in EITHER direction, into
        // chunks no larger than this. [readMessage]'s payload loop already
        // did this correctly; [sendMessage]'s data write did NOT — it used
        // to hand the full block (up to 64KB for sideload) to a single
        // bulkTransfer() call. That asymmetry is the leading suspect behind
        // "USB write failed sending block N — connection/endpoint likely
        // dropped" appearing partway through large sideloads (see
        // [sideload]'s doc): a large single-shot bulk WRITE has far more
        // room to go wrong than a same-size read split into safe pieces.
        private const val MAX_BULK_CHUNK = 16 * 1024

        // A single bulkTransfer() call occasionally fails transiently even
        // with correctly-sized chunks (a busy hub, a marginal cable, a
        // kernel URB queue hiccup) without the underlying connection
        // actually being dead. Retrying the same chunk a few times before
        // giving up on the whole message avoids aborting a multi-GB
        // sideload over one blip — this does NOT apply to the handshake
        // (CNXN/AUTH), only to already-established stream traffic, since a
        // handshake failure needs to surface immediately rather than retry
        // silently.
        private const val CHUNK_RETRY_COUNT = 3
        private const val CHUNK_RETRY_DELAY_MS = 150L

        /** ADB's interface descriptor: vendor-specific class, subclass 0x42, protocol 0x01 (fastboot is 0x03). */
        fun findAdbInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
                if (intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 0x01) continue
                var bulkIn = 0
                var bulkOut = 0
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn++ else bulkOut++
                }
                if (bulkIn == 1 && bulkOut == 1) return intf
            }
            return null
        }
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var nextLocalId = 1

    sealed class ConnectResult {
        object Connected : ConnectResult()
        /** Device is prompting the user with "Allow USB debugging?" — call again after they accept. */
        object AwaitingUserAuthorization : ConnectResult()
        data class Error(val reason: String) : ConnectResult()
    }

    fun open(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) return false
        val intf = findAdbInterface(device) ?: return false
        val conn = manager.openDevice(device) ?: return false
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return false
        }
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) {
            conn.releaseInterface(intf)
            conn.close()
            return false
        }
        connection = conn
        usbInterface = intf
        return true
    }

    fun close() {
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
        usbInterface = null
        epIn = null
        epOut = null
    }

    /**
     * Runs the CNXN/AUTH handshake once. On first-ever pairing with a given
     * device this will typically return [ConnectResult.AwaitingUserAuthorization]
     * after sending our public key — call [handshake] again once the user
     * taps "Allow" on the target device's dialog.
     */
    fun handshake(keyPair: AdbKeyManager.AdbKeyPair): ConnectResult {
        // Advertise shell_v2 support (the modern shell,v2,... service, which
        // frames stdout/stderr/exit-code separately) so a proper exit code
        // is available for detecting real command success/failure — the
        // legacy `shell:` service has no such signal at all, which is why
        // a failed command with no error text used to look identical to a
        // successful one with no output.
        sendMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray())
        val first = readMessage() ?: return ConnectResult.Error("no response to CNXN")

        return when (first.command) {
            A_CNXN -> { recordFeatures(first.data); ConnectResult.Connected }
            A_AUTH -> handleAuth(first, keyPair)
            else -> ConnectResult.Error("unexpected response 0x${first.command.toString(16)} to CNXN")
        }
    }

    /** True once [handshake] has completed and the device's own CNXN reply advertised `shell_v2` support. */
    var supportsShellV2: Boolean = false
        private set

    private fun recordFeatures(cnxnPayload: ByteArray) {
        val text = String(cnxnPayload, Charsets.UTF_8)
        supportsShellV2 = text.contains("shell_v2")
    }

    private fun handleAuth(authMsg: AdbMessage, keyPair: AdbKeyManager.AdbKeyPair): ConnectResult {
        if (authMsg.arg0 != AUTH_TOKEN) return ConnectResult.Error("unexpected AUTH arg0=${authMsg.arg0}")
        val token = authMsg.data

        val signature = AdbKeyManager.signToken(keyPair.private, token)
        sendMessage(A_AUTH, AUTH_SIGNATURE, 0, signature)
        val afterSig = readMessage() ?: return ConnectResult.Error("no response to AUTH SIGNATURE")
        if (afterSig.command == A_CNXN) { recordFeatures(afterSig.data); return ConnectResult.Connected }
        if (afterSig.command != A_AUTH) return ConnectResult.Error("unexpected response 0x${afterSig.command.toString(16)} to SIGNATURE")

        // Device didn't recognize the signature (first-time pairing) — send our
        // public key. This triggers the "Allow USB debugging?" dialog on the
        // target; the device will not send CNXN until the user accepts it, so
        // we treat "no further CNXN yet" as a normal, expected pending state.
        val pubKeyPayload = AdbKeyManager.encodeAdbPublicKey(keyPair.public)
        sendMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubKeyPayload)
        val afterKey = readMessage(timeoutMs = HANDSHAKE_TIMEOUT_MS)
            ?: return ConnectResult.AwaitingUserAuthorization
        return if (afterKey.command == A_CNXN) {
            recordFeatures(afterKey.data)
            ConnectResult.Connected
        } else {
            ConnectResult.AwaitingUserAuthorization
        }
    }

    /**
     * Opens an ADB stream to [service] (e.g. "shell:content insert ..." or
     * a host-level service like "reboot:"), collects every WRTE payload
     * until the device closes the stream, and returns the concatenated
     * output. This is the legacy framing with no structured exit code —
     * prefer [runShellV2] for shell commands when [supportsShellV2] is true.
     */
    fun runService(service: String): String {
        val localId = nextLocalId++
        sendMessage(A_OPEN, localId, 0, "$service\u0000".toByteArray())

        var remoteId = 0
        val output = StringBuilder()
        while (true) {
            val msg = readMessage() ?: break
            when (msg.command) {
                A_OKAY -> {
                    if (remoteId == 0) remoteId = msg.arg0
                }
                A_WRTE -> {
                    remoteId = msg.arg0
                    output.append(String(msg.data, Charsets.UTF_8))
                    sendMessage(A_OKAY, localId, remoteId)
                }
                A_CLSE -> {
                    if (remoteId != 0) sendMessage(A_OKAY, localId, remoteId)
                    break
                }
                else -> { /* ignore unrelated traffic */ }
            }
        }
        return output.toString()
    }

    data class ShellV2Result(val exitCode: Int?, val stdout: String, val stderr: String)

    /**
     * Runs a command via the `shell,v2,raw:` service, which frames stdout,
     * stderr, and — critically — an actual exit code as separate typed
     * sub-packets within each WRTE payload (kIdStdout=1, kIdStderr=2,
     * kIdExit=3), instead of the legacy `shell:` service's single
     * undifferentiated byte stream with no completion signal at all. This
     * is what makes it possible to correctly tell "ran fine with no output"
     * apart from "failed with no output" — the legacy service genuinely
     * cannot distinguish the two.
     */
    fun runShellV2(command: String): ShellV2Result {
        val localId = nextLocalId++
        sendMessage(A_OPEN, localId, 0, "shell,v2,raw:$command\u0000".toByteArray())

        var remoteId = 0
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        val pending = java.io.ByteArrayOutputStream() // carries a sub-frame header/body split across WRTE boundaries

        while (true) {
            val msg = readMessage() ?: break
            when (msg.command) {
                A_OKAY -> if (remoteId == 0) remoteId = msg.arg0
                A_WRTE -> {
                    remoteId = msg.arg0
                    pending.write(msg.data)
                    val buffer = pending.toByteArray()
                    var offset = 0
                    while (buffer.size - offset >= 5) {
                        val id = buffer[offset].toInt() and 0xFF
                        val length = ByteBuffer.wrap(buffer, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (buffer.size - offset - 5 < length) break // frame body not fully received yet
                        val body = buffer.copyOfRange(offset + 5, offset + 5 + length)
                        when (id) {
                            1 -> stdout.append(String(body, Charsets.UTF_8)) // kIdStdout
                            2 -> stderr.append(String(body, Charsets.UTF_8)) // kIdStderr
                            3 -> exitCode = if (body.isNotEmpty()) body[0].toInt() and 0xFF else null // kIdExit
                            // kIdStdin(0)/kIdCloseStdin(4)/kIdWindowSizeChange(5)/kIdInvalid(255): not relevant to a one-shot command
                        }
                        offset += 5 + length
                    }
                    pending.reset()
                    if (offset < buffer.size) pending.write(buffer, offset, buffer.size - offset)
                    sendMessage(A_OKAY, localId, remoteId)
                }
                A_CLSE -> {
                    if (remoteId != 0) sendMessage(A_OKAY, localId, remoteId)
                    break
                }
                else -> { /* ignore unrelated traffic */ }
            }
        }
        return ShellV2Result(exitCode, stdout.toString(), stderr.toString())
    }

    sealed class SideloadResult {
        object Success : SideloadResult()
        data class Rejected(val reason: String) : SideloadResult()
        data class Error(val reason: String) : SideloadResult()
    }

    /**
     * Random-access byte source for [sideload] — lets it serve minadbd's
     * (possibly out-of-order — see that function's doc) block requests
     * either from a real [java.io.File] (the cache-copy path) or directly
     * from a SAF [android.os.ParcelFileDescriptor] with no copy at all (see
     * [SafFiles.openSeekableFileDescriptor]). Skipping the copy matters
     * most for exactly the case that used to fail: a multi-GB ROM ZIP,
     * where copying first costs both the time AND another multi-GB of
     * device storage headroom before the actual sideload can even begin.
     */
    sealed class SideloadSource : java.io.Closeable {
        abstract val length: Long
        abstract fun readFullyAt(offset: Long, buffer: ByteArray, length: Int)

        class FromFile(file: java.io.File) : SideloadSource() {
            private val raf = java.io.RandomAccessFile(file, "r")
            override val length: Long = file.length()
            override fun readFullyAt(offset: Long, buffer: ByteArray, length: Int) {
                raf.seek(offset)
                raf.readFully(buffer, 0, length)
            }
            override fun close() = raf.close()
        }

        class FromPfd(private val pfd: android.os.ParcelFileDescriptor, override val length: Long) : SideloadSource() {
            private val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
            override fun readFullyAt(offset: Long, buffer: ByteArray, length: Int) {
                val buf = java.nio.ByteBuffer.wrap(buffer, 0, length)
                var readTotal = 0
                while (readTotal < length) {
                    val n = channel.read(buf, offset + readTotal)
                    if (n < 0) throw java.io.EOFException("unexpected EOF reading SAF fd at offset ${offset + readTotal}")
                    readTotal += n
                }
            }
            override fun close() = runCatching { channel.close() }.let { pfd.close() }
        }
    }

    /** Convenience overload for the cache-copy path — see [SideloadSource.FromFile]. */
    fun sideload(
        file: java.io.File,
        blockSize: Int = 64 * 1024,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): SideloadResult = SideloadSource.FromFile(file).use { sideload(it, blockSize, onProgress) }

    /** Convenience overload for the no-copy SAF path — see [SideloadSource.FromPfd]. */
    fun sideload(
        pfd: android.os.ParcelFileDescriptor,
        length: Long,
        blockSize: Int = 64 * 1024,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): SideloadResult = SideloadSource.FromPfd(pfd, length).use { sideload(it, blockSize, onProgress) }

    /**
     * Implements ADB's `sideload-host:<size>:<blockSize>` protocol: opens
     * the stream, then repeatedly answers the device's block requests until
     * it reports "DONEDONE" (success) or "FAILFAIL" (failure). This is the
     * protocol modern `adb sideload some.zip` actually uses — distinct from
     * a plain `shell:` command in that the DEVICE drives the exchange by
     * requesting specific blocks by number, not just streaming output to
     * the host. (An older `sideload:<size>` service exists too, with a
     * fixed block size and no negotiation, but is only what very old
     * pre-Lollipop minadbd builds expect.)
     *
     * Like the rest of this from-scratch ADB implementation, this exact
     * request/response shape is reconstructed from public knowledge of
     * AOSP's minadbd sideload handler rather than tested against a real
     * recovery — see README for the same caveat that applies to the
     * handshake/key encoding.
     */
    private fun sideload(
        source: SideloadSource,
        blockSize: Int = 64 * 1024,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): SideloadResult {
        val localId = nextLocalId++
        val total = source.length
        val totalBlocksEstimate = if (blockSize > 0) (total + blockSize - 1) / blockSize else 0
        log.debug(TAG, "sideload starting: total=${total}B blockSize=${blockSize}B (~$totalBlocksEstimate blocks) localId=$localId")
        // "sideload-host:<size>:<blockSize>" — the protocol real `adb sideload`
        // actually sends on any reasonably modern host/device pair. The
        // legacy bare "sideload:<size>" service (no block-size negotiation,
        // fixed 64KiB) that this previously sent is what very old (pre-
        // Lollipop-era) minadbd builds expect; on a modern minadbd that
        // doesn't recognize/handle it, an unrecognized OPEN service can
        // crash or wedge minadbd outright rather than cleanly rejecting
        // (CLSE) it — which would explain BOTH symptoms together: the
        // client seeing an instant, sub-50ms "no response" (the read side
        // dying because the process on the other end died, not a timeout),
        // and TWRP itself freezing at the exact moment sideload was
        // attempted. Unverified against this exact minadbd build (same
        // hardware-testing caveat as the rest of this client — see
        // README), but this is what a genuine `adb sideload` would send.
        if (!sendMessage(A_OPEN, localId, 0, "sideload-host:$total:$blockSize\u0000".toByteArray(), label = "OPEN sideload-host")) {
            return SideloadResult.Error("USB write failed sending OPEN sideload-host:$total:$blockSize — connection/endpoint likely dropped; try reconnecting")
        }

        var remoteId = 0
        val first = readMessage() ?: return SideloadResult.Error("no response opening sideload stream")
        when (first.command) {
            A_OKAY -> {
                remoteId = first.arg0
                log.debug(TAG, "sideload stream opened: localId=$localId remoteId=$remoteId")
            }
            A_CLSE -> return SideloadResult.Rejected("device closed the sideload stream immediately — is it actually in recovery/sideload mode?")
            else -> return SideloadResult.Error("unexpected response 0x${first.command.toString(16)} opening sideload")
        }

        val startedAt = System.currentTimeMillis()
        var lastBlockAt = startedAt
        var blocksServed = 0L
        var bytesServed = 0L
        var lastProgressLogAt = startedAt

        run {
            while (true) {
                // Long timeout, not the default BULK_TIMEOUT_MS: between
                // block requests the device can go quiet for a while
                // running the actual installer script (mounting
                // partitions, patching the boot image, flashing it, etc —
                // real work, not "connection lost"). This is exactly what
                // was happening when this used to give up after
                // BULK_TIMEOUT_MS: the last block was fully received and
                // TWRP moved on to running Magisk's installer — which
                // finished successfully on-device — while this loop had
                // already bailed out 15s in, well before the script (and
                // the DONEDONE it sends once done) had a chance to
                // complete.
                val request = readMessage(timeoutMs = SIDELOAD_SCRIPT_TIMEOUT_MS)
                    ?: return SideloadResult.Error(
                        "connection lost waiting for block request — last successful block was ${System.currentTimeMillis() - lastBlockAt}ms ago " +
                            "($blocksServed/$totalBlocksEstimate blocks, ${bytesServed}/${total}B served)"
                    )
                when (request.command) {
                    A_CLSE -> return SideloadResult.Error("device closed the stream unexpectedly after $blocksServed block(s) (${bytesServed}B)")
                    A_WRTE -> {
                        remoteId = request.arg0
                        sendMessage(A_OKAY, localId, remoteId, label = "block-request ack") // ack the request packet itself

                        val text = String(request.data, Charsets.US_ASCII).trimEnd('\u0000')
                        when {
                            text.startsWith("DONEDONE") -> {
                                val elapsedMs = System.currentTimeMillis() - startedAt
                                log.success(
                                    TAG,
                                    "sideload DONEDONE: $blocksServed block(s), ${bytesServed}B in ${elapsedMs}ms " +
                                        "(${if (elapsedMs > 0) bytesServed * 1000 / elapsedMs / 1024 else 0} KB/s avg)"
                                )
                                sendMessage(A_CLSE, localId, remoteId, label = "final CLSE")
                                return SideloadResult.Success
                            }
                            text.startsWith("FAILFAIL") -> {
                                log.error(TAG, "sideload FAILFAIL after $blocksServed block(s), ${bytesServed}B")
                                sendMessage(A_CLSE, localId, remoteId, label = "FAILFAIL CLSE")
                                return SideloadResult.Rejected("device reported a failure partway through")
                            }
                            else -> {
                                val blockNum = text.take(8).trim().toLongOrNull()
                                    ?: return SideloadResult.Error("malformed block request: '$text' (raw bytes: ${request.data.joinToString(",") { (it.toInt() and 0xFF).toString() }})")
                                val offset = blockNum * blockSize
                                if (offset >= total) return SideloadResult.Error("device requested block $blockNum (offset $offset) past end of file ($total B) — $blocksServed block(s) served so far")
                                val chunkSize = minOf(blockSize.toLong(), total - offset).toInt()
                                val chunk = ByteArray(chunkSize)
                                val readStartedAt = System.currentTimeMillis()
                                source.readFullyAt(offset, chunk, chunkSize)
                                val fileReadMs = System.currentTimeMillis() - readStartedAt

                                val writeStartedAt = System.currentTimeMillis()
                                if (!sendMessage(A_WRTE, localId, remoteId, chunk, label = "sideload block $blockNum")) {
                                    val elapsedMs = System.currentTimeMillis() - startedAt
                                    return SideloadResult.Error(
                                        "USB write failed sending block $blockNum (offset $offset, ${chunkSize}B) — connection/endpoint likely dropped. " +
                                            "Context: $blocksServed/$totalBlocksEstimate blocks served (${bytesServed}B/${total}B) over ${elapsedMs}ms before this failure; " +
                                            "see DEBUG-level log entries above for the exact chunk/attempt that failed."
                                    )
                                }
                                val writeMs = System.currentTimeMillis() - writeStartedAt
                                onProgress(offset + chunkSize, total)

                                // Flow control: wait for the device's OKAY of THIS
                                // data write before reading its next block request.
                                val ackStartedAt = System.currentTimeMillis()
                                val ack = readMessage() ?: return SideloadResult.Error(
                                    "no ack for block $blockNum — device stopped responding after the write itself succeeded " +
                                        "($blocksServed/$totalBlocksEstimate blocks served before this)"
                                )
                                if (ack.command != A_OKAY) {
                                    return SideloadResult.Error(
                                        "expected OKAY ack for block $blockNum, got 0x${ack.command.toString(16)} — " +
                                            "$blocksServed/$totalBlocksEstimate blocks served before this"
                                    )
                                }
                                val ackMs = System.currentTimeMillis() - ackStartedAt

                                blocksServed++
                                bytesServed += chunkSize
                                lastBlockAt = System.currentTimeMillis()
                                // Throttled to roughly once/second (not once
                                // per block — see [bulkWriteChunked]'s doc on
                                // why call volume itself matters here) at
                                // DEBUG level, hidden from the log by default
                                // (LogsScreen's "showDebug" toggle). Still
                                // enough to trace a failure back to roughly
                                // which block/offset/timing region it
                                // happened in without one log call per block.
                                if (lastBlockAt - lastProgressLogAt >= 1000L) {
                                    lastProgressLogAt = lastBlockAt
                                    val elapsedMs = lastBlockAt - startedAt
                                    val kbPerSec = if (elapsedMs > 0) bytesServed * 1000 / elapsedMs / 1024 else 0
                                    log.debug(
                                        TAG,
                                        "sideload progress: block $blockNum ($blocksServed/$totalBlocksEstimate), " +
                                            "${bytesServed}B/${total}B, ${kbPerSec}KB/s avg, lastBlock: readFile=${fileReadMs}ms write=${writeMs}ms ack=${ackMs}ms"
                                    )
                                }
                            }
                        }
                    }
                    else -> log.debug(TAG, "sideload: ignoring unrelated command 0x${request.command.toString(16)} on this stream")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return SideloadResult.Error("sideload loop exited unexpectedly after $blocksServed block(s)")
    }

    // ---- low-level framing ----

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /**
     * Writes [data] to [out], split into [MAX_BULK_CHUNK]-sized pieces (see
     * that constant's doc for why this matters) with up to
     * [CHUNK_RETRY_COUNT] retries per chunk on a transient short-write
     * before giving up on the whole call. [label] is purely for the debug
     * log — e.g. "header" or "block 1328 data" — so a failure deep in a
     * multi-GB sideload can be traced back to exactly which chunk of which
     * message it was, not just "a write failed somewhere".
     */
    private fun bulkWriteChunked(conn: UsbDeviceConnection, out: UsbEndpoint, data: ByteArray, label: String): Boolean {
        if (data.isEmpty()) return true
        var offset = 0
        var chunkIndex = 0
        val totalChunks = (data.size + MAX_BULK_CHUNK - 1) / MAX_BULK_CHUNK
        while (offset < data.size) {
            val chunkLen = minOf(MAX_BULK_CHUNK, data.size - offset)
            val chunk = if (offset == 0 && chunkLen == data.size) data else data.copyOfRange(offset, offset + chunkLen)
            var sent = -1
            var lastAttemptMs = 0L
            for (attempt in 1..CHUNK_RETRY_COUNT) {
                val startedAt = System.currentTimeMillis()
                sent = conn.bulkTransfer(out, chunk, chunkLen, BULK_TIMEOUT_MS)
                lastAttemptMs = System.currentTimeMillis() - startedAt
                if (sent == chunkLen) {
                    // Deliberately NOT logged on the ordinary first-attempt
                    // success path — see class doc: a multi-GB sideload
                    // means tens of thousands of chunks, and even a cheap
                    // per-call log entry adds up at that volume. Only the
                    // interesting case (it took a retry to succeed) is
                    // logged; [sideload] separately logs one throttled
                    // summary line per block for the normal-progress case.
                    if (attempt > 1) {
                        log.debug(TAG, "write OK: $label chunk ${chunkIndex + 1}/$totalChunks recovered on retry $attempt/$CHUNK_RETRY_COUNT (${lastAttemptMs}ms)")
                    }
                    break
                }
                log.warn(
                    TAG,
                    "write short/failed: $label chunk ${chunkIndex + 1}/$totalChunks — requested ${chunkLen}B, bulkTransfer returned $sent " +
                        "(attempt $attempt/$CHUNK_RETRY_COUNT, ${lastAttemptMs}ms, endpoint=${out.address})"
                )
                if (attempt < CHUNK_RETRY_COUNT) Thread.sleep(CHUNK_RETRY_DELAY_MS)
            }
            if (sent != chunkLen) {
                log.error(
                    TAG,
                    "write failed permanently: $label chunk ${chunkIndex + 1}/$totalChunks after $CHUNK_RETRY_COUNT attempts — " +
                        "requested ${chunkLen}B at offset $offset/${data.size}, last bulkTransfer() result was $sent. " +
                        "This is the exact point of failure — endpoint=${out.address}, device=${device.deviceName}."
                )
                return false
            }
            offset += chunkLen
            chunkIndex++
        }
        return true
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0), label: String = "message"): Boolean {
        val conn = connection ?: run {
            log.error(TAG, "sendMessage($label): no connection open")
            return false
        }
        val out = epOut ?: run {
            log.error(TAG, "sendMessage($label): no bulk-OUT endpoint claimed")
            return false
        }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(checksum(data))
        header.putInt(command.inv())
        if (!bulkWriteChunked(conn, out, header.array(), "$label header")) return false
        if (data.isNotEmpty()) {
            if (!bulkWriteChunked(conn, out, data, "$label data")) return false
        }
        return true
    }

    private fun readMessage(timeoutMs: Int = BULK_TIMEOUT_MS): AdbMessage? {
        val conn = connection ?: return null
        val inEp = epIn ?: return null
        val headerBuf = ByteArray(24)
        val headerRead = conn.bulkTransfer(inEp, headerBuf, 24, timeoutMs)
        if (headerRead != 24) return null
        val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val dataLength = bb.int
        bb.int // data_crc32, unchecked on read
        bb.int // magic, unchecked on read

        val data = if (dataLength > 0) {
            val payload = ByteArray(dataLength)
            var offset = 0
            while (offset < dataLength) {
                val chunk = ByteArray(minOf(16 * 1024, dataLength - offset))
                val read = conn.bulkTransfer(inEp, chunk, chunk.size, timeoutMs)
                if (read <= 0) return null
                chunk.copyInto(payload, offset, 0, read)
                offset += read
            }
            payload
        } else {
            ByteArray(0)
        }
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        return sum
    }
}
