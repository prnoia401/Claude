package com.prnoia.questremote.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Поиск подключённых по USB устройств с ADB и запрос разрешения на доступ к ним. */
object UsbAdb {
    private const val ACTION_PERMISSION = "com.prnoia.questremote.USB_PERMISSION"
    const val OCULUS_VENDOR_ID = 0x2833

    fun adbDevices(manager: UsbManager): List<UsbDevice> =
        manager.deviceList.values.filter { UsbTransport.findAdbInterface(it) != null }
            // Шлемы Meta — первыми в списке.
            .sortedByDescending { it.vendorId == OCULUS_VENDOR_ID }

    fun label(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ")
        return name.ifBlank { "USB %04x:%04x".format(device.vendorId, device.productId) }
    }

    suspend fun requestPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }
}
