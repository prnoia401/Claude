package com.prnoia.questclient

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Выполняет команды пульта. Вызывается из потоков сервера и из [CommandReceiver]. */
class CommandHandler(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    fun handle(req: JSONObject): JSONObject {
        val res = try {
            execute(req.optString("cmd"), req).put("ok", true)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        if (req.has("id")) res.put("id", req.get("id"))
        ClientLog.add("${req.optString("cmd")} → ${if (res.optBoolean("ok")) "ok" else res.optString("error")}")
        return res
    }

    private fun execute(cmd: String, r: JSONObject): JSONObject = when (cmd) {
        "ping" -> JSONObject()
            .put("pong", true)
            .put("version", Protocol.VERSION)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)

        "status" -> onMain { Player.status(context) }

        "play" -> {
            val src = r.optString("path").ifEmpty { r.optString("url") }
            require(src.isNotEmpty()) { "Нужен path или url" }
            if (!src.contains("://")) require(File(src).exists()) { "Файл не найден: $src" }
            onMain { Player.play(context, src, r.optBoolean("loop"), r.optInt("position")) }
            JSONObject().put("source", src)
        }
        "pause" -> onMain { Player.requireActivity().pause(); Player.status(context) }
        "resume" -> onMain { Player.requireActivity().resume(); Player.status(context) }
        "toggle" -> onMain {
            val a = Player.requireActivity()
            if (Player.state == Player.State.PLAYING) a.pause() else a.resume()
            Player.status(context)
        }
        "stop" -> onMain { Player.activity?.finish(); Player.state = Player.State.IDLE; JSONObject() }
        "seek" -> onMain { Player.requireActivity().seekTo(r.getInt("position")); Player.status(context) }
        "seek_by" -> onMain {
            Player.requireActivity().seekTo(Player.position + r.getInt("delta"))
            Player.status(context)
        }
        "volume" -> onMain { Player.setVolumePercent(context, r.getInt("level")); Player.status(context) }
        "loop" -> onMain {
            Player.loop = r.getBoolean("value")
            Player.activity?.applyLoop()
            Player.status(context)
        }

        "list_media" -> JSONObject().put("files", listMedia(r.optString("dir")))
        "list_apps" -> JSONObject().put("apps", listApps())
        "launch" -> {
            val pkg = r.getString("package")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw IllegalArgumentException("Нет запускаемой activity у $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(intent, r.optJSONObject("extras"))
            context.startActivity(intent)
            JSONObject().put("package", pkg)
        }
        "broadcast" -> {
            // Мост к вашему VR-приложению: оно слушает свой action и получает extras.
            val intent = Intent(r.getString("action"))
            r.optString("package").takeIf { it.isNotEmpty() }?.let(intent::setPackage)
            putExtras(intent, r.optJSONObject("extras"))
            context.sendBroadcast(intent)
            JSONObject()
        }
        "message" -> {
            val text = r.getString("text")
            main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
            JSONObject()
        }
        else -> throw IllegalArgumentException("Неизвестная команда: $cmd")
    }

    private fun putExtras(intent: Intent, extras: JSONObject?) {
        extras ?: return
        for (key in extras.keys()) {
            when (val v = extras.get(key)) {
                is Boolean -> intent.putExtra(key, v)
                is Int -> intent.putExtra(key, v)
                is Long -> intent.putExtra(key, v)
                is Double -> intent.putExtra(key, v)
                else -> intent.putExtra(key, v.toString())
            }
        }
    }

    private fun listMedia(dir: String): JSONArray {
        val root = Environment.getExternalStorageDirectory()
        val dirs = if (dir.isNotEmpty()) listOf(File(dir))
        else listOf("Movies", "Download", "DCIM", "Oculus/VideoShots", "Pictures").map { File(root, it) }
        val out = JSONArray()
        dirs.forEach { d ->
            d.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }.take(500)
                .forEach {
                    out.put(JSONObject().put("name", it.name).put("path", it.absolutePath).put("size", it.length()))
                }
        }
        return out
    }

    private fun listApps(): JSONArray {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val vr = Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
        val infos = (pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL) +
            pm.queryIntentActivities(vr, PackageManager.MATCH_ALL))
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
        val out = JSONArray()
        infos.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
            .forEach { (label, pkg) -> out.put(JSONObject().put("label", label).put("package", pkg)) }
        return out
    }

    /** Выполнить в главном потоке и дождаться результата (UI и MediaPlayer живут там). */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(5, TimeUnit.SECONDS)) throw IllegalStateException("Главный поток не ответил")
        return result!!.getOrThrow()
    }

    private companion object {
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts")
    }
}

/** Последние команды — показываются на экране клиента. */
object ClientLog {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$time  $line")
        while (lines.size > 30) lines.removeLast()
        listener?.let { l -> android.os.Handler(Looper.getMainLooper()).post { l() } }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
