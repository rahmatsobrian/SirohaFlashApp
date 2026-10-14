package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * A from-scratch implementation of Android's fastboot USB protocol, talking
 * directly to the target device over USB host mode via [UsbManager] bulk
 * transfers. This exists because the uploaded project only bundled `qdl`
 * (for EDL/9008 mode) — no `fastboot` executable — and none could be
 * downloaded in the environment this was written in either.
 *
 * Protocol summary (public AOSP spec, no proprietary info involved):
 *   host -> device : raw ASCII command bytes (no null terminator)
 *   device -> host : a response packet whose first 4 bytes are one of
 *       "OKAY" (success, rest of packet is an optional message)
 *       "FAIL" (failure, rest of packet is the error message)
 *       "INFO" (progress message; keep reading for the real terminal reply)
 *       "DATA" (next 8 bytes are a hex size; device is ready to receive
 *               that many raw bytes, used for flash/download)
 *
 * IMPORTANT: this was written against the public protocol description and
 * has not been exercised against real EDL/fastboot hardware in this
 * environment (no device, no USB access here). Treat it as "should be
 * correct" rather than "verified" until you've tried it on a real target.
 */
class FastbootUsbClient(
    private val context: Context,
    private val device: UsbDevice
) {
    companion object {
        private const val MAX_CHUNK = 16 * 1024 // safe bulk-transfer chunk size
        private const val BULK_TIMEOUT_MS = 30_000

        /**
         * Fastboot's USB interface descriptor per AOSP convention: vendor-specific
         * class (0xFF), subclass 0x42, protocol 0x03, with exactly one bulk IN and
         * one bulk OUT endpoint. Checking subclass/protocol (not just endpoint
         * shape) matters because ADB interfaces have the identical class and
         * endpoint shape but protocol 0x01 — without this check the two would be
         * indistinguishable and one could grab the wrong interface.
         */
        fun findFastbootInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
                if (intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 0x03) continue
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

    fun open(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) return false
        val intf = findFastbootInterface(device) ?: return false
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

    sealed class FastbootResponse {
        data class Okay(val message: String) : FastbootResponse()
        data class Fail(val message: String) : FastbootResponse()
        data class Error(val reason: String) : FastbootResponse()
    }

    /** Sends a raw fastboot command and collects INFO lines + the final OKAY/FAIL. */
    fun command(cmd: String, onInfo: (String) -> Unit = {}): FastbootResponse {
        val conn = connection ?: return FastbootResponse.Error("device not open")
        val out = epOut ?: return FastbootResponse.Error("no OUT endpoint")
        val bytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        val sent = conn.bulkTransfer(out, bytes, bytes.size, BULK_TIMEOUT_MS)
        if (sent < 0) return FastbootResponse.Error("write failed for '$cmd'")
        return readUntilTerminal(onInfo)
    }

    /**
     * Full flash sequence for one partition: download the file, then issue
     * `flash:<partition>`. Reports byte-level progress via [onProgress].
     */
    fun flashPartition(
        partition: String,
        file: File,
        onInfo: (String) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): FastbootResponse {
        val dataResult = sendDownload(file.length(), file, onProgress)
        if (dataResult is FastbootResponse.Fail || dataResult is FastbootResponse.Error) return dataResult
        return command("flash:$partition", onInfo)
    }

    /**
     * Downloads a file into the device's staging buffer, then issues a
     * caller-supplied follow-up command against it — used for `boot`
     * (temporary boot without flashing), which needs the exact same DATA
     * handshake as flash but a different terminal command.
     */
    fun downloadThen(
        file: File,
        finalCommand: String,
        onInfo: (String) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): FastbootResponse {
        val dataResult = sendDownload(file.length(), file, onProgress)
        if (dataResult is FastbootResponse.Fail || dataResult is FastbootResponse.Error) return dataResult
        return command(finalCommand, onInfo)
    }

    /**
     * Stages a file into the device's download buffer without any
     * immediate follow-up command — used by Mi Unlock, where the staged
     * `encryptData` blob is consumed implicitly by a separate `oem unlock`
     * call afterward rather than a `flash:`/`boot` command right away.
     */
    fun download(
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): FastbootResponse = sendDownload(file.length(), file, onProgress)

    /**
     * Device -> host transfer for `fetch:<partition>[:<offset>:<size>]`
     * (the reverse of a download). Reply sequence per the AOSP protocol:
     * optional INFO lines, then `DATA<8 hex digits>` announcing how many raw
     * bytes follow, then exactly that many bytes on the bulk-IN endpoint,
     * then a final OKAY/FAIL. The payload is streamed straight into [out]
     * so a whole partition never has to fit in RAM.
     *
     * Reads are capped at the bytes still owed, so a read can never swallow
     * the trailing OKAY packet.
     */
    fun fetch(
        command: String,
        out: OutputStream,
        onInfo: (String) -> Unit = {},
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }
    ): FastbootResponse {
        val conn = connection ?: return FastbootResponse.Error("device not open")
        val outEp = epOut ?: return FastbootResponse.Error("no OUT endpoint")
        val inEp = epIn ?: return FastbootResponse.Error("no IN endpoint")
        val cmdBytes = command.toByteArray(StandardCharsets.US_ASCII)
        if (conn.bulkTransfer(outEp, cmdBytes, cmdBytes.size, BULK_TIMEOUT_MS) < 0) {
            return FastbootResponse.Error("write failed for '$command'")
        }

        var total = -1L
        header@ while (true) {
            val packet = readRawPacket() ?: return FastbootResponse.Error("no response to '$command'")
            if (packet.size < 4) return FastbootResponse.Error("malformed response (too short)")
            val tag = String(packet, 0, 4, StandardCharsets.US_ASCII)
            val rest = if (packet.size > 4) String(packet, 4, packet.size - 4, StandardCharsets.US_ASCII) else ""
            when (tag) {
                "INFO" -> onInfo(rest)
                "DATA" -> {
                    total = rest.trim().toLongOrNull(16)
                        ?: return FastbootResponse.Error("bad DATA size '$rest'")
                    break@header
                }
                "FAIL" -> return FastbootResponse.Fail(rest)
                "OKAY" -> return FastbootResponse.Okay(rest) // nothing to transfer
                else -> return FastbootResponse.Error("unknown response tag '$tag'")
            }
        }

        val buffer = ByteArray(MAX_CHUNK)
        var received = 0L
        var emptyReads = 0
        while (received < total) {
            val toRead = minOf(MAX_CHUNK.toLong(), total - received).toInt()
            val n = conn.bulkTransfer(inEp, buffer, toRead, BULK_TIMEOUT_MS)
            if (n < 0) return FastbootResponse.Error("payload read failed at offset $received of $total")
            if (n == 0) {
                if (++emptyReads > 20) return FastbootResponse.Error("device stopped sending data at offset $received of $total")
                continue
            }
            emptyReads = 0
            out.write(buffer, 0, n)
            received += n
            onProgress(received, total)
        }
        return readUntilTerminal(onInfo)
    }

    /**
     * Like [download], but the payload is produced on the fly by [producer] (which must write
     * exactly [size] bytes into the stream it is given) instead of being read from one file.
     * Used for sparse-image parts that are generated while they are sent.
     */
    fun downloadStream(
        size: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        producer: (java.io.OutputStream) -> Unit
    ): FastbootResponse {
        val conn = connection ?: return FastbootResponse.Error("device not open")
        val out = epOut ?: return FastbootResponse.Error("no OUT endpoint")
        val cmdBytes = "download:%08x".format(size).toByteArray(StandardCharsets.US_ASCII)
        if (conn.bulkTransfer(out, cmdBytes, cmdBytes.size, BULK_TIMEOUT_MS) < 0) {
            return FastbootResponse.Error("write failed for download:")
        }
        val header = readRawPacket() ?: return FastbootResponse.Error("no response to download:")
        val tag = String(header, 0, 4, StandardCharsets.US_ASCII)
        if (tag != "DATA") {
            return if (tag == "FAIL") {
                FastbootResponse.Fail(String(header, 4, header.size - 4, StandardCharsets.US_ASCII))
            } else {
                FastbootResponse.Error("expected DATA, got '$tag'")
            }
        }

        var sent = 0L
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    val n = minOf(left, MAX_CHUNK)
                    val w = conn.bulkTransfer(out, b, o, n, BULK_TIMEOUT_MS)
                    if (w <= 0) throw java.io.IOException("payload write failed at offset $sent")
                    o += w
                    left -= w
                    sent += w
                    onProgress(sent, size)
                }
            }
        }
        try {
            producer(sink)
        } catch (e: java.io.IOException) {
            return FastbootResponse.Error(e.message ?: "I/O error while sending")
        }
        if (sent != size) return FastbootResponse.Error("sent $sent of $size bytes")
        return readUntilTerminal()
    }

    /** Handles the DATA<size> handshake + raw payload write for download:. */
    private fun sendDownload(
        size: Long,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit
    ): FastbootResponse {
        val conn = connection ?: return FastbootResponse.Error("device not open")
        val out = epOut ?: return FastbootResponse.Error("no OUT endpoint")
        val cmdBytes = "download:%08x".format(size).toByteArray(StandardCharsets.US_ASCII)
        if (conn.bulkTransfer(out, cmdBytes, cmdBytes.size, BULK_TIMEOUT_MS) < 0) {
            return FastbootResponse.Error("write failed for download:")
        }
        val header = readRawPacket() ?: return FastbootResponse.Error("no response to download:")
        val tag = String(header, 0, 4, StandardCharsets.US_ASCII)
        if (tag != "DATA") {
            return if (tag == "FAIL") {
                FastbootResponse.Fail(String(header, 4, header.size - 4, StandardCharsets.US_ASCII))
            } else {
                FastbootResponse.Error("expected DATA, got '$tag'")
            }
        }

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(MAX_CHUNK)
            var sent = 0L
            while (sent < size) {
                val toRead = minOf(MAX_CHUNK.toLong(), size - sent).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                val written = conn.bulkTransfer(out, buffer, read, BULK_TIMEOUT_MS)
                if (written < 0) return FastbootResponse.Error("payload write failed at offset $sent")
                sent += written
                onProgress(sent, size)
            }
        }
        return readUntilTerminal()
    }

    private fun readUntilTerminal(onInfo: (String) -> Unit = {}): FastbootResponse {
        while (true) {
            val packet = readRawPacket() ?: return FastbootResponse.Error("read timed out / device disconnected")
            if (packet.size < 4) return FastbootResponse.Error("malformed response (too short)")
            val tag = String(packet, 0, 4, StandardCharsets.US_ASCII)
            val rest = if (packet.size > 4) String(packet, 4, packet.size - 4, StandardCharsets.US_ASCII) else ""
            when (tag) {
                "INFO" -> onInfo(rest)
                "OKAY" -> return FastbootResponse.Okay(rest)
                "FAIL" -> return FastbootResponse.Fail(rest)
                "DATA" -> return FastbootResponse.Error("unexpected DATA outside of a download sequence")
                else -> return FastbootResponse.Error("unknown response tag '$tag'")
            }
        }
    }

    private fun readRawPacket(): ByteArray? {
        val conn = connection ?: return null
        val inEp = epIn ?: return null
        val buffer = ByteArray(inEp.maxPacketSize.coerceAtLeast(64))
        val read = conn.bulkTransfer(inEp, buffer, buffer.size, BULK_TIMEOUT_MS)
        if (read < 0) return null
        return buffer.copyOf(read)
    }
}
