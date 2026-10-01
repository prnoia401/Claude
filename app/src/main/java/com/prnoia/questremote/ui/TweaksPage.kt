package com.prnoia.questremote.ui

import android.view.LayoutInflater
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.DeviceProfile
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.databinding.PageTweaksBinding
import kotlinx.coroutines.launch

/**
 * Настройки шлема. Набор зависит от модели: у Meta Quest есть отладочные свойства
 * `debug.oculus.*`, у PICO и прочих — только общие для Android механизмы.
 */
class TweaksPage(private val activity: MainActivity, private val b: PageTweaksBinding) {

    /** Вариант настройки: подпись и shell-команда, которая его включает. */
    private class Option(val label: String, val command: String, val propValue: String? = null)

    /** [prop] — свойство, по которому подсвечивается текущий вариант (если есть). */
    private class Tweak(val title: String, val hint: String, val prop: String?, val options: List<Option>)

    private val groups = mutableListOf<Pair<Tweak, ChipGroup>>()
    private var resetProps: List<String> = emptyList()
    private var profile: DeviceProfile? = null

    init {
        showPlaceholder("Подключите шлем — здесь появятся настройки для вашей модели.")
        b.btnReset.setOnClickListener {
            if (resetProps.isEmpty()) return@setOnClickListener
            activity.runAction("Сброс параметров") {
                QuestController.shell(resetProps.joinToString("; ") { "setprop $it ''" })
                refreshSelection()
                "Параметры сброшены"
            }
        }
    }

    fun onProfile(p: DeviceProfile) {
        if (p == profile) return
        profile = p
        groups.clear()
        b.sections.removeAllViews()
        val tweaks = buildTweaks(p)
        resetProps = tweaks.mapNotNull { it.prop }.flatMap {
            if (it == "debug.oculus.textureWidth") listOf(it, "debug.oculus.textureHeight") else listOf(it)
        }
        b.btnReset.isEnabled = resetProps.isNotEmpty()
        if (p.vendor != DeviceProfile.Vendor.META) {
            showPlaceholder(
                "${p.name}: отладочные свойства, как у Meta Quest, у этой платформы не документированы. " +
                    "Доступны общие настройки Android. Частоту и разрешение для своих приложений задавайте через SDK."
            )
        }
        tweaks.forEach(::addSection)
        onShown()
    }

    fun onShown() {
        activity.lifecycleScope.launch { refreshSelection() }
    }

    private fun buildTweaks(p: DeviceProfile): List<Tweak> {
        val common = listOf(
            Tweak(
                "Не засыпать на зарядке", "svc power stayon — экран не гаснет, пока устройство питается по USB/зарядке",
                null,
                listOf(
                    Option("При питании USB", "svc power stayon usb"),
                    Option("Всегда", "svc power stayon true"),
                    Option("Как обычно", "svc power stayon false"),
                ),
            ),
            Tweak(
                "Анимации системы", "Отключение ускоряет 2D-интерфейс и автотесты",
                null,
                listOf(
                    Option("Выкл", ANIMATIONS.joinToString("; ") { "settings put global $it 0" }),
                    Option("Вкл", ANIMATIONS.joinToString("; ") { "settings put global $it 1" }),
                ),
            ),
        )
        if (!p.hasOculusProps) return common

        val rates = p.refreshRates.ifEmpty { listOf(72, 90, 120) }
        val meta = mutableListOf(
            propTweak(
                "Частота обновления, Гц", "${p.name}: ${rates.joinToString(" / ")}",
                "debug.oculus.refreshRate", rates.map { it.toString() },
            ),
            propTweak(
                "Уровень CPU", "Выше — быстрее, но горячее и быстрее садится батарея",
                "debug.oculus.cpuLevel", listOf("0", "1", "2", "3", "4", "5"),
            ),
            propTweak(
                "Уровень GPU", "Выше — быстрее, но горячее",
                "debug.oculus.gpuLevel", listOf("0", "1", "2", "3", "4", "5"),
            ),
            propTweak(
                "Фовеация (FFR)", "0 — выкл, 4 — максимум (края кадра размываются сильнее)",
                "debug.oculus.foveation.level", listOf("0", "1", "2", "3", "4"),
            ),
        )
        p.eyeBuffer?.let { (w, h) ->
            meta += Tweak(
                "Разрешение eye-buffer", "Ширина × высота на глаз. 100% — рекомендованное Meta для ${p.name}",
                "debug.oculus.textureWidth",
                listOf(0.8, 1.0, 1.2, 1.4).map { k ->
                    val tw = round32(w * k)
                    val th = round32(h * k)
                    Option(
                        "${(k * 100).toInt()}% · ${tw}×$th",
                        "setprop debug.oculus.textureWidth $tw; setprop debug.oculus.textureHeight $th",
                        tw.toString(),
                    )
                },
            )
        }
        meta += Tweak(
            "Guardian", "Пауза границы — удобно при отладке за столом", "debug.oculus.guardian_pause",
            listOf(
                Option("Включён", "setprop debug.oculus.guardian_pause 0", "0"),
                Option("Пауза", "setprop debug.oculus.guardian_pause 1", "1"),
            ),
        )
        meta += Tweak(
            "Датчик присутствия", "«Выключен» — шлем не засыпает, когда его сняли", null,
            listOf(
                Option("Выключен", "am broadcast -a com.oculus.vrpowermanager.prox_close"),
                Option("Как обычно", "am broadcast -a com.oculus.vrpowermanager.automation_disable"),
            ),
        )
        return meta + common
    }

    private fun showPlaceholder(text: String) {
        b.sections.addView(TextView(activity).apply {
            this.text = text
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        })
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
            option.command + if (tweak.prop?.startsWith("debug.oculus") == true) {
                "\nПерезапустите VR-приложение, чтобы параметр применился."
            } else ""
        }
    }

    /** Подсветить варианты, совпадающие с текущими значениями свойств на шлеме. */
    private suspend fun refreshSelection() {
        if (!QuestController.isConnected) return
        val props = groups.mapNotNull { it.first.prop }
        if (props.isEmpty()) return
        val values = runCatching {
            QuestController.shell(props.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" })
        }.getOrNull() ?: return
        val current = values.lines().associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        groups.forEach { (tweak, group) ->
            val prop = tweak.prop ?: return@forEach
            tweak.options.forEachIndexed { i, option ->
                (group.getChildAt(i) as? Chip)?.isChecked = option.propValue == current[prop]
            }
        }
    }

    private companion object {
        val ANIMATIONS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

        fun round32(v: Double) = ((v / 32).toInt() * 32)

        fun propTweak(title: String, hint: String, prop: String, values: List<String>) = Tweak(
            title, hint, prop, values.map { Option(it, "setprop $prop $it", it) },
        )
    }
}
