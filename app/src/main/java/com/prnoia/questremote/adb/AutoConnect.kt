package com.prnoia.questremote.adb

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Автопилот подключения, чтобы ничего не нажимать руками:
 *  • воткнули кабель → подключаемся по USB (с повтором, если шлем не ответил с первого раза);
 *  • подключились по USB → шлем сам подключается к рабочей Wi‑Fi сети и включает ADB по сети;
 *  • выдернули кабель → переходим на Wi‑Fi по сохранённому IP шлема;
 *  • воткнули кабель снова → возвращаемся на USB;
 *  • при запуске приложения → подключаемся к тому, что доступно.
 */
object AutoConnect {

    /** Рабочая сеть по умолчанию (можно поменять в приложении). */
    const val DEFAULT_SSID = "ВАЙФАЙДЛЯКРУТЫХ"
    const val DEFAULT_PASSWORD = "parol987654321"
    private const val ADB_PORT = 5555
    private const val USB_RETRIES = 3

    private lateinit var app: Application
    private val prefs by lazy { app.getSharedPreferences("auto", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiJob: Job? = null
    private var fallbackJob: Job? = null
    private var usbRetryJob: Job? = null
    private var usbFailures = 0

    private val _status = MutableStateFlow("")
    /** Что сейчас делает автопилот — показывается на вкладке «Шлем». */
    val status: StateFlow<String> = _status.asStateFlow()

    var autoWifi: Boolean
        get() = prefs.getBoolean("auto_wifi", true)
        set(v) = prefs.edit().putBoolean("auto_wifi", v).apply()

    var ssid: String
        get() = prefs.getString("ssid", null) ?: DEFAULT_SSID
        set(v) = prefs.edit().putString("ssid", v).apply()

    var password: String
        get() = prefs.getString("password", null) ?: DEFAULT_PASSWORD
        set(v) = prefs.edit().putString("password", v).apply()

    /** Последний известный IP шлема в Wi‑Fi. */
    var headsetIp: String?
        get() = prefs.getString("headset_ip", null)
        private set(v) = prefs.edit().putString("headset_ip", v).apply()

    private val usbManager: UsbManager? get() = app.getSystemService(UsbManager::class.java)

    fun start(application: Application) {
        app = application
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let { onUsbAttached(it, explicit = false) }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> onUsbDetached()
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            var previous: QuestController.State? = null
            QuestController.state.collect { st ->
                if (st != previous) onState(st)
                previous = st
            }
        }
        scope.launch {
            delay(300)
            connectAnything()
        }
    }

    /** Подключиться к тому, что есть: сначала кабель, потом сохранённый Wi‑Fi. */
    fun connectAnything() {
        if (!QuestController.autoReconnect) return
        val st = QuestController.state.value
        if (st is QuestController.State.Connected || st is QuestController.State.Connecting) return
        val usb = usbManager?.let(UsbAdb::adbDevices)?.firstOrNull()
        when {
            usb != null -> connectUsb(usb)
            headsetIp != null -> connectWifiFallback()
        }
    }

    /**
     * Кабель воткнули. [explicit] — пользователь сам выбрал наше приложение в системном диалоге USB:
     * тогда подключаемся даже при выключенном автопереподключении.
     */
    fun onUsbAttached(device: UsbDevice, explicit: Boolean) {
        if (UsbTransport.findAdbInterface(device) == null) return
        if (!explicit && !QuestController.autoReconnect) return
        usbFailures = 0
        // Уже по кабелю — ничего не делаем. Если по Wi‑Fi — переходим на кабель: он быстрее и стабильнее.
        if (QuestController.isConnected && QuestController.isUsb) return
        connectUsb(device)
    }

    private fun onUsbDetached() {
        val adbStillThere = usbManager?.let(UsbAdb::adbDevices)?.isNotEmpty() == true
        if (!adbStillThere && QuestController.isUsb && QuestController.autoReconnect) {
            usbRetryJob?.cancel()
            _status.value = "Кабель отключён — переключаюсь на Wi‑Fi…"
            connectWifiFallback(force = false)
        }
    }

    fun connectUsb(device: UsbDevice) {
        fallbackJob?.cancel()
        usbRetryJob?.cancel()
        val manager = usbManager ?: return
        scope.launch {
            if (!UsbAdb.requestPermission(app, manager, device)) {
                _status.value = "Нет разрешения на USB‑устройство"
                return@launch
            }
            QuestController.connectUsb(manager, device)
        }
    }

    /** [force] — пробовать Wi‑Fi, даже если кабель воткнут (кабель не работает). */
    private fun connectWifiFallback(force: Boolean = false) {
        val ip = headsetIp ?: return
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            delay(1_500) // дать шлему перезапустить adbd, если он только что сменил режим
            for (i in 1..5) {
                if (QuestController.isConnected) return@launch
                if (!force && usbManager?.let(UsbAdb::adbDevices)?.isNotEmpty() == true) return@launch
                QuestController.connectWifi(ip, ADB_PORT)
                if (QuestController.isConnected) return@launch
                _status.value = "Wi‑Fi $ip: попытка ${i + 1}…"
                delay(2_000L * i)
            }
            _status.value = "Шлем не найден по Wi‑Fi ($ip). Подключите кабель."
        }
    }

