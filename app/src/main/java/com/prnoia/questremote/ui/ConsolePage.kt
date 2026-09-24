package com.prnoia.questremote.ui

import android.content.Intent
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.databinding.PageConsoleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class ConsolePage(private val activity: MainActivity, private val b: PageConsoleBinding) {

    private val adapter = ConsoleAdapter()
    private val layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }

    // logcat может выдавать тысячи строк в секунду: копим их и сбрасываем в список пачками.
    private val pending = ConcurrentLinkedQueue<ConsoleAdapter.Line>()
    private var logcatJob: Job? = null

    init {
        b.lines.layoutManager = layoutManager
        b.lines.adapter = adapter
        b.btnLogcat.setOnClickListener {
            if (logcatJob?.isActive == true) stopLogcat() else startLogcat(b.logcatArgs.text.toString())
        }
        b.btnClear.setOnClickListener { adapter.clear() }
        b.btnShare.setOnClickListener { export() }
        b.grep.doAfterTextChanged { adapter.grep = it?.toString().orEmpty() }
        b.btnRun.setOnClickListener { runInput() }
        b.shellInput.setOnEditorActionListener { _, id, _ ->
            (id == EditorInfo.IME_ACTION_SEND).also { if (it) runInput() }
        }

        activity.lifecycleScope.launch {
            while (true) {
                flush()
                delay(150)
            }
        }
    }

    fun log(text: String, kind: ConsoleAdapter.Kind = ConsoleAdapter.Kind.OUTPUT) {
        text.lineSequence().forEach { pending.add(ConsoleAdapter.Line(it, kind)) }
    }

    fun startLogcat(args: String) {
        stopLogcat()
        b.logcatArgs.setText(args)
        log("$ logcat -v time $args", ConsoleAdapter.Kind.COMMAND)
        b.btnLogcat.text = "Стоп"
        logcatJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            QuestController.logcat(args)
                .catch { log("Ошибка: ${it.message}", ConsoleAdapter.Kind.ERROR) }
                .onCompletion { withContext(Dispatchers.Main) { b.btnLogcat.text = "Logcat" } }
                .collect { pending.add(ConsoleAdapter.Line(it, ConsoleAdapter.Kind.LOG)) }
        }
    }

    fun stopLogcat() {
        logcatJob?.cancel()
        logcatJob = null
        b.btnLogcat.text = "Logcat"
    }

    private fun runInput() {
        val cmd = b.shellInput.text.toString().trim()
        if (cmd.isEmpty()) return
        b.shellInput.setText("")
        activity.runCommand(cmd, cmd)
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val batch = ArrayList<ConsoleAdapter.Line>()
        while (true) batch.add(pending.poll() ?: break)
        val atBottom = !b.lines.canScrollVertically(1)
        adapter.add(batch)
        if (atBottom && adapter.itemCount > 0) b.lines.scrollToPosition(adapter.itemCount - 1)
    }

    private fun export() {
        val dir = File(activity.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "quest_log_${System.currentTimeMillis()}.txt")
        file.writeText(adapter.allLines.joinToString("\n") { it.text })
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, "Экспорт лога"))
    }
}
