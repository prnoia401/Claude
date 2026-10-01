package com.prnoia.questclient

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView

/** Полноэкранный плеер, которым управляет пульт. */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView
    private var mediaPlayer: MediaPlayer? = null
    private var pendingStart = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = VideoView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(video, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            ))
        })
        video.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = Player.loop
            Player.duration = mp.duration
            if (pendingStart > 0) video.seekTo(pendingStart)
            video.start()
            Player.state = Player.State.PLAYING
        }
        video.setOnCompletionListener {
            Player.state = Player.State.ENDED
            Player.position = Player.duration
        }
        video.setOnErrorListener { _, what, extra ->
            Player.state = Player.State.ERROR
            Player.error = "Ошибка воспроизведения ($what/$extra)"
            true
        }
        Player.activity = this
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val src = intent?.getStringExtra(EXTRA_SOURCE) ?: return
        load(src, intent.getBooleanExtra(EXTRA_LOOP, false), intent.getIntExtra(EXTRA_START, 0))
    }

    fun load(src: String, loop: Boolean, startMs: Int) {
        Player.source = src
        Player.loop = loop
        Player.state = Player.State.LOADING
        Player.position = 0
        Player.duration = 0
        pendingStart = startMs
        mediaPlayer = null
        video.setVideoURI(if ("://" in src) Uri.parse(src) else Uri.fromFile(java.io.File(src)))
    }

    fun pause() {
        video.pause()
        if (Player.state == Player.State.PLAYING) Player.state = Player.State.PAUSED
    }

    fun resume() {
        if (Player.state == Player.State.ENDED) video.seekTo(0)
        video.start()
        Player.state = Player.State.PLAYING
    }

    fun seekTo(ms: Int) {
        val target = ms.coerceIn(0, maxOf(Player.duration, 0))
        video.seekTo(target)
        Player.position = target
    }

    fun applyLoop() {
        mediaPlayer?.isLooping = Player.loop
    }

    /** Вызывается раз в секунду службой — обновить позицию для статуса. */
    fun tick() {
        if (Player.state == Player.State.PLAYING || Player.state == Player.State.PAUSED) {
            Player.position = video.currentPosition
        }
    }

    override fun onDestroy() {
        if (Player.activity === this) {
            Player.activity = null
            if (Player.state != Player.State.ERROR) Player.state = Player.State.IDLE
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LOOP = "loop"
        const val EXTRA_START = "start"
    }
}
