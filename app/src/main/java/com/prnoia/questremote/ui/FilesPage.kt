package com.prnoia.questremote.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.AdbSync
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.databinding.PageFilesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Файловый менеджер шлема поверх протокола `sync:` (как `adb push/pull`). */
class FilesPage(private val activity: MainActivity, private val b: PageFilesBinding) {

    private var path = HOME
    private var loadedOnce = false
    private val adapter = FilesAdapter(::open, ::showActions)

    private val pickUpload = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri)
    }

    init {
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = adapter
        b.btnUp.setOnClickListener {
            if (path != "/") navigate(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
        }
        b.btnUpload.setOnClickListener {
            if (QuestController.isConnected) pickUpload.launch(arrayOf("*/*"))
            else activity.toast("Сначала подключите шлем")
        }
        b.btnMkdir.setOnClickListener { mkdir() }
        BOOKMARKS.forEach { (label, dir) ->
            b.bookmarks.addView(Chip(activity).apply {
                text = label
                setOnClickListener { navigate(dir) }
            })
        }
    }

    fun onShown() {
        if (!loadedOnce) navigate(path)
    }

    fun onConnected() {
        loadedOnce = false
        path = HOME
        adapter.submit(emptyList())
        if (b.root.isShown) navigate(path)
    }

    private fun navigate(dir: String) {
        if (!QuestController.isConnected) {
            b.filesStatus.text = "Шлем не подключён"
            return
        }
        activity.lifecycleScope.launch {
            b.filesStatus.text = "Загрузка…"
            try {
                val entries = QuestController.listDir(dir)
                    .sortedWith(compareBy<AdbSync.Entry>({ !it.isDirectory && !it.isLink }, { it.name.lowercase() }))
                path = dir
                loadedOnce = true
                b.path.text = dir
                adapter.submit(entries)
                b.filesStatus.text = if (entries.isEmpty()) "Папка пуста (или нет доступа)" else "Объектов: ${entries.size}"
            } catch (e: Exception) {
                b.filesStatus.text = "Ошибка: ${e.message}"
            }
        }
    }

    private fun child(name: String) = if (path.endsWith("/")) "$path$name" else "$path/$name"

    private fun open(entry: AdbSync.Entry) {
        if (entry.isDirectory || entry.isLink) navigate(child(entry.name)) else download(entry)
    }

    private fun showActions(entry: AdbSync.Entry) {
        val full = child(entry.name)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (entry.isDirectory || entry.isLink) items += "Открыть" to { navigate(full) }
        if (!entry.isDirectory) items += "Скачать на телефон" to { download(entry) }
        if (entry.name.endsWith(".apk", ignoreCase = true)) items += "Установить на шлем" to { installRemote(full) }
        items += "Удалить" to { delete(full) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(entry.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun download(entry: AdbSync.Entry) {
        val full = child(entry.name)
        activity.saveToPhone(entry.name, "Скачивание ${entry.name}") { out ->
            QuestController.pull(full, out) { done -> progress(done, entry.size) }
        }
    }

    private fun upload(uri: Uri) {
        val name = displayName(uri) ?: "file_${System.currentTimeMillis()}"
        val size = runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        val target = child(name)
        activity.runAction("Загрузка $name → $target") {
            b.progress.isVisible = true
            try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        QuestController.push(input, target) { done -> progress(done, size) }
                    } ?: throw java.io.IOException("Не удалось открыть файл")
                }
            } finally {
                b.progress.isVisible = false
            }
            navigate(path)
            "Загружено: $target"
        }
    }

    private fun installRemote(remote: String) {
        activity.runCommand("pm install $remote", "pm install -r -g ${QuestController.quote(remote)}")
    }

    private fun delete(full: String) {
        MaterialAlertDialogBuilder(activity)
            .setMessage("Удалить $full со шлема?")
            .setPositiveButton("Удалить") { _, _ ->
                activity.runAction("Удалить $full") {
                    QuestController.shell("rm -rf ${QuestController.quote(full)}").also { navigate(path) }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun mkdir() {
        val input = EditText(activity).apply {
            hint = "Имя папки"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Новая папка в $path")
            .setView(box)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim().replace("/", "_")
                if (name.isNotEmpty()) {
                    activity.runAction("mkdir $name") {
                        QuestController.shell("mkdir -p ${QuestController.quote(child(name))}").also { navigate(path) }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Вызывается из фонового потока передачи — переключаемся на UI. */
    private fun progress(done: Long, total: Long) {
        activity.runOnUiThread {
            b.progress.isVisible = true
            if (total > 0) {
                b.progress.isIndeterminate = false
                b.progress.setProgressCompat((done * 100 / total).toInt().coerceIn(0, 100), false)
                if (done >= total) b.progress.isVisible = false
            } else {
                b.progress.isIndeterminate = true
            }
            b.filesStatus.text = "Передано ${Formatter.formatShortFileSize(activity, done)}"
        }
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private class FilesAdapter(
        private val onClick: (AdbSync.Entry) -> Unit,
        private val onLongClick: (AdbSync.Entry) -> Unit,
    ) : RecyclerView.Adapter<FilesAdapter.Holder>() {

        private var items: List<AdbSync.Entry> = emptyList()
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        fun submit(list: List<AdbSync.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            val ctx = holder.itemView.context
            holder.name.text = when {
                e.isDirectory -> "📁 ${e.name}"
                e.isLink -> "🔗 ${e.name}"
                else -> "📄 ${e.name}"
            }
            val date = dateFormat.format(Date(e.mtimeSec * 1000))
            holder.meta.text = if (e.isDirectory || e.isLink) date
            else "${Formatter.formatShortFileSize(ctx, e.size)} · $date"
            holder.itemView.setOnClickListener { onClick(e) }
            holder.itemView.setOnLongClickListener { onLongClick(e); true }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val meta: TextView = view.findViewById(R.id.meta)
        }
    }

    private companion object {
        const val HOME = "/sdcard"
        val BOOKMARKS = listOf(
            "Память" to "/sdcard",
            "Download" to "/sdcard/Download",
            "Видео" to "/sdcard/Movies",
            "Скриншоты" to "/sdcard/Oculus/Screenshots",
            "OBB" to "/sdcard/Android/obb",
            "Данные приложений" to "/sdcard/Android/data",
        )
    }
}
