package com.prnoia.questclient

import android.app.Application
import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

class ClientApp : Application() {
    companion object {
        private const val PREFS = "client"

        /** PIN для подключения пульта по Wi‑Fi. Генерируется один раз. */
        fun pin(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            return prefs.getString("pin", null) ?: newPin(context)
        }

        fun newPin(context: Context): String {
            val pin = (1000..9999).random().toString()
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("pin", pin).apply()
            return pin
        }

        fun ipAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
