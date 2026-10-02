package com.prnoia.questremote.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Связь с Quest Remote Client на шлеме: JSON-строки поверх ADB-туннеля (`tcp:47800`)
 * или прямого TCP-подключения по Wi‑Fi. Протокол описан в client/…/Protocol.kt.
 */
class RemoteLink private constructor(
    input: InputStream,
    private val writer: (ByteArray) -> Unit,
    private val closer: AutoCloseable,
    val description: String,
) : AutoCloseable {

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val writeLock = Any()

    private val _status = MutableStateFlow<JSONObject?>(null)
    /** Последний статус плеера, присланный клиентом. */
    val status: StateFlow<JSONObject?> = _status.asStateFlow()

    @Volatile
    var isClosed = false
        private set

    /** Вызывается из потока чтения, когда связь оборвалась. */
    @Volatile
    var onClosed: ((String) -> Unit)? = null

    init {
        Thread({
            var reason = "клиент закрыл соединение"
            try {
                input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    when {
                        msg.has("id") -> pending.remove(msg.optInt("id"))?.complete(msg)
                        msg.optString("event") == "status" -> _status.value = msg
                    }
                }
            } catch (e: IOException) {
                reason = e.message ?: "ошибка чтения"
            }
            shutdown(reason)
        }, "remote-link").apply { isDaemon = true }.start()
    }

    /**
     * Отправить команду и дождаться ответа. Бросает [IOException] с текстом ошибки клиента,
     * если тот ответил `ok:false`.
     */
    suspend fun request(cmd: String, params: JSONObject = JSONObject(), timeoutMs: Long = 10_000): JSONObject {
        if (isClosed) throw IOException("Нет связи с клиентом")
        val id = nextId.getAndIncrement()
        val req = JSONObject(params.toString()).put("id", id).put("cmd", cmd)
        val answer = CompletableDeferred<JSONObject>()
        pending[id] = answer
        try {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) { writer((req.toString() + "\n").toByteArray(Charsets.UTF_8)) }
            }
            val res = withTimeout(timeoutMs) { answer.await() }
            if (!res.optBoolean("ok")) throw IOException(res.optString("error", "ошибка клиента"))
            if (cmd == "status") _status.value = res
            return res
        } finally {
            pending.remove(id)
        }
    }

    private fun shutdown(reason: String) {
        if (isClosed) return
        isClosed = true
        // Закрытие ADB-потока отправляет пакет по USB — не делаем это в UI-потоке.
        Thread { runCatching { closer.close() } }.start()
        pending.values.forEach { it.completeExceptionally(IOException(reason)) }
        pending.clear()
        onClosed?.invoke(reason)
    }

    override fun close() = shutdown("отключено")

    companion object {
        const val PORT = 47800

        /** Через уже установленное ADB-подключение (USB или Wi‑Fi ADB). PIN не нужен. */
        suspend fun viaAdb(): RemoteLink {
            val stream = try {
                QuestController.openTunnel(PORT)
            } catch (e: IOException) {
                throw IOException("Клиент на шлеме не отвечает. Установите/запустите его кнопкой ниже", e)
            }
            val link = RemoteLink(stream.asInputStream(), { stream.write(it) }, stream, "через ADB")
            link.request("ping", timeoutMs = 5_000)
            return link
        }

        /** Напрямую по Wi‑Fi: режим разработчика не нужен, но нужен PIN с экрана клиента. */
        suspend fun viaWifi(host: String, pin: String): RemoteLink {
            val socket = withContext(Dispatchers.IO) {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, PORT), 5_000)
                }
            }
            val out = socket.getOutputStream()
            val link = RemoteLink(socket.getInputStream(), { out.write(it); out.flush() }, socket, "Wi‑Fi $host")
            try {
                link.request("hello", JSONObject().put("pin", pin), timeoutMs = 5_000)
            } catch (e: Exception) {
                link.close()
                throw e
            }
            return link
        }
    }
}
