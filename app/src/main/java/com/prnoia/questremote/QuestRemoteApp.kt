package com.prnoia.questremote

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.UsbAdb
import com.prnoia.questremote.adb.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QuestRemoteApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        QuestController.init(this)
        QuestController.autoReconnect =
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_RECONNECT, true)
        watchUsb()
    }

    /**
     * Кабель переподключили (или шлем перезагрузился) — восстанавливаем связь сами,
     * если до этого были подключены по USB.
     */
    private fun watchUsb() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    ?: return
                if (!QuestController.autoReconnect) return
                if (QuestController.lastTarget !is QuestController.Target.Usb) return
                if (QuestController.isConnected || UsbTransport.findAdbInterface(device) == null) return
                val manager = getSystemService(UsbManager::class.java) ?: return
                scope.launch {
                    if (UsbAdb.requestPermission(this@QuestRemoteApp, manager, device)) {
                        QuestController.connectUsb(manager, device)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
