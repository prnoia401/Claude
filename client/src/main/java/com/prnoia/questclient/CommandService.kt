package com.prnoia.questclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/** Фоновая служба: держит сервер команд, объявляет себя в сети и рассылает статус плеера. */
class CommandService : Service() {

    private var server: CommandServer? = null
    private var nsd: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastStatus = ""

    private val ticker = object : Runnable {
        override fun run() {
            Player.activity?.tick()
            val status = Player.status(this@CommandService)
            val key = status.toString()
            if (key != lastStatus) {
                lastStatus = key
                server?.broadcast(JSONObject(key).put("event", "status"))
            }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val s = CommandServer(CommandHandler(this)) { ClientApp.pin(this) }
        try {
            s.start()
            server = s
            running = true
            ClientLog.add("сервер слушает порт ${Protocol.PORT}")
        } catch (e: Exception) {
            ClientLog.add("не удалось открыть порт ${Protocol.PORT}: ${e.message}")
        }
        registerNsd()
        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        nsd?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        server?.stop()
        running = false
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Пульт", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Quest Remote Client")
            .setContentText("Ждёт команды. PIN: ${ClientApp.pin(this)}")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    /** Объявление в локальной сети, чтобы телефон нашёл шлем без ввода IP. */
    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = "QuestRemote ${Build.MODEL}"
            serviceType = Protocol.NSD_TYPE
            port = Protocol.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = listener
        }
    }

    companion object {
        private const val CHANNEL = "remote"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CommandService::class.java))
        }
    }
}
