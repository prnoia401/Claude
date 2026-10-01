package com.prnoia.questremote.adb

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Протокол `sync:` из adb — тот же, что у `adb push/pull/ls`.
 * Один экземпляр — одна сессия; закрывайте через [close].
 */
class AdbSync(private val stream: AdbConnection.AdbStream) : AutoCloseable {

    data class Entry(val name: String, val mode: Int, val size: Long, val mtimeSec: Long) {
        val isDirectory: Boolean get() = mode and S_IFMT == S_IFDIR
        val isLink: Boolean get() = mode and S_IFMT == S_IFLNK
    }

    private val input = DataInputStream(stream.asInputStream())

    fun stat(path: String): Entry {
        request("STAT", path)
        expectId("STAT")
        val mode = readInt()
        val size = readInt().toLong() and 0xFFFFFFFFL
        val time = readInt().toLong() and 0xFFFFFFFFL
        if (mode == 0) throw IOException("Нет такого файла: $path")
        return Entry(path.substringAfterLast('/'), mode, size, time)
    }

    fun list(path: String): List<Entry> {
        request("LIST", path)
        val out = mutableListOf<Entry>()
        while (true) {
            val id = readId()
            val mode = readInt()
            val size = readInt().toLong() and 0xFFFFFFFFL
            val time = readInt().toLong() and 0xFFFFFFFFL
            val nameLen = readInt()
            when (id) {
                "DENT" -> {
                    val name = String(readBytes(nameLen))
                    if (name != "." && name != "..") out += Entry(name, mode, size, time)
                }
                "DONE" -> return out
                "FAIL" -> throw IOException(String(readBytes(nameLen)))
                else -> throw IOException("sync LIST: неожиданный ответ $id")
            }
        }
    }

    /** Скачать файл со шлема в [dst]. [onProgress] получает число принятых байт. */
    fun pull(remote: String, dst: OutputStream, onProgress: (Long) -> Unit = {}) {
        request("RECV", remote)
        var total = 0L
        while (true) {
            val id = readId()
            val len = readInt()
            when (id) {
                "DATA" -> {
                    copyExactly(len, dst)
                    total += len
                    onProgress(total)
                }
                "DONE" -> return
                "FAIL" -> throw IOException(String(readBytes(len)))
                else -> throw IOException("sync RECV: неожиданный ответ $id")
            }
        }
    }

    /** Загрузить [src] на шлем по пути [remote]. [onProgress] получает число отправленных байт. */
    fun push(
        src: InputStream,
        remote: String,
        mode: Int = 0x1A4 /* 0644 */,
        mtimeSec: Long = System.currentTimeMillis() / 1000,
        onProgress: (Long) -> Unit = {},
    ) {
        request("SEND", "$remote,${S_IFREG or mode}")
        val buf = ByteArray(8 + MAX_DATA)
        var total = 0L
        while (true) {
            val n = src.read(buf, 8, MAX_DATA)
            if (n < 0) break
            if (n == 0) continue
            // Заголовок и данные — одним пакетом, чтобы не ждать лишнего подтверждения.
            header("DATA", n).copyInto(buf)
            stream.write(buf, 0, 8 + n)
            total += n
            onProgress(total)
        }
        stream.write(header("DONE", mtimeSec.toInt()))
        when (val id = readId()) {
            "OKAY" -> readInt()
            "FAIL" -> throw IOException(String(readBytes(readInt())))
            else -> throw IOException("sync SEND: неожиданный ответ $id")
        }
    }

    fun pull(remote: String, dst: File, onProgress: (Long) -> Unit = {}) =
        dst.outputStream().use { pull(remote, it, onProgress) }

    override fun close() {
        runCatching { stream.write(header("QUIT", 0)) }
        stream.close()
    }

    // ---- низкий уровень ----

    private fun request(id: String, path: String) {
        val p = path.toByteArray()
        if (p.size > 1024) throw IOException("Слишком длинный путь")
        stream.write(header(id, p.size) + p)
    }

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(value).array()

    private fun readId(): String = String(readBytes(4))

    private fun expectId(expected: String) {
        val id = readId()
        if (id == "FAIL") throw IOException(String(readBytes(readInt())))
        if (id != expected) throw IOException("sync: ждали $expected, получили $id")
    }

    private fun readInt(): Int = Integer.reverseBytes(input.readInt())

    private fun readBytes(n: Int): ByteArray = ByteArray(n).also { input.readFully(it) }

    private fun copyExactly(n: Int, out: OutputStream) {
        val buf = ByteArray(minOf(n, 64 * 1024).coerceAtLeast(1))
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size))
            if (r < 0) throw IOException("sync: поток оборвался")
            out.write(buf, 0, r)
            left -= r
        }
    }

    companion object {
        private const val MAX_DATA = 64 * 1024
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000
        private const val S_IFLNK = 0xA000
    }
}
