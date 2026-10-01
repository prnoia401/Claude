package com.prnoia.questclient

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Экран клиента в шлеме: адрес, PIN, разрешения и журнал полученных команд. */
class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var log: TextView
    private lateinit var overlayBtn: Button
    private lateinit var filesBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        info = TextView(this).apply { textSize = 18f }
        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        overlayBtn = Button(this).apply {
            text = "Разрешить открывать плеер поверх других окон"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        filesBtn = Button(this).apply {
            text = "Разрешить доступ к видеофайлам"
            setOnClickListener { requestFiles() }
        }
        val pinBtn = Button(this).apply {
            text = "Сменить PIN"
            setOnClickListener {
                ClientApp.newPin(this@MainActivity)
                render()
            }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(info)
                addView(overlayBtn)
                addView(filesBtn)
                addView(pinBtn)
                addView(log)
            })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        CommandService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ClientLog.listener = { render() }
        render()
    }

    override fun onPause() {
        ClientLog.listener = null
        super.onPause()
    }

    private fun render() {
        val ips = ClientApp.ipAddresses().ifEmpty { listOf("нет сети") }
        info.text = buildString {
            appendLine("Quest Remote Client")
            appendLine()
            appendLine("Сервер: ${if (CommandService.running) "работает" else "запускается…"}, порт ${Protocol.PORT}")
            appendLine("IP: ${ips.joinToString(", ")}")
            appendLine("PIN для Wi‑Fi: ${ClientApp.pin(this@MainActivity)}")
            appendLine()
            append("Это окно можно закрыть — клиент продолжит принимать команды в фоне.")
        }
        overlayBtn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        filesBtn.visibility = if (hasFilesAccess()) View.GONE else View.VISIBLE
        log.text = "Последние команды:\n" + ClientLog.text().ifEmpty { "—" }
    }

    private fun hasFilesAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() -> true
        Build.VERSION.SDK_INT >= 33 ->
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestFiles() {
        when {
            Build.VERSION.SDK_INT >= 30 -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }.onFailure {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 2)
            }
            else -> requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }
}
