package com.prnoia.questremote

import android.app.Application
import com.prnoia.questremote.adb.QuestController

class QuestRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        QuestController.init(this)
    }
}
