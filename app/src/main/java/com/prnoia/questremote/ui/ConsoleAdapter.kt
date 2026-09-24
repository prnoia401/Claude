package com.prnoia.questremote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.prnoia.questremote.R

/** Лента консоли: строки logcat, введённые команды и их вывод. */
class ConsoleAdapter : RecyclerView.Adapter<ConsoleAdapter.Holder>() {

    enum class Kind { LOG, COMMAND, OUTPUT, ERROR }

    data class Line(val text: String, val kind: Kind)

    private val all = ArrayList<Line>()
    private val shown = ArrayList<Line>()

    var grep: String = ""
        set(value) {
            field = value
            shown.clear()
            all.filterTo(shown, ::matches)
            notifyDataSetChanged()
        }

    val allLines: List<Line> get() = all

    fun add(lines: List<Line>) {
        if (lines.isEmpty()) return
        all.addAll(lines)
        val start = shown.size
        lines.filterTo(shown, ::matches)
        if (all.size > MAX_LINES) {
            all.subList(0, all.size - MAX_LINES).clear()
            if (shown.size > MAX_LINES) shown.subList(0, shown.size - MAX_LINES).clear()
            notifyDataSetChanged()
        } else {
            notifyItemRangeInserted(start, shown.size - start)
        }
    }

    fun clear() {
        all.clear()
        shown.clear()
        notifyDataSetChanged()
    }

    private fun matches(line: Line) =
        grep.isEmpty() || line.kind != Kind.LOG || line.text.contains(grep, ignoreCase = true)

    override fun getItemCount() = shown.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_console_line, parent, false)
        return Holder(view as TextView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = shown[position]
        val tv = holder.text
        tv.text = line.text
        val colorRes = when (line.kind) {
            Kind.COMMAND -> R.color.log_cmd
            Kind.ERROR -> R.color.log_error
            Kind.LOG -> when (logLevel(line.text)) {
                'E', 'F' -> R.color.log_error
                'W' -> R.color.log_warn
                else -> 0
            }
            Kind.OUTPUT -> 0
        }
        if (colorRes != 0) tv.setTextColor(ContextCompat.getColor(tv.context, colorRes))
        else tv.setTextColor(holder.defaultColor)
    }

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text) {
        val defaultColor = text.textColors
    }

    companion object {
        private const val MAX_LINES = 5000
        private val LEVEL = Regex("""\s([VDIWEF])/""")

        /** Уровень строки формата `logcat -v time`: `09-24 20:45:01.123 E/Tag( 123): …`. */
        fun logLevel(line: String): Char? = LEVEL.find(line.take(40))?.groupValues?.get(1)?.first()
    }
}
