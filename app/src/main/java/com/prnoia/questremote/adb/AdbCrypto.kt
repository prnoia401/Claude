package com.prnoia.questremote.adb

import java.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA-ключ, которым телефон представляется шлему (аналог ~/.android/adbkey на ПК).
 * После «Всегда разрешать» в шлеме повторного подтверждения не будет.
 */
class AdbCrypto private constructor(private val keyPair: KeyPair) {

    /** Подпись 20-байтного токена AUTH: PKCS#1 v1.5 с префиксом SHA-1, как у adb. */
    fun sign(token: ByteArray): ByteArray {
        val block = ByteArray(KEY_BYTES)
        block[1] = 0x01
        val tail = SHA1_DIGEST_INFO + token
        val padEnd = KEY_BYTES - tail.size - 1
        for (i in 2 until padEnd) block[i] = 0xFF.toByte()
        block[padEnd] = 0x00
        tail.copyInto(block, padEnd + 1)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keyPair.private)
            doFinal(block)
        }
    }

    /** Публичный ключ в формате adbd: base64(RSAPublicKey struct) + " имя\0". */
    fun adbPublicKey(name: String): ByteArray {
        val key = keyPair.public as RSAPublicKey
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(KEY_WORDS)
        buf.putInt(n0inv.toInt())
        putWordsLe(buf, n)
        putWordsLe(buf, rr)
        buf.putInt(key.publicExponent.toInt())

        val encoded = Base64.getEncoder().encodeToString(buf.array())
        return "$encoded $name\u0000".toByteArray()
    }

    private fun putWordsLe(buf: ByteBuffer, value: BigInteger) {
        var v = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        repeat(KEY_WORDS) {
            buf.putInt(v.and(mask).toInt())
            v = v.shiftRight(32)
        }
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8
        private const val KEY_WORDS = KEY_BYTES / 4

        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        fun loadOrCreate(dir: File): AdbCrypto {
            dir.mkdirs()
            val privFile = File(dir, "adbkey.pk8")
            val pubFile = File(dir, "adbkey.x509")
            val factory = KeyFactory.getInstance("RSA")
            if (privFile.exists() && pubFile.exists()) {
                runCatching {
                    val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    return AdbCrypto(KeyPair(pub, priv))
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.genKeyPair()
            privFile.writeBytes(pair.private.encoded)
            pubFile.writeBytes(pair.public.encoded)
            return AdbCrypto(pair)
        }
    }
}
