package com.siroha.flashtool.core

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Kotlin reimplementation of the "USB bridge" mechanism found (by reverse
 * engineering a well-known third-party EDL tool's bundled `.so` files — not
 * by copying any of that tool's own code) behind why it can flash EDL/9008
 * devices without root or Shizuku. Short version of the architecture, since
 * the details matter for how much to trust this file:
 *
 *   qdl (via libqdl.so)
 *      │  calls the *standard*, public libusb_init()/get_device_list()/open()
 *      ▼
 *   libusb.so   — upstream libusb, patched internally
 *      │  when it needs a USB fd it can't open itself (no root/Shizuku),
 *      │  it calls two internal functions instead:
 *      │    usb_bridge::get_devices()
 *      │    usb_bridge::get_fd(std::string const& path)
 *      ▼
 *   libusb_bridge.so
 *      │  socket() + connect() + recvmsg() to an ABSTRACT Unix domain
 *      │  socket literally named "adbify_usb_bridge" (confirmed by pulling
 *      │  that exact string out of the binary) — no sendmsg/open/ioctl of
 *      │  its own, consistent with a client that only ever *receives*.
 *      ▼
 *   >>> THIS is the missing "server" side, and it's what this file is. <<<
 *      Whatever answers that socket is expected to already hold an open,
 *      permitted UsbDeviceConnection (from Android's normal USB-Host
 *      permission flow) and hand its fd back over the socket using
 *      SCM_RIGHTS — which on Android is exposed with zero JNI/native code
 *      needed, via LocalSocket.setFileDescriptorsForSend().
 *
 * IMPORTANT — what is and isn't confirmed:
 *   - The abstract socket NAME ("adbify_usb_bridge") is confirmed: it's a
 *     literal string pulled directly out of libusb_bridge.so.
 *   - The overall shape (Unix socket + recvmsg + SCM_RIGHTS, no open/ioctl
 *     in the client) is confirmed from the exact set of imported symbols in
 *     that library, cross-checked against libusb.so's own relocations,
 *     which do resolve get_devices()/get_fd() from it.
 *   - The WIRE FORMAT is now confirmed by disassembling get_devices() and
 *     get_fd() directly (ARM64, via a from-scratch Python decoder, since no
 *     objdump/binutils were available where this analysis ran): every
 *     request opens with a 2-byte BIG-ENDIAN opcode (0x0000 = get_devices,
 *     0x0001 = get_fd — confirmed by the literal `rev16` byte-swap
 *     instruction on the length field, and by get_fd() writing the raw
 *     bytes `00 01` before its path argument), and both the get_devices()
 *     reply and the get_fd() path argument use length-prefixed framing
 *     identical to java.io.DataOutputStream.writeUTF()/readUTF() — 2-byte
 *     BE length, then that many UTF-8 bytes. See [handleClient] for exactly
 *     how each opcode is handled now that this is settled rather than
 *     guessed.
 *
 * This was NOT confirmed against real EDL hardware end-to-end (no such
 * device was available where the disassembly work happened) — only against
 * the reverse-engineered binaries' own instructions. Treat a real flash
 * through this path as newly-plausible, not proven, until it's been run
 * against real hardware.
 *
 * History, for whoever picks this up next: an earlier version of this file
 * guessed "an empty write means get_devices()" — that left qdl stuck
 * forever at "Waiting for EDL device", because the real client always
 * sends the same 2 opcode bytes up front regardless of which method it's
 * calling, so no request was ever actually empty and it always fell into
 * the get_fd() branch, which then failed to read a valid path out of what
 * were actually raw opcode bytes.
 */
class UsbBridgeServer(private val context: Context, private val log: LogRepository) {

    companion object {
        private const val TAG = "UsbBridge"

        /** Confirmed literal string from the reference tool's libusb_bridge.so. */
        const val SOCKET_NAME = "adbify_usb_bridge"
    }

    private var serverSocket: LocalServerSocket? = null
    private var acceptJob: Job? = null
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null

    /**
     * Opens [targetDevice] (USB permission must already be granted — see
     * [UsbDeviceHelper.requestPermission]) and starts answering the abstract
     * socket in the background. Returns false if the device or the socket
     * itself couldn't be opened; callers should treat that as "the no-root
     * bridge path isn't usable right now", not retry in a loop.
     */
    fun start(scope: CoroutineScope, targetDevice: UsbDevice): Boolean {
        stop()
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(targetDevice)
        if (conn == null) {
            log.error(TAG, "UsbManager.openDevice() returned null for ${targetDevice.deviceName}")
            return false
        }
        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: IOException) {
            log.error(TAG, "Could not bind abstract socket '$SOCKET_NAME': ${e.message}")
            conn.close()
            return false
        }
        connection = conn
        device = targetDevice
        serverSocket = server
        log.success(TAG, "USB bridge listening on '$SOCKET_NAME' for ${targetDevice.deviceName} (${String.format("%04x", targetDevice.vendorId)}:${String.format("%04x", targetDevice.productId)})")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val incoming = try {
                    server.accept()
                } catch (e: IOException) {
                    break // server socket was closed by stop() -- normal shutdown
                }
                handleClient(incoming)
            }
        }
        return true
    }

    private fun handleClient(client: LocalSocket) {
        try {
            client.soTimeout = 5000
            val input = java.io.DataInputStream(client.inputStream)
            val output = java.io.DataOutputStream(client.outputStream)

            // Confirmed via ARM64 disassembly of the reference tool's
            // libusb_bridge.so (get_devices()/get_fd() at 0x120d0/0x12790):
            // every request starts with a 2-byte BIG-ENDIAN opcode —
            // 0x0000 for get_devices(), 0x0001 for get_fd(path) — written
            // with a plain 2-byte store, not a length-prefixed string. This
            // replaced an earlier "empty write = get_devices()" guess that
            // left qdl stuck forever at "Waiting for EDL device": that guess
            // was checking whether any request bytes were empty, but the
            // real client always sends exactly these 2 opcode bytes up
            // front regardless of which method it's calling, so nothing was
            // ever "empty" and it always fell into the get_fd() branch.
            val opcode = input.readUnsignedShort()
            log.debug(TAG, "Bridge: request opcode=0x${"%04x".format(opcode)}")

            val dev = device
            val conn = connection
            if (dev == null || conn == null) {
                output.write(1)
                output.flush()
                return
            }

            when (opcode) {
                0 -> {
                    // get_devices(): reply is a single length-prefixed
                    // string (2-byte BE length + UTF-8 bytes — the same
                    // framing java.io.DataOutputStream.writeUTF() produces,
                    // which get_devices()'s own read helper decodes with an
                    // explicit big-endian byte-swap). Multiple devices would
                    // be ':'-delimited within that one string (confirmed via
                    // the byte-by-byte 0x3a scan in get_devices()'s parsing
                    // loop) — moot here since this bridge only ever serves
                    // the one device it was started for.
                    output.writeUTF(dev.deviceName)
                    output.flush()
                    log.info(TAG, "Bridge: replied to get_devices() with '${dev.deviceName}'")
                }
                1 -> {
                    // get_fd(path): the path argument follows as its own
                    // separate writeUTF()-framed string (a second,
                    // independent write on the client side — see get_fd()'s
                    // call to its own string-send helper right after it
                    // writes the 2-byte opcode). Reply is a single status
                    // byte (0 = success) — with the actual fd riding along
                    // as SCM_RIGHTS ancillary data on that same underlying
                    // write, which is why setFileDescriptorsForSend() is
                    // called immediately before it and nothing else is
                    // written in between.
                    val requested = input.readUTF()
                    log.info(TAG, "Bridge: get_fd() requested '$requested'")
                    if (requested == dev.deviceName) {
                        ParcelFileDescriptor.fromFd(conn.fileDescriptor).use { duped ->
                            client.setFileDescriptorsForSend(arrayOf(duped.fileDescriptor))
                            output.write(0)
                            output.flush()
                        }
                        log.success(TAG, "Bridge: sent USB fd for '$requested'")
                    } else {
                        output.write(1)
                        output.flush()
                        log.warn(TAG, "Bridge: get_fd() request for unknown device '$requested' (have '${dev.deviceName}')")
                    }
                }
                else -> {
                    // get_serial() (opcode 0x0002, inferred from enum
                    // ordering — never confirmed since qdl itself never
                    // calls it, only the reference tool's own "tst" utility
                    // does) or anything else unrecognized. Not implemented;
                    // fail cleanly rather than hang.
                    output.write(1)
                    output.flush()
                    log.warn(TAG, "Bridge: unhandled opcode 0x${"%04x".format(opcode)}")
                }
            }
        } catch (e: IOException) {
            log.warn(TAG, "Bridge: client I/O error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** Stops accepting new connections and releases the USB device. Safe to call even if [start] was never called or already failed. */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { connection?.close() }
        connection = null
        device = null
    }
}
