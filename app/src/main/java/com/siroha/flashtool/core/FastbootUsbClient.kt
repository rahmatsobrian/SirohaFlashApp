package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
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
         * Heuristic fastboot interface match: a vendor-specific interface
         * (class 0xFF) exposing exactly one bulk IN and one bulk OUT
         * endpoint. This matches how virtually all fastboot-mode USB
         * gadgets enumerate, without hardcoding a single vendor/product ID
         * (target phones vary widely).
         */
        fun findFastbootInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
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
