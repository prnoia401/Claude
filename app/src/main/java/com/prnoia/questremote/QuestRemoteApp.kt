package com.prnoia.questremote

import android.app.Application
import com.prnoia.questremote.adb.AutoConnect
import com.prnoia.questremote.adb.QuestController

class QuestRemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        QuestController.init(this)
        QuestController.autoReconnect =
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_RECONNECT, true)
        // Кабель воткнули/выдернули, запуск приложения — подключение без нажатий.
        AutoConnect.start(this)
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
