package com.prnoia.questremote.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prnoia.questremote.adb.DeviceProfile
import com.prnoia.questremote.adb.NetworkScanner
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.QuestController.State
import com.prnoia.questremote.adb.UsbAdb
import com.prnoia.questremote.databinding.PageDeviceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DevicePage(private val activity: MainActivity, private val b: PageDeviceBinding) {

    private val prefs = activity.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private var liveJob: Job? = null
    private lateinit var recordChip: Chip
    private lateinit var liveChip: Chip

    private class Action(val label: String, val confirm: String? = null, val run: () -> Unit)

    init {
        b.inputIp.setText(prefs.getString("ip", ""))
        b.inputPort.setText(prefs.getInt("port", 5555).toString())

        b.btnUsb.setOnClickListener { pickUsbDevice() }
        b.btnWifi.setOnClickListener { connectWifi() }
        b.btnScan.setOnClickListener { scan() }
        b.btnDisconnect.setOnClickListener { QuestController.disconnect() }
        b.btnRefreshInfo.setOnClickListener { refreshInfo() }

        if (!activity.packageManager.hasSystemFeature("android.hardware.usb.host")) {
            b.usbHint.text = "Этот телефон не поддерживает режим USB-хоста — используйте Wi‑Fi " +
                "(включите ADB по сети на шлеме с компьютера: adb tcpip 5555)."
        }

        val actions = listOf(
            Action("Скриншот") { screenshot() },
            Action("Домой") { key("Домой", "KEYCODE_HOME") },
            Action("Назад") { key("Назад", "KEYCODE_BACK") },
            Action("Громкость +") { key("Громкость +", "KEYCODE_VOLUME_UP") },
            Action("Громкость −") { key("Громкость −", "KEYCODE_VOLUME_DOWN") },
            Action("Без звука") { key("Без звука", "KEYCODE_VOLUME_MUTE") },
            Action("Разбудить") { key("Разбудить", "KEYCODE_WAKEUP") },
            Action("Усыпить") { key("Усыпить", "KEYCODE_SLEEP") },
            Action("Ввести текст") { typeText() },
            Action("Открыть ссылку") { openUrl() },
            Action("Включить ADB по Wi‑Fi") { enableWifiAdb() },
            Action("Перезагрузить", confirm = "Перезагрузить шлем?") {
                activity.runAction("Перезагрузка") { QuestController.service("reboot:") }
            },
            Action("Выключить", confirm = "Выключить шлем?") {
                activity.runCommand("Выключение", "reboot -p")
            },
        )
        actions.forEach { action ->
            b.actions.addView(Chip(activity).apply {
                text = action.label
                setOnClickListener {
                    if (action.confirm == null) action.run()
                    else MaterialAlertDialogBuilder(activity)
                        .setMessage(action.confirm)
                        .setPositiveButton("Да") { _, _ -> action.run() }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            })
        }
        liveChip = Chip(activity).apply {
            text = "Живой экран"
            isCheckable = true
            setOnClickListener { if (isChecked) startLiveView() else stopLiveView() }
        }
        recordChip = Chip(activity).apply {
            text = "Запись видео"
            isCheckable = true
            setOnClickListener { toggleRecording() }
        }
        b.actions.addView(liveChip, 1)
        b.actions.addView(recordChip, 2)
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
            is State.Connected -> "✅ ${state.profile.name} (${state.profile.vendor.title})\n${state.via}"
            is State.Failed -> "❌ ${state.message}" + if (state.reconnecting) "\nПереподключаюсь…" else ""
        }
        val busy = state is State.Connecting
        b.btnUsb.isEnabled = !busy
        b.btnWifi.isEnabled = !busy
        b.btnDisconnect.isVisible = state is State.Connected || busy ||
            (state is State.Failed && state.reconnecting)
        if (state !is State.Connected) {
            b.info.text = "—"
            recordChip.isChecked = false
        }
    }

    private fun key(title: String, code: String) = activity.runCommand(title, "input keyevent $code")

    // ---- Подключение ----

    private fun pickUsbDevice() {
        val devices = UsbAdb.adbDevices(activity.usbManager)
        when {
            devices.isEmpty() -> MaterialAlertDialogBuilder(activity)
                .setTitle("Шлем не найден по USB")
                .setMessage(
                    "1. На шлеме включён режим разработчика:\n" +
                        "   • Quest — приложение Meta Horizon → Устройства → Режим разработчика;\n" +
                        "   • PICO — Настройки → Общие → О устройстве → 7 раз нажать на номер сборки, " +
                        "затем Настройки → Разработчик → Отладка по USB.\n" +
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

    // ---- Ввод ----

    private fun prompt(title: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Отправить") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Набрать текст в активном поле шлема (логины, пароли, адреса). */
    private fun typeText() = prompt("Ввести текст в шлеме", "Сначала выберите поле ввода в шлеме") { text ->
        if (text.isEmpty()) return@prompt
        if (text.any { it.code > 127 }) {
            activity.toast("input text поддерживает только латиницу, цифры и символы ASCII")
            return@prompt
        }
        // В `input text` пробел кодируется как %s.
        activity.runCommand("Ввод текста", "input text ${QuestController.quote(text.replace(" ", "%s"))}")
    }

    private fun openUrl() = prompt("Открыть ссылку в шлеме", "https://…") { raw ->
        val url = raw.trim().let { if ("://" in it) it else "https://$it" }
        if (url.length <= 8) return@prompt
        activity.runCommand(
            "Открыть $url",
            "am start -a android.intent.action.VIEW -d ${QuestController.quote(url)}"
        )
    }

    // ---- Информация ----

    fun refreshInfo() {
        if (!QuestController.isConnected) return
        val profile = QuestController.profile
        activity.lifecycleScope.launch {
            runCatching { QuestController.shell(infoScript(profile)) }
                .onSuccess { b.info.text = it }
                .onFailure { b.info.text = "Ошибка: ${it.message}" }
        }
    }

    private fun infoScript(profile: DeviceProfile?): String {
        val d = '$'
        val meta = if (profile?.hasOculusProps == true) """
            rr=${d}(getprop debug.oculus.refreshRate)
            echo "Частота:     ${d}{rr:-по умолчанию} Гц"
        """.trimIndent() else ""
        return """
            b=${d}(dumpsys battery)
            lvl=${d}(echo "${d}b" | grep -m1 'level:' | tr -dc 0-9)
            t=${d}(echo "${d}b" | grep -m1 'temperature:' | tr -dc 0-9)
            plug=${d}(echo "${d}b" | grep -E 'AC powered: true|USB powered: true' | head -n 1)
            echo "Устройство:  ${profile?.name ?: ""} (${d}(getprop ro.product.model))"
            echo "Android:     ${d}(getprop ro.build.version.release) (SDK ${d}(getprop ro.build.version.sdk))"
            echo "Сборка:      ${d}(getprop ro.build.display.id)"
            echo "Серийный №:  ${d}(getprop ro.serialno)"
            echo "Батарея:     ${d}{lvl}%${d}{plug:+ (заряжается)}"
            [ -n "${d}t" ] && echo "Температура: ${d}((t / 10)).${d}((t % 10)) °C"
            echo "Wi‑Fi IP:    ${d}(ip -f inet addr show wlan0 | grep -o 'inet [0-9.]*' | cut -d' ' -f2)"
            $meta
            echo "Аптайм:     ${d}(uptime)"
            echo "Хранилище /data:"
            df -h /data | tail -n 1
        """.trimIndent()
    }

    // ---- Скриншот, живой экран, запись ----

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

    /** Обновляемый скриншот (≈1–2 кадра в секунду) — посмотреть, что видит человек в шлеме. */
    private fun startLiveView() {
        if (!QuestController.isConnected) {
            liveChip.isChecked = false
            activity.toast("Сначала подключите шлем")
            return
        }
        liveJob?.cancel()
        b.liveCard.isVisible = true
        b.liveStatus.text = "Живой экран: загрузка…"
        liveJob = activity.lifecycleScope.launch {
            var frames = 0
            val started = System.currentTimeMillis()
            while (isActive && QuestController.isConnected) {
                try {
                    val png = QuestController.screenshotBytes()
                    val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
                    b.liveImage.setImageBitmap(bmp)
                    frames++
                    val fps = frames * 1000.0 / (System.currentTimeMillis() - started).coerceAtLeast(1)
                    b.liveStatus.text = "Живой экран: %.1f кадр/с (скриншоты по ADB)".format(fps)
                } catch (e: Exception) {
                    b.liveStatus.text = "Ошибка: ${e.message}"
                    delay(1_000)
                }
                delay(200)
            }
        }
    }

    fun stopLiveView() {
        liveJob?.cancel()
        liveJob = null
        if (::liveChip.isInitialized) liveChip.isChecked = false
        b.liveCard.isVisible = false
        b.liveImage.setImageDrawable(null)
    }

    private fun toggleRecording() {
        if (!QuestController.isRecording) {
            recordChip.isChecked = false
            activity.runAction("Запись видео") {
                QuestController.startRecording()
                recordChip.isChecked = true
                "Запись идёт (до 3 минут). Нажмите «Запись видео» ещё раз, чтобы остановить и сохранить."
            }
        } else {
            recordChip.isChecked = false
            val name = "headset_${System.currentTimeMillis() / 1000}.mp4"
            activity.saveToPhone(name, "Сохранение видео") { out -> QuestController.stopRecording(out) }
        }
    }
}
