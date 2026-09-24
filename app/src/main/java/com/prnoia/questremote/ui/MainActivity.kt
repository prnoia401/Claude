package com.prnoia.questremote.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.QuestController.State
import com.prnoia.questremote.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var console: ConsolePage
        private set
    private lateinit var device: DevicePage
    private lateinit var apps: AppsPage
    private lateinit var tweaks: TweaksPage

    val usbManager: UsbManager by lazy { getSystemService(UsbManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        console = ConsolePage(this, binding.pageConsole)
        device = DevicePage(this, binding.pageDevice)
        apps = AppsPage(this, binding.pageApps)
        tweaks = TweaksPage(this, binding.pageTweaks)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QuestController.state.collect(::render)
            }
        }

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** Шлем воткнули в телефон — система открыла приложение с этим устройством. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val usb = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?: return
        if (!QuestController.isConnected) device.connectUsb(usb)
    }

    fun showTab(id: Int) {
        binding.bottomNav.menu.findItem(id)?.isChecked = true
        binding.pageDevice.root.visibility = if (id == R.id.tab_device) View.VISIBLE else View.GONE
        binding.pageApps.root.visibility = if (id == R.id.tab_apps) View.VISIBLE else View.GONE
        binding.pageTweaks.root.visibility = if (id == R.id.tab_tweaks) View.VISIBLE else View.GONE
        binding.pageConsole.root.visibility = if (id == R.id.tab_console) View.VISIBLE else View.GONE
        when (id) {
            R.id.tab_apps -> apps.onShown()
            R.id.tab_tweaks -> tweaks.onShown()
        }
    }

    private fun render(state: State) {
        binding.toolbar.subtitle = when (state) {
            State.Disconnected -> "Не подключено"
            is State.Connecting -> if (state.awaitingApproval) "Подтвердите доступ в шлеме…" else "Подключение: ${state.via}…"
            is State.Connected -> "${state.model} · ${state.via}"
            is State.Failed -> "Ошибка подключения"
        }
        device.render(state)
        if (state is State.Connected) {
            device.refreshInfo()
        } else {
            console.stopLogcat()
        }
    }

    fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomNav)
            .show()
    }

    /**
     * Выполнить действие на шлеме: команда и результат пишутся в консоль,
     * ошибка показывается внизу экрана.
     */
    fun runAction(title: String, block: suspend () -> String?) {
        if (!QuestController.isConnected) {
            toast("Сначала подключите шлем")
            return
        }
        console.log("$ $title", ConsoleAdapter.Kind.COMMAND)
        lifecycleScope.launch {
            try {
                val out = block()
                if (!out.isNullOrBlank()) console.log(out)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                console.log(msg, ConsoleAdapter.Kind.ERROR)
                toast("$title: $msg")
            }
        }
    }

    fun runCommand(title: String, command: String) = runAction(title) { QuestController.shell(command) }
}
