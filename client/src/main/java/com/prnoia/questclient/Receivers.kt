package com.prnoia.questclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Команда через ADB без сети:
 * `am broadcast -n com.prnoia.questclient/.CommandReceiver -a com.prnoia.questclient.COMMAND --es json '{"cmd":"pause"}'`
 * Результат пишется в resultData (виден в выводе am broadcast).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_COMMAND) return
        val json = intent.getStringExtra("json") ?: return
        val pending = goAsync()
        Thread {
            val res = runCatching { CommandHandler(context.applicationContext).handle(JSONObject(json)) }
                .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
            pending.resultData = res.toString()
            pending.finish()
        }.start()
    }
}

/** Сервер команд поднимается сам после включения шлема. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { CommandService.start(context) }
        }
    }
}
