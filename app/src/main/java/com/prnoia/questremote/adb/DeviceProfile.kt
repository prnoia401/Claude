package com.prnoia.questremote.adb

/**
 * Что известно о конкретной модели шлема: какие частоты экрана она поддерживает,
 * родное разрешение eye-buffer и какие настройки к ней применимы.
 */
data class DeviceProfile(
    val vendor: Vendor,
    val name: String,
    /** Поддерживаемые частоты обновления, Гц. Пусто — неизвестны. */
    val refreshRates: List<Int>,
    /** Рекомендуемое Meta разрешение eye-buffer (ширина × высота на глаз), если известно. */
    val eyeBuffer: Pair<Int, Int>?,
) {
    enum class Vendor(val title: String) {
        META("Meta Quest"),
        PICO("PICO"),
        OTHER("Android"),
    }

    /** Есть ли отладочные свойства `debug.oculus.*` (только шлемы Meta). */
    val hasOculusProps: Boolean get() = vendor == Vendor.META

    companion object {
        /** Скрипт, выводящий четыре строки для [detect]. */
        const val PROBE_COMMAND =
            "getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.model; getprop ro.product.device"

        fun fromProbe(output: String): DeviceProfile {
            val l = output.lines().map { it.trim() } + List(4) { "" }
            return detect(manufacturer = l[0], brand = l[1], model = l[2], device = l[3])
        }

        fun detect(manufacturer: String, brand: String, model: String, device: String): DeviceProfile {
            val maker = "$manufacturer $brand".lowercase()
            val m = model.lowercase()
            val d = device.lowercase()
            return when {
                "oculus" in maker || "meta" in maker || "quest" in m -> meta(m, d, model)
                "pico" in maker || "pico" in m -> pico(m, model)
                else -> DeviceProfile(Vendor.OTHER, model.ifBlank { "Android" }, emptyList(), null)
            }
        }

        // Кодовые имена: monterey — Quest 1, hollywood — Quest 2, seacliff — Quest Pro,
        // eureka — Quest 3, panther — Quest 3S.
        private fun meta(m: String, d: String, raw: String): DeviceProfile = when {
            d == "panther" || "3s" in m ->
                DeviceProfile(Vendor.META, "Quest 3S", listOf(72, 80, 90, 120), 1832 to 1920)
            d == "eureka" || "quest 3" in m ->
                DeviceProfile(Vendor.META, "Quest 3", listOf(72, 80, 90, 120), 2064 to 2208)
            d == "seacliff" || "pro" in m ->
                DeviceProfile(Vendor.META, "Quest Pro", listOf(72, 90), 1800 to 1920)
            d == "hollywood" || "quest 2" in m ->
                DeviceProfile(Vendor.META, "Quest 2", listOf(60, 72, 80, 90, 120), 1832 to 1920)
            d == "monterey" ->
                DeviceProfile(Vendor.META, "Quest", listOf(60, 72), 1440 to 1600)
            else -> DeviceProfile(Vendor.META, raw.ifBlank { "Quest" }, listOf(72, 90, 120), null)
        }

        private fun pico(m: String, raw: String): DeviceProfile = when {
            "neo3" in m.replace(" ", "") ->
                DeviceProfile(Vendor.PICO, "PICO Neo 3", listOf(72, 90), null)
            "ultra" in m ->
                DeviceProfile(Vendor.PICO, "PICO 4 Ultra", listOf(72, 90), null)
            "4" in m || m.startsWith("a81") ->
                DeviceProfile(Vendor.PICO, "PICO 4", listOf(72, 90), null)
            else -> DeviceProfile(Vendor.PICO, raw.ifBlank { "PICO" }, emptyList(), null)
        }
    }
}
