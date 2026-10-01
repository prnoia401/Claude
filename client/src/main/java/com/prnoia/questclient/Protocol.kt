package com.prnoia.questclient

/**
 * Протокол пульта: по одной JSON-строке на сообщение, TCP-порт [PORT].
 *
 * Запрос:  {"id":1,"cmd":"play","path":"/sdcard/Movies/film.mp4"}
 * Ответ:   {"id":1,"ok":true, ...} или {"id":1,"ok":false,"error":"..."}
 * Событие: {"event":"status","state":"playing","position":1234,...} — без id, рассылается всем.
 *
 * Подключения с localhost (ADB-туннель `tcp:47800`) доверенные. По Wi‑Fi сначала
 * нужно отправить {"cmd":"hello","pin":"1234"} с PIN, который показывает клиент.
 */
object Protocol {
    const val PORT = 47800
    const val NSD_TYPE = "_questremote._tcp."
    const val ACTION_COMMAND = "com.prnoia.questclient.COMMAND"
    const val VERSION = 1
}
