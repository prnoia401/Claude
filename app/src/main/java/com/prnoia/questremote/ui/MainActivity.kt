package com.prnoia.questremote.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prnoia.questremote.BuildConfig
import com.prnoia.questremote.QuestRemoteApp
import com.prnoia.questremote.R
import com.prnoia.questremote.adb.QuestController
import com.prnoia.questremote.adb.QuestController.State
import com.prnoia.questremote.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var console: ConsolePage
        private set
    private lateinit var device: DevicePage
    private lateinit var apps: AppsPage
    private lateinit var files: FilesPage
    private lateinit var remote: RemotePage
    private lateinit var tweaks: TweaksPage

    val usbManager: UsbManager by lazy { getSystemService(UsbManager::class.java) }
    private val prefs by lazy { getSharedPreferences(QuestRemoteApp.PREFS, MODE_PRIVATE) }

    // «Сохранить на телефон»: один лаунчер на всё приложение, задача ждёт выбора файла.
    private var pendingSave: (suspend (OutputStream) -> Unit)? = null
    private var pendingSaveTitle = ""
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val writer = pendingSave
        pendingSave = null
        if (uri != null && writer != null) writeTo(uri, pendingSaveTitle, writer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        console = ConsolePage(this, binding.pageConsole)
        device = DevicePage(this, binding.pageDevice)
        apps = AppsPage(this, binding.pageApps)
        files = FilesPage(this, binding.pageFiles)
        remote = RemotePage(this, binding.pageRemote)
        tweaks = TweaksPage(this, binding.pageTweaks)

        setupMenu()
        applyKeepScreenOn()

        // «Назад» со страницы настроек возвращает на вкладку «Шлем», а не закрывает приложение.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.pageTweaks.root.visibility == View.VISIBLE) {
                    showTab(R.id.tab_device)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QuestController.state.collect(::render)
            }
        }

        handleUsbIntent(intent)
    }

    /**
     * Android 15 рисует приложение под системными панелями. Отступаем сверху
     * под статус-бар, а снизу — под клавиатуру (панель навигации учитывает BottomNavigationView).
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.updatePadding(top = bars.top)
            binding.root.updatePadding(left = bars.left, right = bars.right, bottom = (ime.bottom - bars.bottom).coerceAtLeast(0))
            insets
        }
    }

    private fun setupMenu() {
        binding.toolbar.inflateMenu(R.menu.main)
        val menu = binding.toolbar.menu
        menu.findItem(R.id.menu_auto_reconnect).isChecked = QuestController.autoReconnect
        menu.findItem(R.id.menu_keep_screen).isChecked = prefs.getBoolean(QuestRemoteApp.KEY_KEEP_SCREEN_ON, true)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_auto_reconnect -> {
                    item.isChecked = !item.isChecked
                    QuestController.autoReconnect = item.isChecked
                    prefs.edit().putBoolean(QuestRemoteApp.KEY_AUTO_RECONNECT, item.isChecked).apply()
                }
                R.id.menu_keep_screen -> {
                    item.isChecked = !item.isChecked
                    prefs.edit().putBoolean(QuestRemoteApp.KEY_KEEP_SCREEN_ON, item.isChecked).apply()
                    applyKeepScreenOn()
                }
                R.id.menu_about -> showAbout()
            }
            true
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean(QuestRemoteApp.KEY_KEEP_SCREEN_ON, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Quest Remote ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Управление VR-шлемами по ADB с телефона: USB Type‑C или Wi‑Fi.\n\n" +
                    "Поддерживаются Meta Quest 2 / 3 / 3S / Pro, PICO Neo 3 / 4 / 4 Ultra " +
                    "и любые Android-устройства с включённой отладкой.\n\n" +
                    "Телефон: Android ${android.os.Build.VERSION.RELEASE}, " +
                    "USB-хост: ${if (packageManager.hasSystemFeature("android.hardware.usb.host")) "есть" else "нет"}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** Шлем воткнули в телефон — система открыла приложение с этим устройством. */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val usb = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            ?: return
        val state = QuestController.state.value
        if (state !is State.Connected && state !is State.Connecting) device.connectUsb(usb)
    }

    fun showTab(id: Int) {
        // «Настройки» открываются с вкладки «Шлем», поэтому подсвечиваем её.
        val navId = if (id == R.id.tab_tweaks) R.id.tab_device else id
        binding.bottomNav.menu.findItem(navId)?.isChecked = true
        binding.pageDevice.root.visibility = if (id == R.id.tab_device) View.VISIBLE else View.GONE
        binding.pageRemote.root.visibility = if (id == R.id.tab_remote) View.VISIBLE else View.GONE
        binding.pageApps.root.visibility = if (id == R.id.tab_apps) View.VISIBLE else View.GONE
        binding.pageFiles.root.visibility = if (id == R.id.tab_files) View.VISIBLE else View.GONE
        binding.pageTweaks.root.visibility = if (id == R.id.tab_tweaks) View.VISIBLE else View.GONE
        binding.pageConsole.root.visibility = if (id == R.id.tab_console) View.VISIBLE else View.GONE
        when (id) {
            R.id.tab_apps -> apps.onShown()
            R.id.tab_files -> files.onShown()
            R.id.tab_remote -> remote.onShown()
            R.id.tab_tweaks -> tweaks.onShown()
        }
        if (id != R.id.tab_device) device.stopLiveView()
    }

    private var lastState: State? = null

    private fun render(state: State) {
        val changed = state != lastState
        lastState = state
        // Журналируем смены состояния с временем, чтобы по экспорту консоли было видно,
        // когда и почему оборвалась связь.
        if (changed) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val kind = if (state is State.Failed) ConsoleAdapter.Kind.ERROR else ConsoleAdapter.Kind.COMMAND
            console.log("[$time] ${describe(state)}", kind)
        }
        binding.toolbar.subtitle = describe(state)
        device.render(state)
        if (state is State.Connected) {
            if (changed) {
                device.refreshInfo()
                tweaks.onProfile(state.profile)
                apps.onConnected()
                files.onConnected()
            }
        } else {
            console.stopLogcat()
            device.stopLiveView()
        }
    }

    private fun describe(state: State): String = when (state) {
        State.Disconnected -> "Не подключено"
        is State.Connecting -> if (state.awaitingApproval) "Подтвердите доступ в шлеме…" else "Подключение: ${state.via}…"
        is State.Connected -> "${state.profile.name} · ${state.via}"
        is State.Failed -> "Ошибка: ${state.message}" + if (state.reconnecting) " — переподключаюсь…" else ""
    }

    fun toast(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomNav)
            .show()
    }

    /**
     * Выполнить действие на шлеме: команда и результат пишутся в консоль,
     * ошибка показывается внизу экрана.
     */
    fun runAction(title: String, block: suspend () -> String?) {
        if (!QuestController.isConnected) {
            toast("Сначала подключите шлем")
            return
        }
        console.log("$ $title", ConsoleAdapter.Kind.COMMAND)
        lifecycleScope.launch {
            try {
                val out = block()
                if (!out.isNullOrBlank()) console.log(out)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                console.log(msg, ConsoleAdapter.Kind.ERROR)
                toast("$title: $msg")
            }
        }
    }

    fun runCommand(title: String, command: String) = runAction(title) { QuestController.shell(command) }

    /** Спросить у пользователя, куда сохранить файл, и записать его через [writer]. */
    fun saveToPhone(suggestedName: String, title: String, writer: suspend (OutputStream) -> Unit) {
        pendingSave = writer
        pendingSaveTitle = title
        createDocument.launch(suggestedName)
    }

    private fun writeTo(uri: Uri, title: String, writer: suspend (OutputStream) -> Unit) {
        runAction(title) {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { writer(it) }
                    ?: throw java.io.IOException("Не удалось открыть файл для записи")
            }
            "Сохранено на телефон"
        }
    }
}
