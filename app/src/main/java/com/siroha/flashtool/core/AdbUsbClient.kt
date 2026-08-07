package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
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
    private val device: UsbDevice
) {
    companion object {
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
        sendMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::siroha-flash-tool\u0000".toByteArray())
        val first = readMessage() ?: return ConnectResult.Error("no response to CNXN")

        return when (first.command) {
            A_CNXN -> ConnectResult.Connected
            A_AUTH -> handleAuth(first, keyPair)
            else -> ConnectResult.Error("unexpected response 0x${first.command.toString(16)} to CNXN")
        }
    }

    private fun handleAuth(authMsg: AdbMessage, keyPair: AdbKeyManager.AdbKeyPair): ConnectResult {
        if (authMsg.arg0 != AUTH_TOKEN) return ConnectResult.Error("unexpected AUTH arg0=${authMsg.arg0}")
        val token = authMsg.data

        val signature = AdbKeyManager.signToken(keyPair.private, token)
        sendMessage(A_AUTH, AUTH_SIGNATURE, 0, signature)
        val afterSig = readMessage() ?: return ConnectResult.Error("no response to AUTH SIGNATURE")
        if (afterSig.command == A_CNXN) return ConnectResult.Connected
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
            ConnectResult.Connected
        } else {
            ConnectResult.AwaitingUserAuthorization
        }
    }

    /**
     * Opens an ADB stream to [service] (e.g. "shell:content insert ..."),
     * collects every WRTE payload until the device closes the stream, and
     * returns the concatenated output. This does NOT implement the
     * "sideload:<size>" flow-controlled transfer protocol — that's a
     * separate, more complex chunked handshake and is intentionally out of
     * scope here (see README).
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

    // ---- low-level framing ----

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val conn = connection ?: return
        val out = epOut ?: return
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(checksum(data))
        header.putInt(command.inv())
        conn.bulkTransfer(out, header.array(), 24, BULK_TIMEOUT_MS)
        if (data.isNotEmpty()) {
            conn.bulkTransfer(out, data, data.size, BULK_TIMEOUT_MS)
        }
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
