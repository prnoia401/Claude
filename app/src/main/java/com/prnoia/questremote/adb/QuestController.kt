package com.prnoia.questremote.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Единая точка управления шлемом. Живёт в Application, поэтому переживает
 * повороты экрана и смену вкладок.
 */
object QuestController {

    sealed interface State {
        data object Disconnected : State
        data class Connecting(val via: String, val awaitingApproval: Boolean = false) : State
        data class Connected(val via: String, val profile: DeviceProfile) : State {
            val model: String get() = profile.name
        }
        data class Failed(val message: String, val reconnecting: Boolean = false) : State
    }

    /** Куда подключались в последний раз — для автопереподключения. */
    sealed interface Target {
        data class Usb(val deviceName: String) : Target
        data class Wifi(val host: String, val port: Int) : Target
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var crypto: AdbCrypto
    private lateinit var cacheDir: File
    private val keyName = "QuestRemote@${Build.MODEL.replace(' ', '_')}"

    @Volatile
    private var connection: AdbConnection? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    var lastTarget: Target? = null
        private set

    /** Переподключаться ли самостоятельно после обрыва. */
    @Volatile
    var autoReconnect = true

    // Результат устаревшей попытки connect() не должен перетирать новую.
    @Volatile
    private var attempt = 0

    // Транспорт, ожидающий подтверждения в шлеме: закрываем его при «Отключить».
    @Volatile
    private var pendingTransport: AdbTransport? = null

    fun init(context: Context) {
        cacheDir = context.cacheDir
        crypto = AdbCrypto.loadOrCreate(File(context.filesDir, "adb"))
    }

    val isConnected: Boolean get() = _state.value is State.Connected

    val profile: DeviceProfile? get() = (_state.value as? State.Connected)?.profile

    suspend fun connectUsb(manager: UsbManager, device: UsbDevice) {
        lastTarget = Target.Usb(device.deviceName)
        connect("USB") { UsbTransport.open(manager, device) }
    }

    suspend fun connectWifi(host: String, port: Int) {
        lastTarget = Target.Wifi(host, port)
        connect("$host:$port") { TcpTransport(host, port) }
    }

    private suspend fun connect(via: String, openTransport: () -> AdbTransport) =
        withContext(Dispatchers.IO) {
            closeCurrent()
            val myAttempt = ++attempt
            _state.value = State.Connecting(via)
            try {
                val transport = openTransport()
                pendingTransport = transport
                val c = AdbConnection.connect(transport, crypto, keyName) {
                    if (myAttempt == attempt) _state.value = State.Connecting(via, awaitingApproval = true)
                }
                pendingTransport = null
                if (myAttempt != attempt) {
                    c.close()
                    return@withContext
                }
                c.onLost = { e -> lost(c, e) }
                connection = c
                startWatchdog(c)
                val probe = runCatching { c.shell(DeviceProfile.PROBE_COMMAND, timeoutS = 15) }.getOrDefault("")
                // Связь могла оборваться, пока опрашивали модель, — тогда не затираем ошибку.
                if (connection === c) {
                    _state.value = State.Connected(c.transportDescription, DeviceProfile.fromProbe(probe))
                }
            } catch (e: Exception) {
                pendingTransport = null
                if (myAttempt == attempt) _state.value = State.Failed(e.describe())
            }
        }

    /** Отключиться по просьбе пользователя: без автопереподключения. */
    fun disconnect() {
        lastTarget = null
        reconnectJob?.cancel()
        closeCurrent()
        _state.value = State.Disconnected
    }

    private fun closeCurrent() {
        attempt++
        pendingTransport?.let { runCatching { it.close() } }
        pendingTransport = null
        watchdog?.cancel()
        recording = null
        val c = connection
        connection = null
        c?.close()
    }

    private fun lost(c: AdbConnection, e: Throwable) {
        if (connection !== c) return
        watchdog?.cancel()
        connection = null
        recording = null
        c.close()
        val target = lastTarget
        val retry = autoReconnect && target is Target.Wifi
        _state.value = State.Failed("Связь потеряна: ${e.describe()}", reconnecting = retry)
        if (retry) scheduleWifiReconnect(target as Target.Wifi)
        // USB переподключается по событию «устройство подключено» (см. QuestRemoteApp).
    }

