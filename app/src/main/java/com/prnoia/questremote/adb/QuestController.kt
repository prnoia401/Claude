package com.prnoia.questremote.adb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
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

/**
 * Единая точка управления шлемом. Живёт в Application, поэтому переживает
 * повороты экрана и смену вкладок.
 */
object QuestController {

    sealed interface State {
        data object Disconnected : State
        data class Connecting(val via: String, val awaitingApproval: Boolean = false) : State
        data class Connected(val via: String, val model: String) : State
        data class Failed(val message: String) : State
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

    suspend fun connectUsb(manager: UsbManager, device: UsbDevice) =
        connect("USB") { UsbTransport.open(manager, device) }

    suspend fun connectWifi(host: String, port: Int) =
        connect("$host:$port") { TcpTransport(host, port) }

    private suspend fun connect(via: String, openTransport: () -> AdbTransport) =
        withContext(Dispatchers.IO) {
            disconnect()
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
                val model = runCatching { c.shell("getprop ro.product.model").trim() }.getOrDefault("")
                // Связь могла оборваться, пока спрашивали модель, — тогда не затираем ошибку.
                if (connection === c) {
                    _state.value = State.Connected(c.transportDescription, model.ifEmpty { "Android" })
                }
            } catch (e: Exception) {
                if (myAttempt == attempt) _state.value = State.Failed(e.describe())
            }
        }

    fun disconnect() {
        attempt++
        pendingTransport?.let { runCatching { it.close() } }
        pendingTransport = null
        watchdog?.cancel()
        val c = connection
        connection = null
        c?.close()
        _state.value = State.Disconnected
    }

    private fun lost(c: AdbConnection, e: Throwable) {
        if (connection !== c) return
        watchdog?.cancel()
        connection = null
        c.close()
        _state.value = State.Failed("Связь потеряна: ${e.describe()}")
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

    /** Выполнить shell-команду на шлеме и вернуть её вывод. */
    suspend fun shell(command: String): String = withClient { it.shell(command).trimEnd() }

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
                    onProgress((sent * 100 / size).toInt())
                }
            }
            String(stream.readAllBytes(timeoutS = 180)).trim()
        }
    }

    /** Скриншот текущего кадра шлема, сохранённый в кэш телефона. */
    suspend fun screenshot(): File = withClient { c ->
        val png = c.open("exec:screencap -p").use { it.readAllBytes(timeoutS = 30) }
        if (png.size < 8) throw IOException("Шлем вернул пустой скриншот")
        File(cacheDir, "shots").apply { mkdirs() }
            .resolve("quest_${System.currentTimeMillis()}.png")
            .apply { writeBytes(png) }
    }

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

    private const val WATCHDOG_PERIOD_MS = 10_000L
    private const val WATCHDOG_TIMEOUT_S = 10L

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
