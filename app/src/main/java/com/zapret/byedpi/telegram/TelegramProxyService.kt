package com.zapret.byedpi.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zapret.byedpi.R
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.ui.SettingsActivity
import kotlin.concurrent.thread

class TelegramProxyService : Service() {

    companion object {
        const val ACTION_START = "com.zapret.byedpi.telegram.START"
        const val ACTION_STOP = "com.zapret.byedpi.telegram.STOP"

        private const val TAG = "TelegramProxy"
        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIFICATION_ID = 42

        /** Сколько секунд без прироста `down` считаем зависанием. */
        private const val STALL_MS = 90_000L

        /** Не перезапускать чаще, чем раз в 10 минут. */
        private const val RESTART_COOLDOWN_MS = 600_000L

        /** Пул из 8; если активных вчетверо больше при нулевом трафике — они мёртвые. */
        private const val STUCK_ACTIVE = 32

        @Volatile
        private var working = false

        @Volatile
        private var stopRequested = false

        @Volatile
        private var statsRunning = false

        fun start(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, TelegramProxyService::class.java))
            }
        }
    }

    /** Когда watchdog последний раз перезапускал прокси (антидребезг). */
    @Volatile
    private var lastRestartAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            else -> {
                if (TelegramRuntime.state.value.phase == TelegramPhase.STOPPED) {
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        if (working) return
        stopRequested = false
        val current = TelegramRuntime.state.value
        if (current.phase == TelegramPhase.RUNNING ||
            current.phase == TelegramPhase.STARTING
        ) {
            return
        }
        val port = TelegramPrefs.port()
        TelegramRuntime.update {
            it.copy(
                phase = TelegramPhase.STARTING,
                port = port,
                secret = null,
                error = null
            )
        }
        startForegroundCompat(getString(R.string.telegram_starting))

        synchronized(this) {
            if (working) return
            working = true
        }

        thread(name = "tgws-start", isDaemon = true) {
            try {
                if (!TelegramNative.available) {
                    fail(TelegramNative.loadError ?: getString(R.string.telegram_error_native))
                    return@thread
                }

                val secret = TelegramPrefs.secret()
                AppLog.i(TAG, "Запуск Telegram WS-прокси на 127.0.0.1:$port")
                TelegramNative.configurePool(
                    poolSize = 8,
                    cacheDir = cacheDir.absolutePath,
                    cfEnabled = TelegramPrefs.cfEnabled()
                )
                val rc = TelegramNative.startProxy(
                    host = "127.0.0.1",
                    port = port,
                    dcIps = "",
                    secret = secret,
                    verbose = true
                )
                if (rc != 0) {
                    fail("StartProxy вернул код $rc")
                    return@thread
                }

                if (stopRequested) {
                    TelegramNative.stopProxy()
                    TelegramRuntime.update { it.copy(phase = TelegramPhase.STOPPED) }
                    stopForegroundCompat()
                    stopSelf()
                    return@thread
                }

                val fullSecret = TelegramNative.secretWithPrefix() ?: "dd$secret"
                TelegramRuntime.update {
                    it.copy(
                        phase = TelegramPhase.RUNNING,
                        port = port,
                        secret = fullSecret,
                        error = null
                    )
                }
                AppLog.i(TAG, "Telegram WS-прокси работает: 127.0.0.1:$port")
                TelegramNative.stats()?.let { AppLog.i(TAG, "stats: $it") }
                startStatsLogger()
                updateNotification(getString(R.string.telegram_running, port))
            } catch (t: Throwable) {
                AppLog.e(TAG, "Ошибка запуска Telegram WS-прокси: ${t.message}", t)
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                working = false
            }
        }
    }

    private fun stopProxy() {
        stopRequested = true
        statsRunning = false
        if (TelegramRuntime.state.value.phase == TelegramPhase.STOPPED) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        TelegramRuntime.update { it.copy(phase = TelegramPhase.STOPPED) }
        thread(name = "tgws-stop", isDaemon = true) {
            try {
                TelegramNative.stats()?.let { AppLog.i(TAG, "stats before stop: $it") }
                TelegramNative.stopProxy()
            } catch (t: Throwable) {
                AppLog.w(TAG, "StopProxy: ${t.message}")
            }
            AppLog.i(TAG, "Telegram WS-прокси остановлен")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun fail(message: String) {
        TelegramRuntime.update {
            it.copy(phase = TelegramPhase.ERROR, error = message)
        }
        AppLog.e(TAG, "Telegram WS-прокси: $message")
        updateNotification(getString(R.string.telegram_error, message))
    }

    private fun startStatsLogger() {
        if (statsRunning) return
        statsRunning = true
        thread(name = "tgws-stats", isDaemon = true) {
            var lastDown = -1L
            var lastUp = -1L
            var lastProgressAt = System.currentTimeMillis()
            var lastTotal = -1L
            while (statsRunning && TelegramRuntime.state.value.phase == TelegramPhase.RUNNING) {
                try {
                    Thread.sleep(15_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!statsRunning) break

                val stats = TelegramNative.stats() ?: continue
                AppLog.i(TAG, "stats: $stats")

                val down = parseBytes(statValue(stats, "down")) ?: continue
                val up = parseBytes(statValue(stats, "up")) ?: 0L
                val total = statValue(stats, "total")?.toLongOrNull() ?: -1L
                val active = statValue(stats, "active")?.toIntOrNull() ?: 0
                val now = System.currentTimeMillis()

                val newConnections = total != lastTotal
                val upGrew = up > lastUp
                /*
                 * Зависание — это когда клиент что-то отправил, а вниз
                 * ничего не пришло. Тихий прокси с живым соединением
                 * (Telegram просто молчит) зависанием не считаем, иначе
                 * перезапускали бы его каждые 10 минут на ровном месте.
                 *
                 * Отдельный случай — распухший пул: active сильно больше
                 * размера пула (8) при нулевом трафике, значит соединения
                 * висят мёртвыми.
                 */
                val stuckPool = active >= STUCK_ACTIVE
                when {
                    down > lastDown -> {
                        lastDown = down
                        lastUp = up
                        lastProgressAt = now
                    }
                    newConnections -> {
                        // Новые соединения: даём им шанс, таймер с нуля.
                        lastProgressAt = now
                        lastUp = up
                    }
                    (upGrew || stuckPool) && active > 0 &&
                        now - lastProgressAt >= STALL_MS -> {
                        val stalledMs = now - lastProgressAt
                        if (now - lastRestartAt < RESTART_COOLDOWN_MS) {
                            AppLog.w(
                                TAG,
                                "watchdog: down стоит ${stalledMs / 1000}с, " +
                                    "up=${statValue(stats, "up")}, active=$active — " +
                                    "перезапуск был недавно, жду"
                            )
                        } else {
                            lastRestartAt = now
                            AppLog.w(
                                TAG,
                                "watchdog: down стоит ${stalledMs / 1000}с при " +
                                    "up=${statValue(stats, "up")}, active=$active " +
                                    "(total=$total) — перезапуск прокси"
                            )
                            statsRunning = false
                            restartAfterStall()
                            return@thread
                        }
                    }
                }
                lastTotal = total
                if (up > lastUp) lastUp = up
            }
        }
    }

    /**
     * Перезапускает нативный прокси, не убивая службу: сначала StopProxy,
     * потом обычный путь запуска (он сам поднимет новый stats-логгер).
     */
    private fun restartAfterStall() {
        try {
            TelegramNative.stats()?.let { AppLog.i(TAG, "stats before restart: $it") }
            TelegramNative.stopProxy()
        } catch (t: Throwable) {
            AppLog.w(TAG, "watchdog: StopProxy: ${t.message}")
        }
        TelegramRuntime.update { it.copy(phase = TelegramPhase.STOPPED) }
        try {
            Thread.sleep(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        startProxy()
    }

    private fun statValue(stats: String, key: String): String? =
        Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)").find(stats)?.groupValues?.get(1)

    /**
     * Превращает `2.8MB` / `234.9KB` / `0.0B` из статистики в байты.
     * Формат задаёт нативная библиотека, поэтому разбираем аккуратно:
     * не распознали — считаем, что данных нет.
     */
    private fun parseBytes(value: String?): Long? {
        if (value == null) return null
        val match = Regex("^([0-9]+(?:[.,][0-9]+)?)([BKMGT]?)B?$").find(value.trim()) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024.0
            "G" -> 1024.0 * 1024.0 * 1024.0
            "T" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (number * multiplier).toLong()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, SettingsActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TelegramProxyService::class.java)
            .setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.telegram_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_nav_logs)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notification_action_stop), stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.telegram_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.telegram_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        statsRunning = false
        if (TelegramRuntime.state.value.phase == TelegramPhase.RUNNING) {
            try {
                TelegramNative.stopProxy()
            } catch (_: Throwable) {
            }
            TelegramRuntime.update { it.copy(phase = TelegramPhase.STOPPED) }
        }
        super.onDestroy()
    }
}
