package com.zapret.byedpi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.EngineConfig
import com.zapret.byedpi.core.LogEntry
import com.zapret.byedpi.core.UpdateChecker
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.core.VpnState
import com.zapret.byedpi.databinding.ActivityMainBinding
import com.zapret.byedpi.nativebridge.HevBridge
import com.zapret.byedpi.service.VpnController
import com.zapret.byedpi.ui.BottomNavNavigator
import com.zapret.byedpi.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Главный экран этапа 1.
 *
 * Задачи экрана:
 *  - большая кнопка ВКЛ/ВЫКЛ;
 *  - запрос системного разрешения VpnService;
 *  - запрос разрешения на уведомления (Android 13+);
 *  - показ состояния, TUN, пресета, счётчика ротаций и времени работы;
 *  - живой журнал последних событий.
 *
 * Логика запуска туннеля лежит в ZapretVpnService; Activity только
 * показывает состояние и отправляет команды через VpnController.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LOG_PREVIEW_LINES = 6
    }

    private lateinit var binding: ActivityMainBinding

    /** Последнее известное состояние — нужно для обработки нажатия кнопки. */
    private var currentState = VpnState()

    /** Ждём ли результат запроса разрешения на уведомления, чтобы стартовать VPN. */
    private var pendingStartAfterNotificationPermission = false

    // -----------------------------------------------------------------------
    // Activity Result API: системный диалог VPN.
    // -----------------------------------------------------------------------
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLog.i(TAG, "Пользователь разрешил VPN")
            startVpnService()
        } else {
            AppLog.w(TAG, "Пользователь отклонил разрешение VPN")
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    // -----------------------------------------------------------------------
    // Activity Result API: разрешение POST_NOTIFICATIONS (Android 13+).
    // Без него foreground-уведомление может быть не видно, но VPN работает.
    // -----------------------------------------------------------------------
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            AppLog.w(TAG, "Уведомления запрещены — статус не будет виден в шторке")
        }
        if (pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false
            requestVpnConsentAndStart()
        }
    }

    // -----------------------------------------------------------------------
    // Activity Result API: сохранение диагностического отчёта.
    // -----------------------------------------------------------------------
    private val createLogFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                LogExporter.write(this@MainActivity, uri)
            }
            Toast.makeText(
                this@MainActivity,
                if (saved) R.string.log_export_success else R.string.log_export_error,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.bottomNav.selectedItemId = R.id.nav_home
        setContentView(binding.root)

        binding.powerContainer.setOnClickListener { onPowerClicked() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNavNavigator.navigate(this, R.id.nav_home, item.itemId)
            true
        }
        binding.btnExportLog.setOnClickListener {
            try {
                createLogFileLauncher.launch(LogExporter.suggestFileName())
            } catch (e: Exception) {
                AppLog.e(TAG, "Не удалось открыть диалог сохранения логов: ${e.message}", e)
                Toast.makeText(this, R.string.log_export_error, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnClearLog.setOnClickListener {
            AppLog.clear()
            AppLog.i(TAG, "Журнал очищен")
        }

        // Первичная отрисовка до подписки на Flow — чтобы не было мигания.
        currentState = VpnRuntime.state.value
        render(currentState)
        renderLogs(AppLog.entries.value)

        binding.tvVersion.text = getString(R.string.version_label, UpdateChecker.currentVersion)
        binding.tvUpdate.setOnClickListener {
            openReleases()
        }
        observeRuntime()
        observeUpdateChecker()
        UpdateChecker.refresh(this)
    }

    /**
     * Показывает плашку «доступна новая версия», если на GitHub тег новее
     * текущего. Проверка кэшируется на 12 часов, поэтому вызов безопасен
     * при каждом открытии экрана.
     */
    private fun observeUpdateChecker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateChecker.info.collect { info ->
                    val latest = info.latest
                    if (info.updateAvailable && latest != null) {
                        binding.tvUpdate.text = getString(R.string.update_available, latest)
                        binding.tvUpdate.visibility = View.VISIBLE
                    } else {
                        binding.tvUpdate.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL)))
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось открыть страницу релизов: ${e.message}")
        }
    }

    /**
     * Подписка на состояние службы и журнал.
     *
     * repeatOnLifecycle(STARTED) автоматически отписывается, когда экран
     * уходит в фон, и снова подписывается при возврате. Это защищает от
     * утечек и лишней работы.
     */
    override fun onResume() {
        super.onResume()
        binding.bottomNav.selectedItemId = R.id.nav_home
        // Вернулись в приложение — заодно проверим, не вышло ли обновление.
        UpdateChecker.refresh(this)
    }

    private fun observeRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    VpnRuntime.state.collect { state ->
                        render(state)
                    }
                }
                launch {
                    AppLog.entries.collect { entries ->
                        renderLogs(entries)
                    }
                }
                launch {
                    // Раз в секунду обновляем «В работе» и «Трафик».
                    while (true) {
                        updateUptime()
                        updateTraffic()
                        delay(1_000L)
                    }
                }
            }
        }
    }

    /**
     * Обработка большой кнопки.
     *
     * STOPPED/ERROR -> запрашиваем разрешения и запускаем.
     * STARTING/RUNNING -> просим службу остановиться.
     * STOPPING -> игнорируем повторные нажатия.
     */
    private fun onPowerClicked() {
        when (currentState.phase) {
            VpnPhase.RUNNING, VpnPhase.STARTING -> {
                AppLog.i(TAG, "Пользователь нажал «Выключить»")
                VpnController.stop(this)
            }

            VpnPhase.STOPPING -> Unit

            VpnPhase.STOPPED, VpnPhase.ERROR -> {
                beginStartFlow()
            }
        }
    }

    /**
     * Перед запуском VPN просим разрешение на уведомления (если нужно),
     * а затем — системное разрешение VpnService.
     */
    private fun beginStartFlow() {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            pendingStartAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpnConsentAndStart()
        }
    }

    /**
     * VpnService.prepare() возвращает:
     *  - null    — разрешение уже есть, можно стартовать;
     *  - Intent  — нужно показать системный диалог согласия.
     */
    private fun requestVpnConsentAndStart() {
        val consentIntent: Intent? = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            AppLog.e(TAG, "VpnService.prepare() завершился ошибкой: ${e.message}", e)
            Toast.makeText(
                this,
                getString(R.string.vpn_prepare_error, e.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (consentIntent == null) {
            AppLog.i(TAG, "Разрешение VPN уже выдано")
            startVpnService()
        } else {
            AppLog.i(TAG, "Показываем системный диалог разрешения VPN")
            vpnPermissionLauncher.launch(consentIntent)
        }
    }

    private fun startVpnService() {
        AppLog.i(TAG, "Передаём команду START в ZapretVpnService")
        VpnController.start(this)
    }

    // -----------------------------------------------------------------------
    // Отрисовка состояния
    // -----------------------------------------------------------------------

    /** Набор ресурсов, зависящий от фазы. */
    private data class StatusUi(
        val labelRes: Int,
        val hintRes: Int,
        val colorRes: Int,
        val vpnValueRes: Int
    )

    private fun statusFor(phase: VpnPhase): StatusUi = when (phase) {
        VpnPhase.STOPPED -> StatusUi(
            R.string.power_label_off,
            R.string.power_hint_off,
            R.color.state_off,
            R.string.state_value_stopped
        )

        VpnPhase.STARTING -> StatusUi(
            R.string.power_label_starting,
            R.string.power_hint_starting,
            R.color.state_busy,
            R.string.state_value_starting
        )

        VpnPhase.RUNNING -> StatusUi(
            R.string.power_label_running,
            R.string.power_hint_running,
            R.color.state_on,
            R.string.state_value_running
        )

        VpnPhase.STOPPING -> StatusUi(
            R.string.power_label_stopping,
            R.string.power_hint_stopping,
            R.color.state_busy,
            R.string.state_value_stopping
        )

        VpnPhase.ERROR -> StatusUi(
            R.string.power_label_error,
            R.string.power_hint_error,
            R.color.state_off,
            R.string.state_value_error
        )
    }

    private fun render(state: VpnState) {
        currentState = state
        val ui = statusFor(state.phase)
        val stateColor = ContextCompat.getColor(this, ui.colorRes)

        // Большой круг: цвет зависит от состояния.
        binding.powerCircle.backgroundTintList = ColorStateList.valueOf(stateColor)

        binding.tvPowerLabel.setText(ui.labelRes)
        binding.tvPowerLabel.setTextColor(stateColor)

        binding.tvPowerHint.text = if (state.phase == VpnPhase.ERROR) {
            state.errorMessage ?: getString(R.string.power_hint_error)
        } else {
            getString(ui.hintRes)
        }

        // Карточка «Состояние».
        binding.tvVpnValue.setText(ui.vpnValueRes)
        binding.tvTunValue.text = state.tunName ?: getString(R.string.value_none)
        binding.tvEngineValue.text = state.engineLabel ?: getString(R.string.value_none)
        binding.tvSocksValue.text = if (state.phase == VpnPhase.RUNNING) {
            "${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT}"
        } else {
            getString(R.string.value_none)
        }
        binding.tvPresetValue.text = state.presetName
        binding.tvRotationsValue.text = getString(R.string.number_format, state.rotationCount)

        // Доступность: TalkBack должен понимать, что делает кнопка.
        binding.powerContainer.contentDescription = getString(
            if (state.phase == VpnPhase.RUNNING ||
                state.phase == VpnPhase.STARTING ||
                state.phase == VpnPhase.STOPPING
            ) {
                R.string.power_button_description_on
            } else {
                R.string.power_button_description_off
            }
        )

        updateUptime()
        updateTraffic()
    }

    private fun updateUptime() {
        val startedAt = currentState.startedAt
        binding.tvUptimeValue.text = if (startedAt == null) {
            getString(R.string.value_none)
        } else {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
            DateUtils.formatElapsedTime(seconds)
        }
    }

    /**
     * Статистика hev: [tx_packets, tx_bytes, rx_packets, rx_bytes].
     * tx — от приложений в туннель (upload), rx — из интернета (download).
     */
    private fun updateTraffic() {
        if (currentState.phase != VpnPhase.RUNNING) {
            binding.tvTrafficValue.text = getString(R.string.value_none)
            return
        }

        val stats = HevBridge.statsOrNull()
        if (stats == null || stats.size < 4) {
            binding.tvTrafficValue.text = getString(R.string.value_none)
            return
        }

        val rxBytes = stats[3].coerceAtLeast(0L)
        val txBytes = stats[1].coerceAtLeast(0L)
        binding.tvTrafficValue.text = getString(
            R.string.traffic_format,
            Formatter.formatShortFileSize(this, rxBytes),
            Formatter.formatShortFileSize(this, txBytes)
        )
    }

    private fun renderLogs(entries: List<LogEntry>) {
        val visible = entries.takeLast(LOG_PREVIEW_LINES)
        binding.tvLog.text = if (visible.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            visible.joinToString(separator = "\n") { it.toDisplayLine() }
        }
    }
}
