package com.prnoia.questremote.adb

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Проверяет клиент против поддельного adbd на localhost:
 * авторизацию (подпись токена и формат публичного ключа), shell и потоковую запись.
 */
class AdbConnectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private lateinit var crypto: AdbCrypto

    @Before
    fun setUp() {
        server = ServerSocket(0)
        crypto = AdbCrypto.loadOrCreate(tmp.newFolder("keys"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun unknownKeyIsSentThenKnownKeySignatureVerifies() {
        val learnedKey = AtomicReference<PublicKey>()
        var prompted = false

        // 1-е подключение: шлем не знает ключ → запрашивает RSAPUBLICKEY.
        val first = fakeDevice { dev ->
            dev.expect(A_CNXN)
            dev.send(A_AUTH, 1, 0, Random.nextBytes(20))
            dev.expect(A_AUTH).also { assertEquals(2, it.arg0) }
            dev.send(A_AUTH, 1, 0, Random.nextBytes(20))
            val pub = dev.expect(A_AUTH)
            assertEquals(3, pub.arg0)
            learnedKey.set(parseAdbPublicKey(pub.payload))
            dev.send(A_CNXN, 0x01000001, 4096, "device::ro.product.model=Quest 2;\u0000".toByteArray())
        }
        AdbConnection.connect(TcpTransport("127.0.0.1", server.localPort), crypto, "test@host") {
            prompted = true
        }.close()
        first.join()
        assertTrue("должен был появиться запрос в шлеме", prompted)

        // 2-е подключение: шлем знает ключ и проверяет подпись токена.
        val verified = AtomicReference<Boolean>()
        val second = fakeDevice { dev ->
            dev.expect(A_CNXN)
            val token = Random.nextBytes(20)
            dev.send(A_AUTH, 1, 0, token)
            val sig = dev.expect(A_AUTH)
            verified.set(
                Signature.getInstance("NONEwithRSA").run {
                    initVerify(learnedKey.get())
                    update(SHA1_DIGEST_INFO + token)
                    verify(sig.payload)
                }
            )
            dev.send(A_CNXN, 0x01000001, 4096, "device::\u0000".toByteArray())
        }
        val c = AdbConnection.connect(TcpTransport("127.0.0.1", server.localPort), crypto, "test@host")
        second.join()
        c.close()
        assertEquals(true, verified.get())
    }

    @Test
    fun shellAndStreamingWrite() {
        val apk = Random.nextBytes(10_000)
        val received = java.io.ByteArrayOutputStream()

        val device = fakeDevice { dev ->
            dev.expect(A_CNXN)
            dev.send(A_CNXN, 0x01000001, 4096, "device::\u0000".toByteArray())

            // shell:echo hi
            val open = dev.expect(A_OPEN)
            assertEquals("shell:echo hi\u0000", String(open.payload))
            dev.send(A_OKAY, 100, open.arg0)
            dev.send(A_WRTE, 100, open.arg0, "hi\n".toByteArray())
            dev.expect(A_OKAY)
            dev.send(A_CLSE, 100, open.arg0)

            // exec:… с потоковой отправкой данных (как установка APK)
            val exec = dev.expect(A_OPEN)
            assertTrue(String(exec.payload).startsWith("exec:cmd package install -S ${apk.size}"))
            dev.send(A_OKAY, 200, exec.arg0)
            while (received.size() < apk.size) {
                val w = dev.expect(A_WRTE)
                assertTrue("пакет больше maxPayload", w.payload.size <= 4096)
                received.write(w.payload)
                dev.send(A_OKAY, 200, exec.arg0)
            }
            dev.send(A_WRTE, 200, exec.arg0, "Success\n".toByteArray())
            dev.expect(A_OKAY)
            dev.send(A_CLSE, 200, exec.arg0)
        }

        val c = AdbConnection.connect(TcpTransport("127.0.0.1", server.localPort), crypto, "t")
        assertEquals("hi\n", c.shell("echo hi"))
        val out = c.open("exec:cmd package install -S ${apk.size} -r -g").use { s ->
            s.write(apk)
            String(s.readAllBytes())
        }
        device.join()
        c.close()
        assertEquals("Success\n", out)
        assertArrayEquals(apk, received.toByteArray())
    }

    // ---- поддельный adbd ----

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private class Device(socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()

        fun expect(cmd: Int): Msg {
            val h = ByteArray(24).also(input::readFully)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val m = Msg(b.int, b.int, b.int, ByteArray(b.int))
            val checksum = b.int
            assertEquals("magic", m.cmd.inv(), b.int)
            input.readFully(m.payload)
            assertEquals("checksum", m.payload.sumOf { it.toInt() and 0xFF }, checksum)
            assertEquals("команда", Integer.toHexString(cmd), Integer.toHexString(m.cmd))
            return m
        }

        fun send(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv())
            output.write(h.array())
            output.write(payload)
            output.flush()
        }
    }

    private var failure: Throwable? = null

    private fun fakeDevice(script: (Device) -> Unit): Thread = thread {
        try {
            server.accept().use { script(Device(it)) }
        } catch (t: Throwable) {
            failure = t
        }
    }

    // Ошибки в потоке устройства пробрасываем в тест.
    @After
    fun rethrowDeviceFailure() {
        failure?.let { throw it }
    }

    /** Разбор формата ключа adbd (см. adb/crypto/rsa_2048_key.cpp) обратно в RSA-ключ. */
    private fun parseAdbPublicKey(payload: ByteArray): PublicKey {
        val text = String(payload).trimEnd('\u0000')
        assertTrue(text.endsWith(" test@host"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int
        val nBytes = ByteArray(256)
        b.get(nBytes)
        val rrBytes = ByteArray(256)
        b.get(rrBytes)
        val e = b.int
        val n = BigInteger(1, nBytes.reversedArray())
        val rr = BigInteger(1, rrBytes.reversedArray())
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv * n ≡ -1 (mod 2^32), rr = 2^4096 mod n — так их проверяет adbd.
        assertEquals(r32 - BigInteger.ONE, BigInteger.valueOf(n0inv.toLong() and 0xFFFFFFFFL).multiply(n).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(e.toLong())))
    }

    private companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
    }
}
