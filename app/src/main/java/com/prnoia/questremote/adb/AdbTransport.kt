package com.prnoia.questremote.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Канал, по которому ходят ADB-сообщения: кабель USB или Wi‑Fi. */
interface AdbTransport : AutoCloseable {
    /** Прочитать ровно [len] байт или бросить IOException. */
    fun readFully(buf: ByteArray, len: Int)
    fun write(buf: ByteArray, len: Int)
    val description: String
}

class TcpTransport(host: String, port: Int) : AdbTransport {
    private val socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), 5_000)
    }
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override val description = "Wi‑Fi $host:$port"

    override fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("Соединение закрыто шлемом")
            off += n
        }
    }

    override fun write(buf: ByteArray, len: Int) {
        output.write(buf, 0, len)
        output.flush()
    }

    override fun close() = socket.close()
}