    private fun onState(st: QuestController.State) {
        when (st) {
            is QuestController.State.Connected -> {
                fallbackJob?.cancel()
                usbFailures = 0
                if (QuestController.isUsb) {
                    _status.value = "Подключено по кабелю"
                    if (autoWifi) setupWifi(auto = true)
                } else {
                    _status.value = "Подключено по Wi‑Fi — кабель не нужен"
                }
            }
            is QuestController.State.Failed -> {
                if (!QuestController.autoReconnect || st.reconnecting || !QuestController.isUsb) return
                // Связь по кабелю пропала или не установилась (например, шлем перезапустил adbd
                // после tcpip или не ответил сразу после перевтыкания) — повторяем, потом Wi‑Fi.
                val usb = usbManager?.let(UsbAdb::adbDevices)?.firstOrNull()
                when {
                    usb == null -> connectWifiFallback()
                    usbFailures < USB_RETRIES -> {
                        usbFailures++
                        _status.value = "USB: повтор $usbFailures из $USB_RETRIES…"
                        usbRetryJob?.cancel()
                        usbRetryJob = scope.launch {
                            delay(1_500L * usbFailures)
                            if (!QuestController.isConnected) connectUsb(usb)
                        }
                    }
                    else -> {
                        _status.value = "Шлем не отвечает по кабелю — пробую Wi‑Fi"
                        connectWifiFallback(force = true)
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Подключить шлем к рабочей сети и включить ADB по Wi‑Fi.
     * [auto] — запущено автопилотом: без лишних действий, если всё уже настроено.
     */
    fun setupWifi(auto: Boolean = false) {
        wifiJob?.cancel()
        wifiJob = scope.launch {
            try {
                _status.value = "Wi‑Fi: проверяю шлем…"
                val sdk = QuestController.shell("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
                var ip = headsetWifiIp()
                val current = currentSsid(sdk)
                if (ssid.isNotBlank() && (ip == null || (current != null && current != ssid))) {
                    if (sdk < 30) {
                        _status.value = "Шлем на Android ${androidName(sdk)}: подключите его к «$ssid» вручную один раз — " +
                            "дальше он будет подключаться сам."
                        if (ip == null) return@launch
                    } else {
                        _status.value = "Wi‑Fi: подключаю шлем к «$ssid»…"
                        QuestController.shell(
                            "cmd wifi set-wifi-enabled enabled; " +
                                "cmd wifi connect-network ${QuestController.quote(ssid)} wpa2 ${QuestController.quote(password)}"
                        )
                        ip = null
                        for (i in 0 until 20) {
                            delay(1_000)
                            ip = headsetWifiIp()
                            if (ip != null && currentSsid(sdk) == ssid) break
                        }
                    }
                }
                if (ip == null) {
                    _status.value = "Шлем не получил IP в сети «$ssid». Проверьте пароль и что сеть рядом."
                    return@launch
                }
                headsetIp = ip
                val tcpPort = QuestController.shell("getprop service.adb.tcp.port").trim()
                if (tcpPort == ADB_PORT.toString()) {
                    _status.value = "Готово: шлем в Wi‑Fi ($ip), ADB по сети включён — кабель можно отключить."
                    return@launch
                }
                _status.value = "Включаю ADB по Wi‑Fi на $ip…"
                // adbd перезапустится: связь по кабелю на секунду пропадёт и восстановится сама.
                runCatching { QuestController.service("tcpip:$ADB_PORT") }
                _status.value = "Готово: шлем в Wi‑Fi ($ip), ADB по сети включён — кабель можно отключить."
            } catch (e: Exception) {
                if (!auto || QuestController.isConnected) _status.value = "Wi‑Fi: ${e.message}"
            }
        }
    }

    /** Отключить шлем от Wi‑Fi (связь по кабелю остаётся). */
    suspend fun disconnectHeadsetWifi(): String {
        val sdk = QuestController.shell("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
        return if (sdk >= 30) QuestController.shell("cmd wifi set-wifi-enabled disabled")
        else QuestController.shell("svc wifi disable")
    }

    private suspend fun headsetWifiIp(): String? {
        val out = QuestController.shell("ip -f inet addr show wlan0 2>/dev/null")
        return Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
    }

    /** SSID, к которому подключён шлем (Android 11+), или null, если не удалось узнать. */
    private suspend fun currentSsid(sdk: Int): String? {
        if (sdk < 30) return null
        val out = QuestController.shell("cmd wifi status 2>/dev/null")
        return Regex("""connected to "([^"]*)"""").find(out)?.groupValues?.get(1)
    }

    private fun androidName(sdk: Int) = when (sdk) {
        29 -> "10"
        28 -> "9"
        else -> "SDK $sdk"
    }
}
