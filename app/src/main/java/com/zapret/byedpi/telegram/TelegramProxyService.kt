package com.zapret.byedpi.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zapret.byedpi.R
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.ui.SettingsActivity
import com.zapret.byedpi.util.NotificationHelper
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class TelegramProxyService : Service() {

    companion object {
        const val ACTION_START = "com.zapret.byedpi.telegram.START"
        const val ACTION_STOP = "com.zapret.byedpi.telegram.STOP"
        const val ACTION_SWITCH = "com.zapret.byedpi.telegram.SWITCH"

        private const val TAG = "TelegramProxy"
        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIFICATION_ID = 42

        /**
         * Сколько секунд без прироста `down` считаем зависанием.
         *
         * Раньше таймер сбрасывался на каждом новом соединении, и на мобильном
         * интернете это давало вечный «застой»: Telegram переподключается
         * быстрее, чем истекает окно, данные при этом не идут, а watchdog молчит
         * (в логе 0.6.2 — 47 висящих соединений и 32 минуты без единого байта
         * вниз). Теперь прогресс — только реальные байты вниз.
         */
        private const val STALL_MS = 60_000L

        /** Не перезапускать чаще, чем раз в минуту. */
        private const val RESTART_COOLDOWN_MS = 60_000L

        /** Пул из 8; если активных вчетверо больше при нулевом трафике — они мёртвые. */
        private const val STUCK_ACTIVE = 32

        /** Прокси поднялся, но клиентов нет дольше этого времени — подсказываем. */
        private const val NO_CLIENTS_MS = 120_000L

        /** Застои, разнесённые дальше этого окна, считаются заново. */
        private const val STALL_RESET_MS = 30 * 60_000L

        /**
         * Пропуск между опросами статистики больше этого означает, что систему
         * заморозила (Doze / агрессивная экономия Xiaomi). Это не застой канала,
         * перезапускать прокси из-за него не нужно.
         */
        private const val FREEZE_GAP_MS = 150_000L

        /**
         * Сколько байт вниз должен отдать канал, чтобы считать его рабочим
         * и запомнить выбор транспорта для следующего запуска.
         */
        private const val GOOD_DOWNLOAD_BYTES = 400_000L

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

        /**
         * Переключить транспорт Cloudflare (TLS 443 ↔ HTTP 80).
         *
         * На разных операторах «работает» разный канал: у кого-то режут
         * TLS по SNI, у кого-то — HTTP на 80. Кнопка даёт тестировщику
         * проверить второй канал за пару секунд, не дожидаясь watchdog.
         */
        fun switchChannel(context: Context) {
            val intent = Intent(context, TelegramProxyService::class.java)
                .setAction(ACTION_SWITCH)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                AppLog.w(TAG, "Переключение канала: ${t.message}")
            }
        }
    }

    /** Когда watchdog последний раз перезапускал прокси (антидребезг). */
    @Volatile
    private var lastRestartAt = 0L

    /**
     * Номер поколения stats-потока: после перезапуска старый поток обязан
     * выйти, иначе два потока начнут дублировать опросы и перезапуски.
     */
    @Volatile
    private var statsGeneration = 0

    /** Сколько подряд застоев: со второго пробуем другой транспорт CF. */
    @Volatile
    private var stallCount = 0

    @Volatile
    private var lastStallAt = 0L

    /** Переопределение Cloudflare только на текущий запуск службы. */
    @Volatile
    private var cfOverride: Boolean? = null

    /** Переопределение транспорта (TLS/HTTP) только на текущий запуск службы. */
    @Volatile
    private var secureOverride: Boolean? = null

    /** Что реально применено к нативной библиотеке прямо сейчас. */
    @Volatile
    private var effectiveSecure = true

    @Volatile
    private var effectiveCf = true

    /** Момент, когда прокси поднялся: нужен для подсказки «Telegram не подключается». */
    @Volatile
    private var proxyStartedAt = 0L

    @Volatile
    private var noClientsNotified = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastNetworkId: String? = null

    @Volatile
    private var networkRestartPending = false

    override fun onCreate() {
        super.onCreate()
        // Канал проблем общий с VPN-службой: создаём и здесь, иначе
        // уведомление о сбое прокси не покажется, если VPN не запускали.
        NotificationHelper.createChannel(this)
        createChannel()
        registerNetworkMonitor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            ACTION_SWITCH -> {
                if (TelegramRuntime.state.value.phase == TelegramPhase.RUNNING) {
                    // StopProxy + повторный старт блокируют поток на секунды,
                    // поэтому уводим переключение с главного потока службы.
                    thread(name = "tgws-switch", isDaemon = true) { switchChannel() }
                } else {
                    // Прокси не запущен: просто запоминаем выбор для следующего старта.
                    val next = !TelegramPrefs.secure()
                    TelegramPrefs.setSecure(next)
                    TelegramRuntime.update { it.copy(channel = channelLabelFor(next)) }
                    AppLog.i(TAG, "Канал для следующего запуска: ${channelLabelFor(next)}")
                    stopSelfResult(startId)
                }
            }
            else -> {
                if (TelegramRuntime.state.value.phase == TelegramPhase.STOPPED) {
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------------------------------------------------
    // Смена сети
    // -----------------------------------------------------------------------

    /**
     * Wi-Fi ↔ мобильные данные.
     *
     * Прокси держит долгоживущие TLS-соединения до Cloudflare и «залипший»
     * выбор домена: после смены сети они мертвы, но выглядят живыми —
     * соединение установлено, данных нет. В логах это ровно тот случай,
     * на который жаловался владелец: на Wi-Fi работает, на мобильном нет
     * (и наоборот). Поэтому на смену сети перезапускаем прокси целиком.
     */
    private fun registerNetworkMonitor() {
        try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = manager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val id = network.toString()
                    val previous = lastNetworkId
                    lastNetworkId = id
                    if (previous == null || previous == id) return
                    AppLog.w(TAG, "Смена сети: $previous -> $id")
                    scheduleRestartForNetwork()
                }
            }
            networkCallback = callback
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            manager.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            AppLog.w(TAG, "NetworkCallback не зарегистрирован: ${t.message}")
        }
    }

    private fun unregisterNetworkMonitor() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
        connectivityManager = null
    }

    private fun scheduleRestartForNetwork() {
        if (networkRestartPending) return
        if (TelegramRuntime.state.value.phase != TelegramPhase.RUNNING) return
        networkRestartPending = true
        thread(name = "tgws-net", isDaemon = true) {
            try {
                // Сети нужно полсекунды-секунда, чтобы реально подняться.
                Thread.sleep(1_500L)
                if (TelegramRuntime.state.value.phase == TelegramPhase.RUNNING && !stopRequested) {
                    AppLog.i(TAG, "Перезапуск прокси после смены сети")
                    restartProxy("смена сети", asStall = false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                networkRestartPending = false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Запуск
    // -----------------------------------------------------------------------

    private fun startProxy(fresh: Boolean = true) {
        if (working) return
        stopRequested = false
        val current = TelegramRuntime.state.value
        if (current.phase == TelegramPhase.RUNNING ||
            current.phase == TelegramPhase.STARTING
        ) {
            return
        }
        if (fresh) {
            // Пользователь нажал «Запустить»: берём настройки из prefs,
            // а не «залипшие» переопределения прошлого запуска.
            secureOverride = null
            cfOverride = null
            stallCount = 0
        }
        val port = TelegramPrefs.port()
        val secure = secureOverride ?: TelegramPrefs.secure()
        val cf = cfOverride ?: TelegramPrefs.cfEnabled()
        effectiveSecure = secure
        effectiveCf = cf
        val channel = channelLabelFor(secure, cf)

        TelegramRuntime.update {
            it.copy(
                phase = TelegramPhase.STARTING,
                port = port,
                secret = null,
                error = null,
                channel = channel
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
                AppLog.i(TAG, "Запуск Telegram WS-прокси на 127.0.0.1:$port, канал: $channel")
                // ВАЖНО: сначала транспорт, потом старт — библиотека читает
                // DISABLE_SECURE при каждом соединении, но порядок так надёжнее
                // и виден в логе до первого коннекта.
                TelegramNative.setDisableSecure(!secure)
                TelegramNative.configurePool(
                    poolSize = 8,
                    cacheDir = cacheDir.absolutePath,
                    cfEnabled = cf
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

                /*
                 * Проверяем, что порт реально слушается. В логе 0.6.4 после
                 * перезапуска watchdog'ом прокси писал «работает», а Telegram
                 * к нему не подключался: порт остался мёртвым. Одна попытка
                 * перезапуска лечит это; если нет — честно показываем ошибку.
                 */
                if (!isPortListening(port)) {
                    AppLog.w(TAG, "порт $port не слушается после запуска, перезапускаю")
                    try {
                        TelegramNative.stopProxy()
                    } catch (_: Throwable) {
                    }
                    Thread.sleep(700L)
                    val retry = TelegramNative.startProxy(
                        host = "127.0.0.1",
                        port = port,
                        dcIps = "",
                        secret = secret,
                        verbose = true
                    )
                    if (retry != 0 || !isPortListening(port)) {
                        val text = getString(R.string.telegram_port_dead, port)
                        AppLog.e(TAG, text)
                        fail(text)
                        NotificationHelper.notifyProblem(this, text)
                        return@thread
                    }
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
                        error = null,
                        channel = channel
                    )
                }
                proxyStartedAt = System.currentTimeMillis()
                noClientsNotified = false
                AppLog.i(TAG, "Telegram WS-прокси работает: 127.0.0.1:$port, канал: $channel")
                TelegramNative.stats()?.let { AppLog.i(TAG, "stats: $it") }
                startStatsLogger()
                updateNotification(getString(R.string.telegram_running_channel, port, channel))
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
        secureOverride = null
        cfOverride = null
        stallCount = 0
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
        NotificationHelper.notifyProblem(this, getString(R.string.telegram_error, message))
    }

    // -----------------------------------------------------------------------
    // Статистика и watchdog
    // -----------------------------------------------------------------------

    private fun startStatsLogger() {
        if (statsRunning) return
        statsRunning = true
        val generation = ++statsGeneration
        thread(name = "tgws-stats", isDaemon = true) {
            var lastDown = -1L
            var lastUp = -1L
            var lastTotal = -1L
            var lastSampleAt = System.currentTimeMillis()
            var lastProgressAt = lastSampleAt
            var goodBytes = 0L
            var modeSaved = false

            while (statsRunning && generation == statsGeneration &&
                TelegramRuntime.state.value.phase == TelegramPhase.RUNNING
            ) {
                try {
                    Thread.sleep(15_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!statsRunning || generation != statsGeneration) break

                val stats = TelegramNative.stats() ?: continue
                AppLog.i(TAG, "stats: $stats")

                val down = parseBytes(statValue(stats, "down")) ?: continue
                val up = parseBytes(statValue(stats, "up")) ?: 0L
                val total = statValue(stats, "total")?.toLongOrNull() ?: -1L
                val active = statValue(stats, "active")?.toIntOrNull() ?: 0
                val now = System.currentTimeMillis()

                /*
                 * Большой пропуск между опросами — это не «канал умер»,
                 * а Doze/экономия батареи: процесс заморозили, поток
                 * проснулся позже. Иначе watchdog перезапускал бы прокси
                 * каждый раз после разблокировки экрана.
                 */
                if (now - lastSampleAt > FREEZE_GAP_MS) {
                    AppLog.w(
                        TAG,
                        "watchdog: процесс был заморожен ${(now - lastSampleAt) / 1000}с — " +
                            "таймеры сброшены, перезапуск не нужен"
                    )
                    lastProgressAt = now
                    lastDown = down
                    lastUp = up
                    lastTotal = total
                    lastSampleAt = now
                    continue
                }
                lastSampleAt = now

                /*
                 * Прокси поднялся, но к нему никто не подключается дольше
                 * двух минут — обычно это значит, что прокси не добавлен
                 * в Telegram. Говорим об этом прямо, иначе выглядит как
                 * «прокси не работает».
                 */
                if (!noClientsNotified && total == 0L && proxyStartedAt > 0 &&
                    now - proxyStartedAt > NO_CLIENTS_MS
                ) {
                    noClientsNotified = true
                    val hint = getString(R.string.telegram_no_clients)
                    AppLog.w(TAG, hint)
                    NotificationHelper.notifyProblem(this, hint)
                }

                val downGrew = down > lastDown
                if (downGrew) {
                    goodBytes += (down - lastDown).coerceAtLeast(0L)
                    lastProgressAt = now
                    /*
                     * Канал реально отдал данные (порог GOOD_DOWNLOAD_BYTES):
                     * сбрасываем счётчик застоев и один раз за запуск
                     * запоминаем транспорт, который отработал, — следующий
                     * запуск начнётся с него, а не с дефолтного.
                     */
                    if (goodBytes >= GOOD_DOWNLOAD_BYTES) {
                        goodBytes = 0
                        stallCount = 0
                        if (!modeSaved) {
                            modeSaved = true
                            if (TelegramPrefs.secure() != effectiveSecure) {
                                TelegramPrefs.setSecure(effectiveSecure)
                                AppLog.i(
                                    TAG,
                                    "канал ${channelLabelFor(effectiveSecure)} отдал данные — " +
                                        "запоминаю как рабочий"
                                )
                            }
                        }
                    }
                }

                val attempts = total != lastTotal || up > lastUp
                val stuckPool = active >= STUCK_ACTIVE
                /*
                 * Зависание — это когда клиент пробует (новые соединения
                 * или отправка вверх), а вниз ничего не приходит. Тихий
                 * прокси с живым соединением зависанием не считаем.
                 */
                if (!downGrew && active > 0 && (attempts || stuckPool) &&
                    now - lastProgressAt >= STALL_MS
                ) {
                    val stalledSec = (now - lastProgressAt) / 1000
                    if (now - lastRestartAt < RESTART_COOLDOWN_MS) {
                        AppLog.w(
                            TAG,
                            "watchdog: данных нет ${stalledSec}с (active=$active, " +
                                "total=$total), перезапуск был недавно — жду"
                        )
                    } else {
                        AppLog.w(
                            TAG,
                            "watchdog: данных нет ${stalledSec}с при active=$active, " +
                                "up=${statValue(stats, "up")}, total=$total " +
                                "(канал ${channelLabelFor(effectiveSecure, effectiveCf)}) — " +
                                "перезапуск"
                        )
                        statsRunning = false
                        restartProxy(
                            "нет данных ${stalledSec}с при active=$active, total=$total"
                        )
                        return@thread
                    }
                }

                if (downGrew) lastDown = down else if (down < lastDown) lastDown = down
                if (up > lastUp) lastUp = up
                lastTotal = total
            }
        }
    }

    /**
     * Перезапускает нативный прокси, не убивая службу.
     *
     * `asStall = true` — это реакция на застой: считаем попытки и со второй
     * подряд меняем транспорт Cloudflare (TLS 443 ↔ HTTP 80). `false` —
     * технический перезапуск (смена сети, ручное переключение канала),
     * транспорт остаётся тем, что выбран.
     */
    private fun restartProxy(reason: String, asStall: Boolean = true) {
        val now = System.currentTimeMillis()
        if (asStall) {
            if (now - lastStallAt > STALL_RESET_MS) stallCount = 0
            stallCount++
            lastStallAt = now

            /*
             * Второй застой подряд: транспорт не пропускает трафик.
             * Пробуем противоположный — у операторов по-разному: кто-то
             * режет TLS по SNI, кто-то HTTP на 80.
             */
            if (stallCount >= 2 && secureOverride == null) {
                val next = !effectiveSecure
                secureOverride = next
                TelegramPrefs.setSecure(next)
                AppLog.w(
                    TAG,
                    "watchdog: $reason — переключаю транспорт на ${channelLabelFor(next)}"
                )
                NotificationHelper.notifyProblem(
                    this,
                    getString(R.string.telegram_channel_switched, channelLabelFor(next))
                )
            } else if (stallCount >= 4 && cfOverride == null) {
                val next = !effectiveCf
                cfOverride = next
                AppLog.w(TAG, "watchdog: $reason — Cloudflare $effectiveCf -> $next")
                NotificationHelper.notifyProblem(this, getString(R.string.telegram_cf_switched))
            }
        }
        lastRestartAt = now

        try {
            TelegramNative.stats()?.let { AppLog.i(TAG, "stats before restart: $it") }
            TelegramNative.stopProxy()
        } catch (t: Throwable) {
            AppLog.w(TAG, "watchdog: StopProxy: ${t.message}")
        }
        TelegramRuntime.update { it.copy(phase = TelegramPhase.STOPPED) }
        try {
            Thread.sleep(700L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        startProxy(fresh = false)
    }

    /** Ручное переключение транспорта Cloudflare из UI. */
    private fun switchChannel() {
        val next = !effectiveSecure
        secureOverride = next
        TelegramPrefs.setSecure(next)
        AppLog.i(TAG, "Ручное переключение канала на ${channelLabelFor(next)}")
        statsRunning = false
        restartProxy("ручное переключение", asStall = false)
    }

    private fun channelLabelFor(secure: Boolean, cf: Boolean = effectiveCf): String = when {
        !cf -> getString(R.string.telegram_channel_direct)
        secure -> getString(R.string.telegram_channel_tls)
        else -> getString(R.string.telegram_channel_http)
    }

    /** Проверяет, что на порту действительно кто-то слушает. */
    private fun isPortListening(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            true
        }
    } catch (_: Throwable) {
        false
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

    // -----------------------------------------------------------------------
    // Уведомление и жизненный цикл
    // -----------------------------------------------------------------------

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
        unregisterNetworkMonitor()
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
