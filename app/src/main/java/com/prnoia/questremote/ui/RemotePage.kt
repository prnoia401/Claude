package com.prnoia.questremote.ui

import android.content.Context
import android.text.InputType
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.prnoia.questremote.BuildConfig
import com.prnoia.questremote.adb.NetworkScanner
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.RemoteLink
import com.prnoia.questremote.databinding.PageRemoteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Пульт: команды нашему клиенту на шлеме (Quest Remote Client) — запустить видео,
 * пауза, перемотка, громкость, запуск приложений, свои команды.
 */
class RemotePage(private val activity: MainActivity, private val b: PageRemoteBinding) {

    private val prefs = activity.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private var userSeeking = false
    private var autoLinking = false

    init {
        b.remoteIp.setText(prefs.getString("ip", ""))
        b.remotePin.setText(prefs.getString("pin", ""))

        b.btnViaAdb.setOnClickListener { connect("через ADB") { RemoteLink.viaAdb() } }
        b.btnViaWifi.setOnClickListener { connectWifi() }
        b.btnFindClient.setOnClickListener { findClients() }
        b.btnInstallClient.setOnClickListener { installClient() }
        b.btnUnlink.setOnClickListener { Session.disconnect() }

        b.btnToggle.setOnClickListener { send("toggle") }
        b.btnStop.setOnClickListener { send("stop") }
        b.btnBack10.setOnClickListener { send("seek_by", JSONObject().put("delta", -10_000)) }
        b.btnFwd10.setOnClickListener { send("seek_by", JSONObject().put("delta", 10_000)) }
        b.loop.setOnClickListener { send("loop", JSONObject().put("value", b.loop.isChecked)) }
        b.seek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                send("seek", JSONObject().put("position", slider.value.toInt()))
            }
        })
        b.volume.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                send("volume", JSONObject().put("level", slider.value.toInt()))
            }
        })

        b.btnMediaRefresh.setOnClickListener { loadMedia() }
        b.btnPlayUrl.setOnClickListener {
            prompt("Путь на шлеме или ссылка", "/sdcard/Movies/film.mp4 или https://…") { src ->
                if (src.isNotBlank()) play(src.trim())
            }
        }

        listOf<Pair<String, () -> Unit>>(
            "Запустить приложение…" to ::pickApp,
            "Сообщение в шлем" to {
                prompt("Сообщение", "Текст появится в шлеме") { text ->
                    if (text.isNotBlank()) send("message", JSONObject().put("text", text))
                }
            },
            "Статус" to { send("status") },
            "Проверка связи" to { send("ping") },
        ).forEach { (label, action) ->
            b.commandChips.addView(Chip(activity).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        b.btnSendRaw.setOnClickListener { sendRaw() }

        // Экран пересоздан при живой связи (поворот, возврат в приложение) — сразу показываем видео.
        if (Session.link != null) loadMedia()

        // Шлем подключился по ADB — автоматически поднимаем связь с клиентом.
        activity.lifecycleScope.launch {
            QuestController.state.collect { st ->
                if (st is QuestController.State.Connected && Session.link == null) autoLink()
            }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Session.state.collectLatest(::renderLink) }
                launch {
                    Session.linkFlow.collectLatest { link ->
                        link?.status?.collect { st -> if (st != null) renderStatus(st) }
                    }
                }
            }
        }
    }

    fun onShown() {
        if (Session.link == null) {
            b.linkStatus.text = Session.state.value
            if (QuestController.isConnected) autoLink()
        } else if (b.mediaList.childCount == 0) {
            // Связь пережила пересоздание экрана, а список видео — нет: подгружаем заново.
            loadMedia()
        }
    }

    // ---- Подключение ----

    private fun connect(title: String, open: suspend () -> RemoteLink) {
        activity.lifecycleScope.launch {
            Session.state.value = "Подключение $title…"
            try {
                val link = open()
                Session.attach(link)
                activity.console.log("Пульт подключён ${link.description}", ConsoleAdapter.Kind.COMMAND)
                runCatching { link.request("status") }
                loadMedia()
            } catch (e: Exception) {
                Session.state.value = "Ошибка: ${e.message}"
            }
        }
    }

    private fun connectWifi() {
        val ip = b.remoteIp.text.toString().trim()
        val pin = b.remotePin.text.toString().trim()
        if (ip.isEmpty() || pin.isEmpty()) {
            activity.toast("Введите IP шлема и PIN с экрана клиента")
            return
        }
        prefs.edit().putString("ip", ip).putString("pin", pin).apply()
        connect("к $ip") { RemoteLink.viaWifi(ip, pin) }
    }

    private fun findClients() {
        val own = NetworkScanner.phoneWifiAddress(activity) ?: run {
            activity.toast("Телефон не подключён к Wi‑Fi")
            return
        }
        b.btnFindClient.isEnabled = false
        activity.lifecycleScope.launch {
            val found = NetworkScanner.scan(own, RemoteLink.PORT)
            b.btnFindClient.isEnabled = true
            if (found.isEmpty()) {
                activity.toast("Клиент не найден в сети ${own.substringBeforeLast('.')}.*")
                return@launch
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Найдены клиенты")
                .setItems(found.toTypedArray()) { _, i -> b.remoteIp.setText(found[i]) }
                .show()
        }
    }

    /** Кнопка «Установить клиент»: всегда переустанавливает и сразу подключается. */
    private fun installClient() {
        activity.runAction("Установка клиента на шлем") {
            val msg = ensureClient(forceInstall = true)
            autoLink()
            msg
        }
    }

    /**
     * Убедиться, что на шлеме стоит актуальный клиент, у него есть разрешения и он запущен.
     * Возвращает описание сделанного.
     */
    private suspend fun ensureClient(forceInstall: Boolean = false): String {
        val installed = QuestController.shell("dumpsys package $CLIENT | grep -m1 versionCode")
        val code = Regex("""versionCode=(\d+)""").find(installed)?.groupValues?.get(1)?.toIntOrNull()
        var done = "Клиент уже установлен"
        if (forceInstall || code == null || code < BuildConfig.VERSION_CODE) {
            val apk = withContext(Dispatchers.IO) {
                File(activity.cacheDir, "client.apk").also { f ->
                    activity.assets.open("client.apk").use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
            val result = QuestController.install(apk)
            apk.delete()
            if (!result.startsWith("Success")) throw java.io.IOException("Установка клиента: $result")
            done = if (code == null) "Клиент установлен" else "Клиент обновлён"
        }
        QuestController.shell(
            listOf(
                // Открывать плеер и приложения по команде, когда клиент в фоне.
                "appops set $CLIENT SYSTEM_ALERT_WINDOW allow",
                // Чтение видео из общей памяти на Android 11+.
                "appops set $CLIENT MANAGE_EXTERNAL_STORAGE allow",
                "am start-foreground-service -n $CLIENT/.CommandService",
            ).joinToString("; ") { "$it >/dev/null 2>&1" }
        )
        return done
    }

    /** Шлем подключён по ADB — сами ставим/запускаем клиент и подключаемся к нему. */
    private fun autoLink() {
        if (Session.link != null || autoLinking) return
        autoLinking = true
        activity.lifecycleScope.launch {
            try {
                Session.state.value = "Готовлю клиент на шлеме…"
                val note = ensureClient()
                // Служба клиента поднимается не мгновенно — несколько попыток.
                var last: Exception? = null
                for (i in 1..5) {
                    if (!QuestController.isConnected) return@launch
                    try {
                        val link = RemoteLink.viaAdb()
                        Session.attach(link)
                        activity.console.log("Пульт: $note, подключено ${link.description}", ConsoleAdapter.Kind.COMMAND)
                        runCatching { link.request("status") }
                        loadMedia()
                        return@launch
                    } catch (e: Exception) {
                        last = e
                        kotlinx.coroutines.delay(1_000L * i)
                    }
                }
                Session.state.value = "Клиент не отвечает: ${last?.message}"
            } catch (e: Exception) {
                Session.state.value = "Ошибка: ${e.message}"
            } finally {
                autoLinking = false
            }
        }
    }

    // ---- Команды ----

    private fun send(cmd: String, params: JSONObject = JSONObject()) {
        val link = Session.link ?: run {
            activity.toast("Сначала подключитесь к клиенту")
            return
        }
        activity.lifecycleScope.launch {
            try {
                val res = link.request(cmd, params)
                if (cmd in VERBOSE) activity.console.log("пульт $cmd → $res")
                if (cmd == "ping") activity.toast("Клиент: ${res.optString("device")}, Android ${res.optString("android")}")
            } catch (e: Exception) {
                activity.toast("$cmd: ${e.message}")
                activity.console.log("пульт $cmd: ${e.message}", ConsoleAdapter.Kind.ERROR)
            }
        }
    }

    private fun play(src: String) =
        send("play", JSONObject().put(if ("://" in src) "url" else "path", src).put("loop", b.loop.isChecked))

    private fun sendRaw() {
        val text = b.rawCommand.text.toString().trim()
        val json = runCatching { JSONObject(text) }.getOrElse {
            activity.toast("Некорректный JSON")
            return
        }
        val cmd = json.optString("cmd")
        if (cmd.isEmpty()) {
            activity.toast("В JSON нужно поле \"cmd\"")
            return
        }
        json.remove("cmd")
        val link = Session.link ?: run {
            activity.toast("Сначала подключитесь к клиенту")
            return
        }
        activity.lifecycleScope.launch {
            try {
                val res = link.request(cmd, json)
                activity.console.log("пульт $cmd → $res")
                activity.toast("OK")
            } catch (e: Exception) {
                activity.toast("$cmd: ${e.message}")
            }
        }
    }

    private fun loadMedia() {
        val link = Session.link ?: return
        activity.lifecycleScope.launch {
            val files = runCatching { link.request("list_media", timeoutMs = 20_000).optJSONArray("files") }
                .getOrElse {
                    activity.toast("Список видео: ${it.message}")
                    return@launch
                } ?: JSONArray()
            b.mediaList.removeAllViews()
            if (files.length() == 0) {
                b.mediaList.addView(TextView(activity).apply {
                    text = "В Movies, Download и DCIM видео не найдено. Загрузите файлы во вкладке «Файлы»."
                })
            }
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                b.mediaList.addView(row(
                    "🎬 ${f.optString("name")}",
                    "${Formatter.formatShortFileSize(activity, f.optLong("size"))} · ${f.optString("path")}",
                ) { play(f.optString("path")) })
            }
        }
    }

    private fun pickApp() {
        val link = Session.link ?: run {
            activity.toast("Сначала подключитесь к клиенту")
            return
        }
        activity.lifecycleScope.launch {
            val apps = runCatching { link.request("list_apps").optJSONArray("apps") }.getOrNull() ?: JSONArray()
            val items = (0 until apps.length()).map { apps.getJSONObject(it) }
            if (items.isEmpty()) {
                activity.toast("Список приложений пуст")
                return@launch
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Запустить на шлеме")
                .setItems(items.map { "${it.optString("label")}\n${it.optString("package")}" }.toTypedArray()) { _, i ->
                    send("launch", JSONObject().put("package", items[i].optString("package")))
                }
                .show()
        }
    }

    // ---- Отрисовка ----

    private fun renderLink(text: String) {
        b.linkStatus.text = text
        val linked = Session.link != null
        b.btnUnlink.isVisible = linked
        listOf(b.btnToggle, b.btnStop, b.btnBack10, b.btnFwd10, b.btnMediaRefresh, b.btnPlayUrl, b.btnSendRaw)
            .forEach { it.isEnabled = linked }
        if (!linked) {
            b.nowPlaying.text = "—"
            b.mediaList.removeAllViews()
        }
    }

    private fun renderStatus(st: JSONObject) {
        val state = st.optString("state")
        val source = st.optString("source").takeIf { it.isNotEmpty() && it != "null" }
        val stateRu = when (state) {
            "playing" -> "▶ Воспроизведение"
            "paused" -> "⏸ Пауза"
            "loading" -> "⏳ Загрузка"
            "ended" -> "⏹ Закончилось"
            "error" -> "⚠ ${st.optString("error")}"
            else -> "Ожидание"
        }
        b.nowPlaying.text = if (source != null) "$stateRu: ${source.substringAfterLast('/')}" else stateRu
        val duration = st.optInt("duration")
        val position = st.optInt("position").coerceIn(0, maxOf(duration, 0))
        if (!userSeeking) {
            b.seek.valueTo = maxOf(duration, 1).toFloat()
            b.seek.value = position.toFloat().coerceAtMost(b.seek.valueTo)
        }
        b.seek.isEnabled = duration > 0
        b.time.text = "${fmt(position)} / ${fmt(duration)}"
        b.loop.isChecked = st.optBoolean("loop")
        if (st.has("volume")) b.volume.value = (st.optInt("volume") / 5 * 5).coerceIn(0, 100).toFloat()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit): View {
        val pad = (6 * activity.resources.displayMetrics.density).toInt()
        return android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            isClickable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = activity.obtainStyledAttributes(attrs)
            background = ta.getDrawable(0)
            ta.recycle()
            addView(TextView(activity).apply { text = title; textSize = 16f })
            addView(TextView(activity).apply { text = subtitle; textSize = 12f })
            setOnClickListener { onClick() }
        }
    }

    private fun prompt(title: String, hint: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Текущая связь с клиентом; переживает пересоздание экрана. */
    private object Session {
        val state = MutableStateFlow("Не подключено")
        val linkFlow = MutableStateFlow<RemoteLink?>(null)
        val link: RemoteLink? get() = linkFlow.value

        fun attach(l: RemoteLink) {
            linkFlow.value?.close()
            l.onClosed = { reason ->
                if (linkFlow.value === l) {
                    linkFlow.value = null
                    state.value = "Связь с клиентом потеряна: $reason"
                }
            }
            linkFlow.value = l
            state.value = "✅ Подключено ${l.description}"
        }

        fun disconnect() {
            val l = linkFlow.value
            linkFlow.value = null
            l?.close()
            state.value = "Не подключено"
        }
    }

    private companion object {
        const val CLIENT = "com.prnoia.questclient"
        val VERBOSE = setOf("status", "ping", "launch", "message")
    }
}
