package com.prnoia.questremote.ui

import android.view.LayoutInflater
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.databinding.PageTweaksBinding
import kotlinx.coroutines.launch

/** Отладочные параметры Quest (setprop debug.oculus.*) и служебные команды. */
class TweaksPage(private val activity: MainActivity, private val b: PageTweaksBinding) {

    /** Вариант настройки: подпись и shell-команда, которая его включает. */
    private class Option(val label: String, val command: String, val propValue: String? = null)

    /** [prop] — свойство, по которому подсвечивается текущий вариант (если есть). */
    private class Tweak(val title: String, val hint: String, val prop: String?, val options: List<Option>)

    private val groups = mutableListOf<Pair<Tweak, ChipGroup>>()

    private val tweaks = listOf(
        propTweak(
            "Частота обновления, Гц", "Quest 2: 60 / 72 / 80 / 90 / 120",
            "debug.oculus.refreshRate", listOf("60", "72", "80", "90", "120"),
        ),
        propTweak(
            "Уровень CPU", "Выше — быстрее, но горячее и дольше батарея",
            "debug.oculus.cpuLevel", listOf("0", "1", "2", "3", "4"),
        ),
        propTweak(
            "Уровень GPU", "Выше — быстрее, но горячее",
            "debug.oculus.gpuLevel", listOf("0", "1", "2", "3", "4"),
        ),
        propTweak(
            "Фовеация (FFR)", "0 — выкл, 4 — максимум (края кадра размываются сильнее)",
            "debug.oculus.foveation.level", listOf("0", "1", "2", "3", "4"),
        ),
        Tweak(
            "Разрешение eye-buffer", "Ширина × высота текстуры на глаз", "debug.oculus.textureWidth",
            listOf(
                Triple("1440×1584", "1440", "1584"),
                Triple("1832×1920 (родное)", "1832", "1920"),
                Triple("2208×2304", "2208", "2304"),
                Triple("2560×2688", "2560", "2688"),
            ).map { (label, w, h) ->
                Option(label, "setprop debug.oculus.textureWidth $w; setprop debug.oculus.textureHeight $h", w)
            },
        ),
        Tweak(
            "Guardian", "Пауза границы — удобно при отладке за столом", "debug.oculus.guardian_pause",
            listOf(
                Option("Включён", "setprop debug.oculus.guardian_pause 0", "0"),
                Option("Пауза", "setprop debug.oculus.guardian_pause 1", "1"),
            ),
        ),
        Tweak(
            "Датчик присутствия", "«Выключен» — шлем не засыпает, когда его сняли", null,
            listOf(
                Option("Выключен", "am broadcast -a com.oculus.vrpowermanager.prox_close"),
                Option("Как обычно", "am broadcast -a com.oculus.vrpowermanager.automation_disable"),
            ),
        ),
    )

    init {
        tweaks.forEach { addSection(it) }
        b.btnReset.setOnClickListener {
            val props = listOf(
                "refreshRate", "cpuLevel", "gpuLevel", "foveation.level",
                "textureWidth", "textureHeight", "guardian_pause",
            )
            activity.runAction("Сброс параметров") {
                QuestController.shell(props.joinToString("; ") { "setprop debug.oculus.$it ''" })
                refreshSelection()
                "Параметры сброшены"
            }
        }
    }

    fun onShown() {
        activity.lifecycleScope.launch { refreshSelection() }
    }

    private fun addSection(tweak: Tweak) {
        val card = LayoutInflater.from(activity).inflate(R.layout.item_tweak, b.sections, false) as MaterialCardView
        card.findViewById<TextView>(R.id.title).text = tweak.title
        card.findViewById<TextView>(R.id.hint).text = tweak.hint
        val group = card.findViewById<ChipGroup>(R.id.options)
        tweak.options.forEach { option ->
            group.addView(Chip(activity).apply {
                text = option.label
                isCheckable = tweak.prop != null
                setOnClickListener { apply(tweak, option) }
            })
        }
        groups += tweak to group
        b.sections.addView(card)
    }

    private fun apply(tweak: Tweak, option: Option) {
        activity.runAction("${tweak.title}: ${option.label}") {
            QuestController.shell(option.command)
            refreshSelection()
            "${option.command}\nПерезапустите VR-приложение, чтобы параметр применился."
        }
    }

    /** Подсветить варианты, совпадающие с текущими значениями свойств на шлеме. */
    private suspend fun refreshSelection() {
        if (!QuestController.isConnected) return
        val props = groups.mapNotNull { it.first.prop }
        val values = runCatching {
            QuestController.shell(props.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" })
        }.getOrNull() ?: return
        val current = values.lines().associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        groups.forEach { (tweak, group) ->
            val prop = tweak.prop ?: return@forEach
            tweak.options.forEachIndexed { i, option ->
                (group.getChildAt(i) as Chip).isChecked = option.propValue == current[prop]
            }
        }
    }

    private companion object {
        fun propTweak(title: String, hint: String, prop: String, values: List<String>) = Tweak(
            title, hint, prop, values.map { Option(it, "setprop $prop $it", it) },
        )
    }
}
