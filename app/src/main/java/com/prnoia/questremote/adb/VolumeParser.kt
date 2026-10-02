package com.prnoia.questremote.adb

/** Громкость медиа на шлеме: текущее значение и максимум. */
data class Volume(val current: Int, val max: Int) {
    val percent: Int get() = if (max > 0) current * 100 / max else 0
    override fun toString() = "Громкость $current из $max ($percent%)"
}

/** Разбор вывода системных команд громкости (форматы отличаются между версиями Android). */
object VolumeParser {

    private val commandRegex = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")

    /** Вывод `cmd media_session volume --get` / `media volume --get`. */
    fun fromVolumeCommand(out: String): Volume? =
        commandRegex.findAll(out).lastOrNull()?.let { Volume(it.groupValues[1].toInt(), it.groupValues[3].toInt()) }

    /** Блок «- STREAM_MUSIC:» из `dumpsys audio`: «Max: 15» и «streamVolume:7» или «Current: 2 (speaker): 7». */
    fun fromDumpsysAudio(dump: String): Volume? {
        val start = dump.indexOf("- STREAM_MUSIC:")
        if (start < 0) return null
        val next = dump.indexOf("- STREAM_", start + 15).let { if (it < 0) dump.length else it }
        val block = dump.substring(start, next)
        val max = Regex("""Max:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val cur = Regex("""streamVolume:\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Current:\s*\S+\s*\([^)]*\):\s*(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return Volume(cur, max)
    }
}
