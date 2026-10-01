package com.prnoia.questclient

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import org.json.JSONObject

/** Состояние встроенного плеера; управляется только из главного потока. */
object Player {

    enum class State { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

    @Volatile var state = State.IDLE
    @Volatile var source: String? = null
    @Volatile var position = 0
    @Volatile var duration = 0
    @Volatile var loop = false
    @Volatile var error: String? = null

    /** Открытый экран плеера, если он сейчас на экране. */
    var activity: PlayerActivity? = null

    fun play(context: Context, src: String, loop: Boolean, startMs: Int) {
        source = src
        this.loop = loop
        error = null
        state = State.LOADING
        val a = activity
        if (a != null) {
            a.load(src, loop, startMs)
        } else {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(PlayerActivity.EXTRA_SOURCE, src)
                    .putExtra(PlayerActivity.EXTRA_LOOP, loop)
                    .putExtra(PlayerActivity.EXTRA_START, startMs)
            )
        }
    }

    fun requireActivity(): PlayerActivity =
        activity ?: throw IllegalStateException("Плеер не открыт — сначала команда play")

    fun volumePercent(context: Context): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolumePercent(context: Context, percent: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    fun status(context: Context): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("source", source ?: JSONObject.NULL)
        .put("position", position)
        .put("duration", duration)
        .put("loop", loop)
        .put("volume", volumePercent(context))
        .put("error", error ?: JSONObject.NULL)
}
