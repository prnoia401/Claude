package com.prnoia.questremote.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Минимальный ADB-клиент (протокол adb host ↔ adbd) поверх любого [AdbTransport].
 * Поддерживает CNXN/AUTH/OPEN/OKAY/WRTE/CLSE — этого хватает для shell:, exec: и установки APK.
 */
class AdbConnection private constructor(private val transport: AdbTransport) : AutoCloseable {

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private val writeLock = Any()
    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private var maxPayload = 4096

    @Volatile
    var isClosed = false
        private set
    var deviceBanner = ""
        private set

    /** Вызывается из потока чтения, если связь оборвалась не по нашей инициативе. */
    @Volatile
    var onLost: ((IOException) -> Unit)? = null

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(payload.size).putInt(checksum(payload)).putInt(command.inv())
            .array()
        synchronized(writeLock) {
            transport.write(header, header.size)
            if (payload.isNotEmpty()) transport.write(payload, payload.size)
        }
    }

    private fun receive(): Message {
        val header = ByteArray(HEADER_SIZE)
        transport.readFully(header, HEADER_SIZE)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int // checksum: с версии протокола 0x01000001 не проверяется
        val magic = b.int
        if (magic != command.inv()) throw IOException("Повреждённый ADB-заголовок")
        if (length < 0 || length > MAX_ACCEPTED_PAYLOAD) throw IOException("Слишком большой пакет: $length")
        val payload = if (length == 0) EMPTY else ByteArray(length).also { transport.readFully(it, length) }
        return Message(command, arg0, arg1, payload)
    }

    private fun handshake(crypto: AdbCrypto, keyName: String, onAuthPrompt: () -> Unit) {
        send(A_CNXN, VERSION, MAX_PAYLOAD_OURS, "host::features=shell_v2,cmd\u0000".toByteArray())
        var signatureSent = false
        while (true) {
            val m = receive()
            when (m.command) {
                A_CNXN -> {
                    maxPayload = minOf(m.arg1, MAX_PAYLOAD_OURS)
                    deviceBanner = String(m.payload).trimEnd('\u0000')
                    return
                }
                A_AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw IOException("Неожиданный AUTH ${m.arg0}")
                    if (!signatureSent) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, crypto.sign(m.payload))
                        signatureSent = true
                    } else {
                        // Ключ шлему незнаком: отправляем публичный ключ, в шлеме появится
                        // диалог «Разрешить отладку по USB?». Ждём CNXN после нажатия.
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.adbPublicKey(keyName))
                        onAuthPrompt()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun startReader() {
        Thread({
            try {
                while (!isClosed) dispatch(receive())
            } catch (e: IOException) {
                if (!isClosed) {
                    closeWith(e)
                    onLost?.invoke(e)
                }
            }
        }, "adb-reader").apply { isDaemon = true }.start()
    }

    private fun dispatch(m: Message) {
        // Ответы шлема адресованы нашему local id в arg1.
        val stream = streams[m.arg1] ?: return
        when (m.command) {
            A_OKAY -> stream.onOkay(m.arg0)
            A_WRTE -> {
                stream.onData(m.payload)
                send(A_OKAY, stream.localId, m.arg0)
            }
            A_CLSE -> {
                streams.remove(stream.localId)
                stream.onRemoteClose()
            }
        }
    }

    /** Открыть сервис adbd, например `shell:ls` или `exec:screencap -p`. */
    fun open(destination: String): AdbStream {
        if (isClosed) throw IOException("Соединение закрыто")
        val stream = AdbStream(nextLocalId.getAndIncrement())
        streams[stream.localId] = stream
        send(A_OPEN, stream.localId, 0, "$destination\u0000".toByteArray())
        stream.awaitOpen()
        return stream
    }

    /** Запустить команду и дождаться всего её вывода. */
    fun shell(command: String): String =
        open("shell:$command").use { String(it.readAllBytes()) }

    private fun closeWith(cause: IOException) {
        isClosed = true
        streams.values.forEach { it.onRemoteClose(cause) }
        streams.clear()
        runCatching { transport.close() }
    }

    override fun close() {
        if (isClosed) return
        closeWith(IOException("Соединение закрыто"))
    }

    val transportDescription: String get() = transport.description

    inner class AdbStream(val localId: Int) : AutoCloseable {
        private val incoming = LinkedBlockingQueue<ByteArray>()
        private val okays = LinkedBlockingQueue<Int>()
        @Volatile private var remoteId = 0
        @Volatile private var closedBy: IOException? = null
        @Volatile private var remoteClosed = false

        internal fun onOkay(remote: Int) {
            remoteId = remote
            okays.offer(remote)
        }

        internal fun onData(data: ByteArray) {
            incoming.offer(data)
        }

        internal fun onRemoteClose(cause: IOException? = null) {
            closedBy = cause
            remoteClosed = true
            incoming.offer(EOF)
            okays.offer(-1)
        }

        internal fun awaitOpen() {
            val r = okays.poll(OPEN_TIMEOUT_S, TimeUnit.SECONDS)
                ?: throw IOException("Шлем не ответил на открытие потока")
            if (r == -1) throw closedBy ?: IOException("Шлем отклонил команду")
        }

        /** Следующий блок данных или null в конце потока. */
        fun read(): ByteArray? {
            val chunk = incoming.take()
            if (chunk === EOF) {
                incoming.offer(EOF)
                closedBy?.let { throw it }
                return null
            }
            return chunk
        }

        fun readAllBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) out.write(read() ?: break)
            return out.toByteArray()
        }

        /** Входящие данные как InputStream — удобно для построчного чтения logcat. */
        fun asInputStream(): InputStream = object : InputStream() {
            private var current: ByteArray = EMPTY
            private var pos = 0

            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= current.size) {
                    current = this@AdbStream.read() ?: return -1
                    pos = 0
                }
                val n = minOf(len, current.size - pos)
                current.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }

        /** Отправить данные в поток с учётом подтверждений (flow control). */
        fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
            var p = off
            val end = off + len
            while (p < end) {
                if (remoteClosed) throw closedBy ?: IOException("Поток закрыт шлемом")
                val n = minOf(maxPayload, end - p)
                send(A_WRTE, localId, remoteId, data.copyOfRange(p, p + n))
                val ack = okays.poll(WRITE_ACK_TIMEOUT_S, TimeUnit.SECONDS)
                    ?: throw IOException("Шлем не подтвердил запись")
                if (ack == -1) throw closedBy ?: IOException("Поток закрыт шлемом")
                p += n
            }
        }

        override fun close() {
            if (streams.remove(localId) != null && !isClosed) {
                runCatching { send(A_CLSE, localId, remoteId) }
            }
            onRemoteClose()
        }
    }

    companion object {
        private const val HEADER_SIZE = 24
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD_OURS = 256 * 1024
        private const val MAX_ACCEPTED_PAYLOAD = 1024 * 1024
        private const val OPEN_TIMEOUT_S = 15L
        private const val WRITE_ACK_TIMEOUT_S = 30L
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0)

        private fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xFF }

        /**
         * Установить соединение. Блокирует, пока пользователь не подтвердит отладку в шлеме.
         * [onAuthPrompt] вызывается, когда шлем показал диалог разрешения.
         */
        fun connect(
            transport: AdbTransport,
            crypto: AdbCrypto,
            keyName: String,
            onAuthPrompt: () -> Unit = {},
        ): AdbConnection {
            val c = AdbConnection(transport)
            try {
                c.handshake(crypto, keyName, onAuthPrompt)
            } catch (e: Exception) {
                runCatching { transport.close() }
                throw e
            }
            c.startReader()
            return c
        }
    }
}
