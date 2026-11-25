package com.zapret.byedpi.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Тонкая обёртка над запуском/остановкой службы.
 *
 * Activity не должна знать детали Intent'ов и флагов Android 8+.
 * Здесь же централизованно логируются ошибки старта foreground-службы.
 */
object VpnController {
    private const val TAG = "VpnController"

    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Перезапускает VPN после изменения Per-App/доменов.
     *
     * Живёт в собственном scope: Activity может закрыться сразу после
     * сохранения, а перезапуск должен дойти до конца.
     */
    fun restartAsync(context: Context) {
        restartScope.launch { restart(context) }
    }

    /**
     * То же, но suspend: Activity держится на экране, пока VPN
     * останавливается и снова стартует. Это важно для Android 12+:
     * запуск foreground-службы из уже закрытой Activity может быть запрещён.
     */
    suspend fun restart(context: Context) {
        val phase = VpnRuntime.state.value.phase
        AppLog.i(TAG, "Перезапуск VPN после изменения настроек (phase=$phase)")

        if (phase == VpnPhase.RUNNING ||
            phase == VpnPhase.STARTING ||
            phase == VpnPhase.STOPPING
        ) {
            stop(context)
            val stopped = withTimeoutOrNull(15_000L) {
                VpnRuntime.state.first {
                    it.phase == VpnPhase.STOPPED || it.phase == VpnPhase.ERROR
                }
                true
            } ?: false
            if (!stopped) {
                AppLog.w(TAG, "VPN не остановился за 15 секунд, пробуем старт")
            }
            delay(300L)
        }

        start(context)
    }

    /** Запускает ZapretVpnService как foreground-службу. */
    fun start(context: Context) {
        val intent = Intent(context, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_START
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Например, ForegroundServiceStartNotAllowedException на Android 12+,
            // если система решила, что приложение не в foreground.
            AppLog.e(TAG, "Не удалось запустить службу VPN: ${e.message}", e)
            VpnRuntime.update {
                it.copy(
                    phase = VpnPhase.ERROR,
                    errorMessage = "Не удалось запустить службу: ${e.message}"
                )
            }
        }
    }

    /**
     * Просит службу корректно остановиться (закрыть TUN, убрать уведомление).
     *
     * Используем startService, а не startForegroundService: служба уже
     * работает, а для команды STOP ей не нужно снова входить в foreground.
     * Если система всё же запретила старт, аварийно вызываем stopService —
     * onDestroy службы закроет файловый дескриптор.
     */
    fun stop(context: Context) {
        val intent = Intent(context, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_STOP
        }

        try {
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "startService(STOP) не сработал: ${e.message}. Пробуем stopService()")
            context.stopService(Intent(context, ZapretVpnService::class.java))
        }
    }
}
