package com.siroha.flashtool.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.siroha.flashtool.USB_PERMISSION"
private const val QUALCOMM_EDL_VENDOR_ID = 0x05C6
private const val QUALCOMM_EDL_PRODUCT_ID = 0x9008

/**
 * Enumerates attached USB devices and distinguishes "this looks like a
 * Qualcomm EDL (9008) device" from "this looks like a fastboot device"
 * using vendor/product ID for EDL (Qualcomm's is fixed: 05C6:9008) and the
 * vendor-specific-interface heuristic for fastboot (see
 * FastbootUsbClient.findFastbootInterface — fastboot VID/PID varies wildly
 * by OEM, so interface shape is the only reliable signal).
 */
object UsbDeviceHelper {

    fun listDevices(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.toList()
    }

    fun isEdlDevice(device: UsbDevice): Boolean =
        device.vendorId == QUALCOMM_EDL_VENDOR_ID && device.productId == QUALCOMM_EDL_PRODUCT_ID

    fun isLikelyFastbootDevice(device: UsbDevice): Boolean =
        !isEdlDevice(device) && FastbootUsbClient.findFastbootInterface(device) != null

    /** Suspends until the user grants (or denies) USB permission for [device]. */
    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else 0
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
            )
            manager.requestPermission(device, pendingIntent)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }
}
