package com.prnoia.questremote.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Поиск устройств с открытым ADB-портом в текущей Wi‑Fi подсети /24. */
object NetworkScanner {

    fun phoneWifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    suspend fun scan(ownIp: String, port: Int = 5555): List<String> = withContext(Dispatchers.IO) {
        val prefix = ownIp.substringBeforeLast('.')
        val limit = Semaphore(48)
        coroutineScope {
            (1..254).map { "$prefix.$it" }
                .filter { it != ownIp }
                .map { host -> async { limit.withPermit { host.takeIf { isOpen(it, port) } } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun isOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 300); true }
    } catch (_: Exception) {
        false
    }
}
