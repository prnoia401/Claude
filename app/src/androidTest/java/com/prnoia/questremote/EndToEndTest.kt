package com.prnoia.questremote

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.containsString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prnoia.questremote.adb.AdbSync
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.RemoteLink
import com.prnoia.questremote.ui.MainActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Сквозная проверка на эмуляторе: телефон и «шлем» — одно устройство, приложение подключается
 * к собственному adbd через ADB-порт эмулятора (CI заранее выдаёт приложению авторизованный ключ).
 * Шаги идут по порядку и используют общее подключение.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio: AudioManager get() = context.getSystemService(AudioManager::class.java)
    private val music: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    private val musicMax: Int get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun waitUntil(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(250)
        }
        throw AssertionError("Не дождались: $what")
    }

    // В эмуляторе adbd не слушает TCP внутри системы: он доступен через ADB-порт эмулятора
    // на стороне хоста, который изнутри виден как 10.0.2.2:5555. CI передаёт адрес аргументами.
    private val adbHost: String
        get() = InstrumentationRegistry.getArguments().getString("adbHost") ?: "10.0.2.2"
    private val adbPort: Int
        get() = InstrumentationRegistry.getArguments().getString("adbPort")?.toIntOrNull() ?: 5555

    private fun connectIfNeeded() = runBlocking {
        if (!QuestController.isConnected) QuestController.connectWifi(adbHost, adbPort)
        assertTrue("ADB не подключился: ${QuestController.state.value}", QuestController.isConnected)
    }

    @Test
    fun t01_connectAdb() {
        connectIfNeeded()
        val st = QuestController.state.value as QuestController.State.Connected
        assertTrue(st.profile.name.isNotBlank())
    }

    @Test
    fun t02_shell() = runBlocking {
        connectIfNeeded()
        assertEquals("hello", QuestController.shell("echo hello"))
        assertEquals("a b", QuestController.shell("echo ${QuestController.quote("a b")}"))
    }

    @Test
    fun t03_volumeIsReallyChanged() = runBlocking {
        connectIfNeeded()
        val v0 = QuestController.mediaVolume(percent = 0)
        waitUntil("громкость 0") { music == 0 }
        assertEquals(0, v0?.current)
        val v1 = QuestController.mediaVolume(delta = +2)
        waitUntil("громкость +2") { music == 2 }
        assertEquals(2, v1?.current)
        QuestController.mediaVolume(delta = -1)
        waitUntil("громкость −1") { music == 1 }
        QuestController.mediaVolume(percent = 100)
        waitUntil("громкость 100%") { music == musicMax }
    }

    @Test
    fun t04_filesPushListPull() = runBlocking {
        connectIfNeeded()
        val data = Random.nextBytes(300_000)
        val remote = "/data/local/tmp/qr_e2e.bin"
        QuestController.push(data.inputStream(), remote)
        val entry = QuestController.listDir("/data/local/tmp").first { it.name == "qr_e2e.bin" }
        assertEquals(data.size.toLong(), entry.size)
        val back = ByteArrayOutputStream()
        QuestController.pull(remote, back)
        assertArrayEquals(data, back.toByteArray())
        QuestController.shell("rm $remote")
        Unit
    }

    @Test
    fun t05_screenshot() = runBlocking {
        connectIfNeeded()
        val png = QuestController.screenshotBytes()
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun t06_logcatStreams() = runBlocking {
        connectIfNeeded()
        val marker = "qr_${Random.nextInt(1_000_000)}"
        val line = withTimeout(20_000) {
            val flow = QuestController.logcat("-s QRTEST")
            coroutineScope {
                val job = launch {
                    repeat(20) {
                        QuestController.shell("log -t QRTEST $marker")
                        delay(500)
                    }
                }
                flow.first { marker in it }.also { job.cancel() }
            }
        }
        assertTrue(line.contains(marker))
    }

    @Test
    fun t07_installClient() = runBlocking {
        connectIfNeeded()
        val apk = File(context.cacheDir, "client_e2e.apk")
        context.assets.open("client.apk").use { i -> apk.outputStream().use { i.copyTo(it) } }
        val result = QuestController.install(apk)
        assertTrue("install: $result", result.startsWith("Success"))
        QuestController.shell(
            "appops set com.prnoia.questclient SYSTEM_ALERT_WINDOW allow; " +
                "appops set com.prnoia.questclient MANAGE_EXTERNAL_STORAGE allow; " +
                "am start-foreground-service -n com.prnoia.questclient/.CommandService"
        )
        Unit
    }

    private fun link(): RemoteLink = runBlocking {
        connectIfNeeded()
        var last: Exception? = null
        repeat(10) {
            try {
                return@runBlocking RemoteLink.viaAdb()
            } catch (e: Exception) {
                last = e
                Thread.sleep(1_000)
            }
        }
        throw AssertionError("Клиент не отвечает: ${last?.message}")
    }

    private fun RemoteLink.state(): String = runBlocking { request("status").optString("state") }

    @Test
    fun t08_clientPlayback() {
        val l = link()
        try {
            runBlocking {
                assertTrue(l.request("ping").optBoolean("pong"))
                val files = l.request("list_media", timeoutMs = 20_000).getJSONArray("files")
                val video = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
                    .firstOrNull { it.endsWith("qr_test.mp4") }
                    ?: throw AssertionError("Тестовое видео не найдено клиентом: $files")

                l.request("play", JSONObject().put("path", video))
            }
            waitUntil("воспроизведение", 30_000) { l.state() == "playing" }
            runBlocking { l.request("pause") }
            waitUntil("пауза") { l.state() == "paused" }
            runBlocking {
                val st = l.request("seek", JSONObject().put("position", 5_000))
                assertTrue("seek: $st", st.optInt("position") in 4_000..6_000)
                l.request("resume")
            }
            waitUntil("снова воспроизведение") { l.state() == "playing" }
            runBlocking { l.request("volume", JSONObject().put("level", 40)) }
            val expected = (40 * musicMax + 50) / 100
            waitUntil("громкость клиента 40%") { music == expected }
            runBlocking { l.request("stop") }
            waitUntil("стоп") { l.state() == "idle" }
        } finally {
            l.close()
        }
    }

    @Test
    fun t09_clientLaunchesApp() {
        val l = link()
        try {
            runBlocking {
                val apps = l.request("list_apps").getJSONArray("apps")
                assertTrue("list_apps пуст", apps.length() > 0)
                l.request("launch", JSONObject().put("package", "com.android.settings"))
            }
            waitUntil("Настройки на экране") {
                runBlocking { QuestController.shell("dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'") }
                    .contains("com.android.settings")
            }
            runBlocking { QuestController.shell("input keyevent KEYCODE_HOME") }
        } finally {
            l.close()
        }
    }

    @Test
    fun t10_uiVolumeButtonWorks() {
        connectIfNeeded()
        runBlocking {
            QuestController.shell("input keyevent KEYCODE_HOME")
            QuestController.mediaVolume(percent = 0)
        }
        waitUntil("громкость 0") { music == 0 }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Громкость 50%")).perform(scrollTo(), click())
            waitUntil("кнопка «Громкость 50%» изменила громкость") { music == (50 * musicMax + 50) / 100 }
            onView(withText("Громкость +")).perform(scrollTo(), click())
            waitUntil("кнопка «Громкость +»") { music == (50 * musicMax + 50) / 100 + 1 }
        }
    }

    /** Найти view с текстом (ждём, пока появится после асинхронной загрузки) и нажать. */
    private fun clickWhenShown(text: String, timeoutMs: Long = 30_000, partial: Boolean = false) {
        val matcher = if (partial) withText(containsString(text)) else withText(text)
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                onView(matcher).perform(scrollTo(), click())
                return
            } catch (e: Throwable) {
                last = e
            }
            // Элементы RecyclerView не поддерживают scrollTo — пробуем просто нажать.
            try {
                onView(matcher).perform(click())
                return
            } catch (e: Throwable) {
                last = e
                Thread.sleep(500)
            }
        }
        throw AssertionError("Не нашли на экране «$text». Видно: ${visibleTexts()} | ${last?.message?.take(150)}")
    }

    /** Тексты всех видимых TextView во всех окнах приложения — для понятной ошибки теста. */
    private fun visibleTexts(): String {
        val out = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            out += "resumed=${activities.map { it.javaClass.simpleName }}"
            fun walk(v: android.view.View) {
                if (v.visibility != android.view.View.VISIBLE) return
                if (v is android.widget.TextView && v.text.isNotBlank()) out += v.text.toString().take(40)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            activities.forEach { walk(it.window.decorView) }
        }
        return out.take(60).joinToString(" · ")
    }

    private fun clickTab(id: Int) = onView(withId(id)).perform(click())

    @Test
    fun t12_uiRemoteTabPlaysAndPauses() {
        connectIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use {
            clickTab(R.id.tab_remote)
            // Вкладка сама ставит/запускает клиент и подключается, затем показывает список видео.
            clickWhenShown("🎬 qr_test.mp4", timeoutMs = 60_000)
            val probe = link()
            try {
                waitUntil("видео играет после нажатия в списке", 30_000) { probe.state() == "playing" }
                // На эмуляторе плеер открылся поверх пульта (это одно устройство) — возвращаем пульт.
                runBlocking { QuestController.shell("am start -n com.prnoia.questremote/.ui.MainActivity") }
                Thread.sleep(1_500)
                clickWhenShown("▶ / ⏸")
                waitUntil("пауза после нажатия ▶/⏸") { probe.state() == "paused" }
                clickWhenShown("+10 с")
                clickWhenShown("⏹ Стоп")
                waitUntil("стоп после нажатия ⏹") { probe.state() == "idle" }
            } finally {
                probe.close()
            }
        }
    }

    @Test
    fun t13_uiFilesAndAppsTabsLoad() {
        connectIfNeeded()
        ActivityScenario.launch(MainActivity::class.java).use {
            clickTab(R.id.tab_files)
            clickWhenShown("📁 Download")
            clickTab(R.id.tab_apps)
            clickWhenShown("com.prnoia.questclient")
            clickWhenShown("Версия и сведения")
        }
    }

    @Test
    fun t11_syncEntryTypes() = runBlocking {
        connectIfNeeded()
        val root = QuestController.listDir("/sdcard")
        assertTrue("в /sdcard нет папок", root.any(AdbSync.Entry::isDirectory))
    }
}
