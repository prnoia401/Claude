package com.prnoia.questremote.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prnoia.questremote.adb.NetworkScanner
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.QuestController.State
import com.prnoia.questremote.adb.UsbAdb
import com.prnoia.questremote.databinding.PageDeviceBinding
import kotlinx.coroutines.launch
import java.io.File

class DevicePage(private val activity: MainActivity, private val b: PageDeviceBinding) {

    private val prefs = activity.getSharedPreferences("connection", Context.MODE_PRIVATE)

    private class Action(val label: String, val confirm: String? = null, val run: () -> Unit)

    init {
        b.inputIp.setText(prefs.getString("ip", ""))
        b.inputPort.setText(prefs.getInt("port", 5555).toString())

        b.btnUsb.setOnClickListener { pickUsbDevice() }
        b.btnWifi.setOnClickListener { connectWifi() }
        b.btnScan.setOnClickListener { scan() }
        b.btnDisconnect.setOnClickListener { QuestController.disconnect() }
        b.btnRefreshInfo.setOnClickListener { refreshInfo() }

        val actions = listOf(
            Action("Скриншот") { screenshot() },
            Action("Домой") { activity.runCommand("Домой", "input keyevent KEYCODE_HOME") },
            Action("Назад") { activity.runCommand("Назад", "input keyevent KEYCODE_BACK") },
            Action("Громкость +") { activity.runCommand("Громкость +", "input keyevent KEYCODE_VOLUME_UP") },
            Action("Громкость −") { activity.runCommand("Громкость −", "input keyevent KEYCODE_VOLUME_DOWN") },
            Action("Разбудить") { activity.runCommand("Разбудить", "input keyevent KEYCODE_WAKEUP") },
            Action("Усыпить") { activity.runCommand("Усыпить", "input keyevent KEYCODE_SLEEP") },
            Action("Включить ADB по Wi‑Fi") { enableWifiAdb() },
            Action("Перезагрузить", confirm = "Перезагрузить шлем?") {
                activity.runAction("Перезагрузка") { QuestController.service("reboot:") }
            },
            Action("Выключить", confirm = "Выключить шлем?") {
                activity.runCommand("Выключение", "reboot -p")
            },
        )
        actions.forEach { action ->
            val chip = Chip(activity).apply {
                text = action.label
                setOnClickListener {
                    if (action.confirm == null) action.run()
                    else MaterialAlertDialogBuilder(activity)
                        .setMessage(action.confirm)
                        .setPositiveButton("Да") { _, _ -> action.run() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            b.actions.addView(chip)
        }
    }

    fun render(state: State) {
        b.status.text = when (state) {
            State.Disconnected -> "Не подключено"
            is State.Connecting ->
                if (state.awaitingApproval) {
                    "Наденьте шлем и нажмите «Разрешить» (лучше с галочкой «Всегда разрешать с этого компьютера»)"
                } else {
                    "Подключение (${state.via})…"
                }
            is State.Connected -> "✅ ${state.model}\n${state.via}"
            is State.Failed -> "❌ ${state.message}"
        }
        val busy = state is State.Connecting
        b.btnUsb.isEnabled = !busy
        b.btnWifi.isEnabled = !busy
        b.btnDisconnect.isVisible = state is State.Connected || busy
        if (state !is State.Connected) b.info.text = "—"
    }

    // ---- Подключение ----

    private fun pickUsbDevice() {
        val devices = UsbAdb.adbDevices(activity.usbManager)
        when {
            devices.isEmpty() -> MaterialAlertDialogBuilder(activity)
                .setTitle("Шлем не найден по USB")
                .setMessage(
                    "1. Шлем в режиме разработчика (приложение Meta Horizon → Устройства → Режим разработчика).\n" +
                        "2. Кабель Type‑C ↔ Type‑C с поддержкой данных, не только зарядки.\n" +
                        "3. Телефон — USB‑хост: опустите шторку, нажмите на уведомление USB → «USB управляет: это устройство». " +
                        "Если не помогло — переверните штекер на стороне шлема или подключите через OTG‑переходник.\n" +
                        "4. В шлеме появится запрос «Разрешить отладку по USB?» — разрешите."
                )
                .setPositiveButton("Понятно", null)
                .show()
            devices.size == 1 -> connectUsb(devices.first())
            else -> MaterialAlertDialogBuilder(activity)
                .setTitle("Выберите устройство")
                .setItems(devices.map(UsbAdb::label).toTypedArray()) { _, i -> connectUsb(devices[i]) }
                .show()
        }
    }

    fun connectUsb(device: UsbDevice) {
        activity.lifecycleScope.launch {
            val granted = UsbAdb.requestPermission(activity, activity.usbManager, device)
            if (!granted) {
                activity.toast("Доступ к USB‑устройству не выдан")
                return@launch
            }
            QuestController.connectUsb(activity.usbManager, device)
        }
    }

    private fun connectWifi() {
        val ip = b.inputIp.text.toString().trim()
        val port = b.inputPort.text.toString().toIntOrNull() ?: 5555
        if (ip.isEmpty()) {
            activity.toast("Введите IP шлема или нажмите «Найти в сети»")
            return
        }
        prefs.edit().putString("ip", ip).putInt("port", port).apply()
        activity.lifecycleScope.launch { QuestController.connectWifi(ip, port) }
    }

    private fun scan() {
        val own = NetworkScanner.phoneWifiAddress(activity)
        if (own == null) {
            activity.toast("Телефон не подключён к Wi‑Fi")
            return
        }
        b.btnScan.isEnabled = false
        b.btnScan.text = "Поиск…"
        activity.lifecycleScope.launch {
            val found = NetworkScanner.scan(own, b.inputPort.text.toString().toIntOrNull() ?: 5555)
            b.btnScan.isEnabled = true
            b.btnScan.text = "Найти в сети"
            if (found.isEmpty()) {
                activity.toast("В сети ${own.substringBeforeLast('.')}.* устройств с открытым ADB нет")
                return@launch
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Найдены устройства")
                .setItems(found.toTypedArray()) { _, i ->
                    b.inputIp.setText(found[i])
                    connectWifi()
                }
                .show()
        }
    }

    /**
     * Аналог `adb tcpip 5555`: после этого шлем слушает ADB по Wi‑Fi,
     * и кабель можно отключить (до перезагрузки шлема).
     */
    private fun enableWifiAdb() {
        activity.runAction("adb tcpip 5555") {
            val ip = wifiIp()
            val reply = QuestController.service("tcpip:5555")
            if (ip != null) {
                b.inputIp.setText(ip)
                b.inputPort.setText("5555")
                prefs.edit().putString("ip", ip).putInt("port", 5555).apply()
            }
            "$reply\nIP шлема: ${ip ?: "не определён (шлем не в Wi‑Fi?)"}. " +
                "Через пару секунд нажмите «По Wi‑Fi»."
        }
    }

    private suspend fun wifiIp(): String? {
        val out = QuestController.shell("ip -f inet addr show wlan0")
        return Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
    }

    // ---- Информация ----

    fun refreshInfo() {
        if (!QuestController.isConnected) return
        activity.lifecycleScope.launch {
            runCatching { QuestController.shell(INFO_SCRIPT) }
                .onSuccess { b.info.text = it }
                .onFailure { b.info.text = "Ошибка: ${it.message}" }
        }
    }

    // ---- Скриншот ----

    private fun screenshot() {
        activity.runAction("Скриншот") {
            val file = QuestController.screenshot()
            showScreenshot(file)
            "Сохранён: ${file.name}"
        }
    }

    private fun showScreenshot(file: File) {
        val image = ImageView(activity).apply {
            adjustViewBounds = true
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        }
        MaterialAlertDialogBuilder(activity)
            .setView(image)
            .setPositiveButton("Поделиться") { _, _ ->
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(Intent.createChooser(send, "Скриншот шлема"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    companion object {
        private val INFO_SCRIPT = """
            b=${'$'}(dumpsys battery)
            lvl=${'$'}(echo "${'$'}b" | grep -m1 'level:' | tr -dc 0-9)
            t=${'$'}(echo "${'$'}b" | grep -m1 'temperature:' | tr -dc 0-9)
            echo "Модель:      ${'$'}(getprop ro.product.model)"
            echo "Android:     ${'$'}(getprop ro.build.version.release) (SDK ${'$'}(getprop ro.build.version.sdk))"
            echo "Сборка:      ${'$'}(getprop ro.build.display.id)"
            echo "Серийный №:  ${'$'}(getprop ro.serialno)"
            echo "Батарея:     ${'$'}{lvl}%"
            [ -n "${'$'}t" ] && echo "Температура: ${'$'}((t / 10)).${'$'}((t % 10)) °C"
            echo "Wi‑Fi IP:    ${'$'}(ip -f inet addr show wlan0 | grep -o 'inet [0-9.]*' | cut -d' ' -f2)"
            echo "Частота:     ${'$'}(getprop debug.oculus.refreshRate) Гц (пусто = по умолчанию)"
            echo "Аптайм:      ${'$'}(uptime)"
            echo "Хранилище /data:"
            df -h /data | tail -n 1
        """.trimIndent()
    }
}
