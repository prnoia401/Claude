package com.prnoia.questremote.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * ADB по кабелю: телефон — USB-хост, шлем — USB-устройство с ADB-интерфейсом
 * (class 0xFF, subclass 0x42, protocol 0x01).
 */
class UsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    override val description: String,
) : AdbTransport {

    override fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val chunk = minOf(len - off, MAX_TRANSFER)
            val n = connection.bulkTransfer(epIn, buf, off, chunk, 0)
            if (n < 0) throw IOException("USB: чтение не удалось (кабель отключён?)")
            off += n
        }
    }

    override fun write(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val chunk = minOf(len - off, MAX_TRANSFER)
            val n = connection.bulkTransfer(epOut, buf, off, chunk, WRITE_TIMEOUT_MS)
            if (n < 0) throw IOException("USB: шлем не принял данные за ${WRITE_TIMEOUT_MS / 1000} с")
            off += n
        }
        // Как в оригинальном adb (usb_linux.cpp, zero_mask): если длина кратна размеру
        // USB-пакета, добиваем нулевым пакетом. Иначе adbd не видит конца передачи,
        // склеивает её со следующим заголовком и рвёт соединение.
        if (len > 0 && len % epOut.maxPacketSize == 0) {
            if (connection.bulkTransfer(epOut, buf, 0, 0, WRITE_TIMEOUT_MS) < 0) {
                throw IOException("USB: не удалось отправить нулевой пакет")
            }
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        connection.close()
    }

    companion object {
        private const val MAX_TRANSFER = 16 * 1024
        private const val WRITE_TIMEOUT_MS = 15_000

        fun findAdbInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01
            }

        fun open(manager: UsbManager, device: UsbDevice): UsbTransport {
            val iface = findAdbInterface(device)
                ?: throw IOException("На устройстве нет ADB-интерфейса. Включите режим разработчика на шлеме")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("ADB-интерфейс без bulk-эндпоинтов")
            val connection = manager.openDevice(device)
                ?: throw IOException("Нет доступа к USB-устройству (разрешение не выдано)")
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("USB-интерфейс занят другим приложением")
            }
            val name = device.productName ?: "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
            return UsbTransport(connection, iface, epIn, epOut, "USB $name")
        }
    }
}
