package com.prnoia.questremote.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.databinding.PageAppsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppsPage(private val activity: MainActivity, private val b: PageAppsBinding) {

    private var packages: List<String> = emptyList()
    private val adapter = PackagesAdapter { showActions(it) }
    private var loaded = false

    private val pickApk = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install(uri)
    }

    init {
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = adapter
        b.btnRefresh.setOnClickListener { reload() }
        b.btnInstall.setOnClickListener {
            if (QuestController.isConnected) {
                pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            } else {
                activity.toast("Сначала подключите шлем")
            }
        }
        b.filter.doAfterTextChanged { applyFilter() }
        b.showSystem.setOnCheckedChangeListener { _, _ -> reload() }
    }

    fun onShown() {
        if (!loaded) reload()
    }

    private fun reload() {
        if (!QuestController.isConnected) {
            b.appsStatus.text = "Шлем не подключён"
            return
        }
        activity.lifecycleScope.launch {
            val flag = if (b.showSystem.isChecked) "" else "-3"
            runCatching { QuestController.shell("pm list packages $flag") }
                .onSuccess { out ->
                    packages = out.lineSequence()
                        .map { it.removePrefix("package:").trim() }
                        .filter { it.isNotEmpty() }
                        .sorted()
                        .toList()
                    loaded = true
                    applyFilter()
                }
                .onFailure { b.appsStatus.text = "Ошибка: ${it.message}" }
        }
    }

    private fun applyFilter() {
        val q = b.filter.text.toString().trim()
        val list = if (q.isEmpty()) packages else packages.filter { it.contains(q, ignoreCase = true) }
        adapter.submit(list)
        b.appsStatus.text = "Пакетов: ${list.size}. Нажмите на пакет для действий."
    }

    private fun showActions(pkg: String) {
        val actions = listOf(
            Item("Запустить") { launchApp(pkg) },
            Item("Остановить") { activity.runCommand("Остановить $pkg", "am force-stop $pkg") },
            Item("Логи этого приложения") {
                activity.lifecycleScope.launch {
                    val pid = runCatching { QuestController.shell("pidof -s $pkg").trim() }.getOrDefault("")
                    if (pid.isEmpty()) {
                        activity.toast("$pkg не запущено — сначала запустите")
                    } else {
                        activity.showTab(R.id.tab_console)
                        activity.console.startLogcat("--pid=$pid")
                    }
                }
            },
            Item("Версия и сведения") {
                activity.showTab(R.id.tab_console)
                activity.runCommand(
                    "Сведения $pkg",
                    "dumpsys package $pkg | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|targetSdk' | head -n 6"
                )
            },
            Item("Очистить данные") { confirm("Стереть данные $pkg?") { activity.runCommand("Очистить $pkg", "pm clear $pkg") } },
            Item("Удалить") {
                confirm("Удалить $pkg со шлема?") {
                    activity.runAction("Удалить $pkg") {
                        QuestController.shell("pm uninstall $pkg").also { reload() }
                    }
                }
            },
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(pkg)
            .setItems(actions.map { it.label }.toTypedArray()) { _, i -> actions[i].run() }
            .show()
    }

    private fun launchApp(pkg: String) {
        // VR-приложения Quest регистрируют категорию com.oculus.intent.category.VR,
        // 2D-приложения — обычный LAUNCHER. Пробуем обе.
        activity.runCommand(
            "Запуск $pkg",
            "monkey -p $pkg -c com.oculus.intent.category.VR 1 >/dev/null 2>&1 && echo 'Запущено (VR)' || " +
                "(monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && echo 'Запущено' || " +
                "echo 'Не найдена стартовая activity')"
        )
    }

    private fun install(uri: Uri) {
        activity.lifecycleScope.launch {
            b.progress.isVisible = true
            b.progress.isIndeterminate = true
            b.btnInstall.isEnabled = false
            try {
                val name = displayName(uri) ?: "app.apk"
                b.appsStatus.text = "Копирование $name…"
                val apk = withContext(Dispatchers.IO) {
                    val dst = File(activity.cacheDir, "install.apk")
                    activity.contentResolver.openInputStream(uri)!!.use { input ->
                        dst.outputStream().use { input.copyTo(it) }
                    }
                    dst
                }
                b.appsStatus.text = "Установка $name на шлем…"
                b.progress.isIndeterminate = false
                activity.console.log("$ install $name", ConsoleAdapter.Kind.COMMAND)
                val result = QuestController.install(apk) { percent ->
                    activity.runOnUiThread { b.progress.setProgressCompat(percent, true) }
                }
                activity.console.log(result)
                activity.toast(if (result.startsWith("Success")) "$name установлено" else result)
                apk.delete()
                reload()
            } catch (e: Exception) {
                activity.toast("Ошибка установки: ${e.message}")
                activity.console.log("Ошибка установки: ${e.message}", ConsoleAdapter.Kind.ERROR)
            } finally {
                b.progress.isVisible = false
                b.btnInstall.isEnabled = true
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun confirm(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton("Да") { _, _ -> action() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private class Item(val label: String, val run: () -> Unit)

    private class PackagesAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<PackagesAdapter.Holder>() {

        private var items: List<String> = emptyList()

        fun submit(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_package, parent, false) as TextView
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val pkg = items[position]
            holder.text.text = pkg
            holder.text.setOnClickListener { onClick(pkg) }
        }

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