    private fun scheduleWifiReconnect(target: Target.Wifi) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (i in 1..RECONNECT_ATTEMPTS) {
                delay(2_000L * i)
                if (lastTarget != target || isConnected || _state.value is State.Connecting) return@launch
                connectWifi(target.host, target.port)
                if (isConnected) return@launch
            }
            if (!isConnected) _state.value = State.Failed("Не удалось переподключиться к ${target.host}")
        }
    }

    /**
     * Раз в 10 с проверяем, что шлем отвечает. Без этого «зависшая» связь
     * выглядит как подключённая, а команды просто не выполняются.
     */
    private fun startWatchdog(c: AdbConnection) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (connection === c) {
                delay(WATCHDOG_PERIOD_MS)
                try {
                    c.shell("echo ok", timeoutS = WATCHDOG_TIMEOUT_S)
                } catch (e: IOException) {
                    lost(c, IOException("шлем не ответил на проверку связи (${e.message})", e))
                }
            }
        }
    }

    // ---- Команды ----

    /** Выполнить shell-команду на шлеме и вернуть её вывод. */
    suspend fun shell(command: String, timeoutS: Long = 60): String =
        withClient { it.shell(command, timeoutS).trimEnd() }

    /** Открыть служебный сервис adbd (`reboot:`, `tcpip:5555`) и вернуть его ответ. */
    suspend fun service(destination: String): String = withClient { c ->
        c.open(destination).use { String(it.readAllBytes(timeoutS = 15)).trim() }
    }

    /** Потоковая установка APK через `cmd package install -S` (Android 7+). */
    suspend fun install(apk: File, onProgress: (Int) -> Unit = {}): String = withClient { c ->
        val size = apk.length()
        c.open("exec:cmd package install -S $size -r -g").use { stream ->
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            apk.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    stream.write(buf, 0, n)
                    sent += n
                    onProgress((sent * 100 / size.coerceAtLeast(1)).toInt())
                }
            }
            String(stream.readAllBytes(timeoutS = 180)).trim()
        }
    }

    /** PNG текущего кадра шлема. */
    suspend fun screenshotBytes(): ByteArray = withClient { c ->
        val png = c.open("exec:screencap -p").use { it.readAllBytes(timeoutS = 30) }
        if (png.size < 8) throw IOException("Шлем вернул пустой скриншот")
        png
    }

    /** Скриншот текущего кадра шлема, сохранённый в кэш телефона. */
    suspend fun screenshot(): File {
        val png = screenshotBytes()
        return withContext(Dispatchers.IO) {
            File(cacheDir, "shots").apply { mkdirs() }
                .resolve("headset_${System.currentTimeMillis()}.png")
                .apply { writeBytes(png) }
        }
    }

    /** Поток к TCP-порту на самом шлеме (как `adb forward`), например к клиенту пульта. */
    suspend fun openTunnel(port: Int): AdbConnection.AdbStream = withClient { it.open("tcp:$port") }

    // ---- Файлы (протокол sync) ----

    suspend fun listDir(path: String): List<AdbSync.Entry> = withSync { it.list(path) }

    suspend fun pull(remote: String, dst: OutputStream, onProgress: (Long) -> Unit = {}) =
        withSync { it.pull(remote, dst, onProgress) }

    suspend fun push(src: InputStream, remote: String, onProgress: (Long) -> Unit = {}) =
        withSync { it.push(src, remote, onProgress = onProgress) }

    private suspend fun <T> withSync(block: (AdbSync) -> T): T = withClient { c ->
        AdbSync(c.open("sync:")).use(block)
    }

    // ---- Запись экрана ----

    private class Recording(val stream: AdbConnection.AdbStream, val remote: String)

    @Volatile
    private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    /** Запустить `screenrecord` на шлеме (не дольше 3 минут — ограничение Android). */
    suspend fun startRecording() = withClient { c ->
        if (recording != null) throw IOException("Запись уже идёт")
        val remote = "/sdcard/Movies/headset_rec_${System.currentTimeMillis() / 1000}.mp4"
        c.shell("mkdir -p /sdcard/Movies", 10)
        val stream = c.open("shell:screenrecord --time-limit 180 $remote")
        recording = Recording(stream, remote)
    }

    /** Остановить запись и скачать ролик в [dst]. Возвращает путь файла на шлеме. */
    suspend fun stopRecording(dst: OutputStream): String {
        val rec = recording ?: throw IOException("Запись не идёт")
        recording = null
        withClient { c ->
            // SIGINT — screenrecord корректно дописывает mp4 и завершается.
            c.shell("pkill -INT screenrecord", 10)
            val tail = try {
                String(rec.stream.readAllBytes(timeoutS = 20)).trim()
            } catch (e: AdbTimeoutException) {
                "" // процесс не закрыл поток сам — файл обычно уже дописан
            } finally {
                rec.stream.close()
            }
            if (tail.contains("not supported", ignoreCase = true) || tail.contains("error", ignoreCase = true)) {
                throw IOException("screenrecord: $tail")
            }
        }
        pull(rec.remote, dst)
        runCatching { shell("rm '${rec.remote}'") }
        return rec.remote
    }

    // ---- logcat ----

    /**
     * Поток строк logcat. Отмена сбора закрывает поток на шлеме.
     * [args] — доп. аргументы logcat, например `*:W` или `-s Unity`.
     */
    fun logcat(args: String): Flow<String> = callbackFlow {
        val c = connection ?: throw IOException("Шлем не подключён")
        val stream = c.open("shell:logcat -v time $args")
        val reader = launch(Dispatchers.IO) {
            try {
                stream.asInputStream().bufferedReader().forEachLine { trySend(it) }
            } catch (e: IOException) {
                if (isActive) trySend("--- logcat прерван: ${e.describe()}")
            }
            channel.close()
        }
        awaitClose {
            reader.cancel()
            stream.close()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> withClient(block: (AdbConnection) -> T): T = withContext(Dispatchers.IO) {
        val c = connection ?: throw IOException("Шлем не подключён")
        try {
            block(c)
        } catch (e: AdbTimeoutException) {
            // Команда повисла — значит, шлем больше не отвечает. Честно показываем обрыв.
            lost(c, e)
            throw e
        }
    }

    /** Строка в одинарных кавычках для shell: безопасно для пробелов и спецсимволов. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private const val WATCHDOG_PERIOD_MS = 10_000L
    private const val WATCHDOG_TIMEOUT_S = 10L
    private const val RECONNECT_ATTEMPTS = 5

    fun Throwable.describe(): String {
        val root = generateSequence(this) { it.cause }.last()
        return when (root) {
            is java.net.ConnectException, is java.net.SocketTimeoutException,
            is java.net.NoRouteToHostException ->
                "шлем не отвечает. Проверьте IP, Wi‑Fi и что на шлеме включён adb tcpip 5555"
            is java.net.UnknownHostException -> "неверный адрес"
            else -> message ?: root.message ?: javaClass.simpleName
        }
    }
}
