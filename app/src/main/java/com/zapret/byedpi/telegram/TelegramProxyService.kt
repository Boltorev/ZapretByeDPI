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

        /** Ручной выбор канала: extra [EXTRA_SECURE] = true (TLS 443) / false (HTTP 80). */
        const val ACTION_SET_CHANNEL = "com.zapret.byedpi.telegram.SET_CHANNEL"
        const val EXTRA_SECURE = "secure"

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
         * и запомнить выбор транспорта для следующей сети.
         */
        private const val GOOD_DOWNLOAD_BYTES = 400_000L

        // --- Автоподбор канала при старте -----------------------------------
        //
        // Канал зависит от сети: на мобильном интернете обычно проходит
        // TLS 443, на домашнем Wi-Fi — HTTP 80 (проверено на логах 0.6.6:
        // на 443 `ws tls fail … timeout`, на 80 `CF ok`). Поэтому при первом
        // запуске в незнакомой сети оба канала проверяются автоматически,
        // а не через две минуты ожидания watchdog.

        /** Интервал опроса в режиме подбора канала. */
        private const val PROBE_SAMPLE_MS = 6_000L

        /** Через сколько секунд без единого CF-соединения канал считается мёртвым. */
        private const val PROBE_NO_CF_MS = 12_000L

        /** Через сколько секунд без данных (при живом CF) канал считается слабым. */
        private const val PROBE_NO_DATA_MS = 30_000L

        /** Сколько данных достаточно, чтобы признать канал рабочим в подборе. */
        private const val PROBE_GOOD_BYTES = 8 * 1024L

        /** Сколько раз автоподбор может переключить канал за один подбор. */
        private const val PROBE_MAX_SWITCHES = 1

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
         * Ручной выбор канала (TLS 443 / HTTP 80).
         *
         * Выбор запоминается для текущей сети и применяется сразу, если прокси
         * запущен. Кнопки в UI вызывают именно это — автоподбор после ручного
         * выбора не вмешивается.
         */
        fun selectChannel(context: Context, secure: Boolean) {
            val intent = Intent(context, TelegramProxyService::class.java)
                .setAction(ACTION_SET_CHANNEL)
                .putExtra(EXTRA_SECURE, secure)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                AppLog.w(TAG, "Выбор канала: ${t.message}")
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

    /** Ручной/подобранный транспорт только на текущий запуск службы. */
    @Volatile
    private var secureOverride: Boolean? = null

    /** Что реально применено к нативной библиотеке прямо сейчас. */
    @Volatile
    private var effectiveSecure = true

    @Volatile
    private var effectiveCf = true

    /** Вид текущей сети: Wi-Fi / мобильная / прочее — ключ для запоминания канала. */
    @Volatile
    private var networkKind: String = TelegramPrefs.NET_OTHER

    /** Автоподбор канала: закончен ли он для текущей сети. */
    @Volatile
    private var probeDone = true

    /**
     * Подбор канала для этой сети уже сделан. Держится между перезапусками
     * прокси (watchdog, смена канала), сбрасывается только при новом запуске
     * пользователем и при смене сети — иначе каждая перезагрузка заново
     * дёргала бы оба канала.
     */
    @Volatile
    private var probeFinished = false

    /** Сколько переключений сделал автоподбор (ограничено PROBE_MAX_SWITCHES). */
    @Volatile
    private var probeSwitches = 0

    /** Какие транспорты уже проверены в этом подборе. */
    private val probeTried = HashSet<Boolean>()

    /**
     * Канал выбран вручную. Пока флаг стоит, автоподбор его не перетирает:
     * человек мог выбрать канал специально (например, чтобы проверить).
     * Сбрасывается при новом запуске и при смене сети.
     */
    @Volatile
    private var manualChannel = false

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
            ACTION_SET_CHANNEL -> {
                val secure = intent.getBooleanExtra(EXTRA_SECURE, true)
                // Переключение нативной библиотеки блокирует поток на секунды,
                // поэтому уводим его с главного потока службы.
                thread(name = "tgws-channel", isDaemon = true) {
                    selectChannelBlocking(secure)
                    val phase = TelegramRuntime.state.value.phase
                    if (phase != TelegramPhase.RUNNING && phase != TelegramPhase.STARTING) {
                        // Прокси не был запущен: служба только запомнила выбор.
                        stopForegroundCompat()
                        stopSelf(startId)
                    }
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
    // Сети и каналы
    // -----------------------------------------------------------------------

    /**
     * Какая сейчас сеть: Wi-Fi, мобильная или прочее.
     *
     * Нужна, чтобы запоминать рабочий канал отдельно для Wi-Fi и для
     * мобильного интернета: у них проходят разные транспорты. VPN-сети
     * пропускаем — интересует физический канал.
     */
    private fun detectNetworkKind(): String {
        return try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            var cellular = false
            for (network in manager.allNetworks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).not()) continue
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> return TelegramPrefs.NET_WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular = true
                }
            }
            if (cellular) TelegramPrefs.NET_CELL else TelegramPrefs.NET_OTHER
        } catch (t: Throwable) {
            AppLog.w(TAG, "Не удалось определить тип сети: ${t.message}")
            TelegramPrefs.NET_OTHER
        }
    }

    /**
     * Wi-Fi ↔ мобильные данные.
     *
     * Прокси держит долгоживущие соединения до Cloudflare и «залипший» выбор
     * домена: после смены сети они мертвы, но выглядят живыми — соединение
     * установлено, данных нет. В логе 0.6.6 ровно это: на Wi-Fi `ws tls fail
     * … timeout`, а на мобильном тот же канал работает. Поэтому на смену сети
     * перезапускаем прокси и заново выбираем канал под новую сеть.
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
                    // Сеть другая — канал для неё может быть другим,
                    // и подбирать его нужно заново.
                    secureOverride = null
                    networkKind = detectNetworkKind()
                    probeSwitches = 0
                    probeTried.clear()
                    probeFinished = false
                    manualChannel = false
                    stallCount = 0
                    AppLog.i(
                        TAG,
                        "Перезапуск прокси после смены сети (${networkKindForLog()})"
                    )
                    restartProxy("смена сети", asStall = false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                networkRestartPending = false
            }
        }
    }

    /** Ручной выбор канала из UI: запоминаем для этой сети и сразу применяем. */
    private fun selectChannelBlocking(secure: Boolean) {
        networkKind = detectNetworkKind()
        TelegramPrefs.setSecureFor(networkKind, secure)
        secureOverride = secure
        // Ручной выбор уважаем: автоподбор не перетирает его сразу,
        // но watchdog при долгом застое всё равно попробует второй канал.
        probeDone = true
        probeFinished = true
        probeSwitches = 0
        probeTried.clear()
        manualChannel = true
        AppLog.i(
            TAG,
            "Выбран канал ${channelLabelFor(secure)} для ${networkKindForLog()}"
        )
        if (TelegramRuntime.state.value.phase == TelegramPhase.RUNNING) {
            statsRunning = false
            restartProxy("ручной выбор канала", asStall = false)
        } else {
            TelegramRuntime.update {
                it.copy(
                    channel = channelLabelFor(secure),
                    secure = secure,
                    probing = false
                )
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
            probeSwitches = 0
            probeTried.clear()
            probeFinished = false
            manualChannel = false
            networkKind = detectNetworkKind()
        }
        val port = TelegramPrefs.port()
        val saved = TelegramPrefs.secureFor(networkKind)
        val secure = secureOverride ?: saved ?: TelegramPrefs.secureFallback()
        val cf = cfOverride ?: TelegramPrefs.cfEnabled()
        effectiveSecure = secure
        effectiveCf = cf
        /*
         * Проверка канала запускается на КАЖДОМ новом запуске, а не только
         * там, где канал для сети неизвестен. Иначе «запомненный» канал,
         * который у оператора перестал проходить, будет молча ждать две
         * минуты до watchdog. Если канал уже проверен в этом сеансе
         * (перезапуск watchdog'ом или переключение) — не проверяем заново.
         */
        probeDone = probeFinished
        val channel = channelLabelFor(secure, cf)

        TelegramRuntime.update {
            it.copy(
                phase = TelegramPhase.STARTING,
                port = port,
                secret = null,
                error = null,
                channel = channel,
                secure = secure,
                probing = !probeDone
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
                AppLog.i(
                    TAG,
                    "Запуск Telegram WS-прокси на 127.0.0.1:$port, канал: $channel" +
                        if (probeDone) "" else " (подбор канала)"
                )
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
                        channel = channel,
                        secure = secure,
                        probing = !probeDone
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
        probeSwitches = 0
        probeTried.clear()
        probeFinished = false
        probeDone = true
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
            it.copy(phase = TelegramPhase.ERROR, error = message, probing = false)
        }
        AppLog.e(TAG, "Telegram WS-прокси: $message")
        updateNotification(getString(R.string.telegram_error, message))
        NotificationHelper.notifyProblem(this, getString(R.string.telegram_error, message))
    }

    // -----------------------------------------------------------------------
    // Статистика, автоподбор канала и watchdog
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
            val startedAt = lastSampleAt
            var lastProgressAt = lastSampleAt
            var goodBytes = 0L
            var modeSaved = false

            while (statsRunning && generation == statsGeneration &&
                TelegramRuntime.state.value.phase == TelegramPhase.RUNNING
            ) {
                try {
                    // Пока идёт подбор канала, опрашиваем чаще: иначе мёртвый
                    // транспорт будет висеть до минуты.
                    Thread.sleep(if (probeDone) 15_000L else PROBE_SAMPLE_MS)
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
                val cf = statValue(stats, "cf")?.toLongOrNull() ?: 0L
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt

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
                            rememberChannel(effectiveSecure, "канал отдал данные")
                        }
                    }
                }

                // --- Автоподбор канала под новую сеть ---------------------
                if (!probeDone) {
                    val clientsWork = total >= 2 || active >= 1
                    val decision = when {
                        // Данные пошли — канал найден.
                        down >= PROBE_GOOD_BYTES -> "good"
                        // Клиенты есть, а ни одного CF-соединения не открылось.
                        clientsWork && cf <= 0L && elapsed >= PROBE_NO_CF_MS -> "dead"
                        // CF-соединения есть, но данные не идут.
                        clientsWork && cf > 0L && down < 1024L &&
                            elapsed >= PROBE_NO_DATA_MS -> "dead"
                        // Что-то уже капает — транспорт живой, дальше решает watchdog.
                        cf > 0L && down > 0L -> "live"
                        // Клиентов нет вовсе — проверять нечего, ждём Telegram.
                        total <= 0L && elapsed >= NO_CLIENTS_MS -> "idle"
                        else -> ""
                    }
                    when (decision) {
                        "idle" -> {
                            probeDone = true
                            probeFinished = true
                            TelegramRuntime.update { it.copy(probing = false) }
                            AppLog.i(
                                TAG,
                                "автоподбор: за ${elapsed / 1000}с ни одного клиента — " +
                                    "проверку канала откладываю"
                            )
                        }
                        "good" -> {
                            probeDone = true
                            probeFinished = true
                            secureOverride = null
                            rememberChannel(effectiveSecure, "автоподбор: канал рабочий")
                            TelegramRuntime.update { it.copy(probing = false) }
                            AppLog.i(
                                TAG,
                                "автоподбор: канал ${channelLabelFor(effectiveSecure)} рабочий " +
                                    "(cf=$cf, down=${humanBytes(down)})"
                            )
                        }

                        "live" -> {
                            probeDone = true
                            probeFinished = true
                            secureOverride = null
                            rememberChannel(effectiveSecure, "автоподбор: CF отвечает")
                            TelegramRuntime.update { it.copy(probing = false) }
                            AppLog.i(
                                TAG,
                                "автоподбор: канал ${channelLabelFor(effectiveSecure)} отвечает " +
                                    "(cf=$cf, down=${humanBytes(down)}), подбор закончен"
                            )
                        }

                        "dead" -> {
                            val alternative = !effectiveSecure
                            if (manualChannel) {
                                // Канал выбран человеком — не переключаем сами.
                                probeDone = true
                                probeFinished = true
                                TelegramRuntime.update { it.copy(probing = false) }
                                AppLog.w(
                                    TAG,
                                    "автоподбор: выбранный вручную канал " +
                                        "${channelLabelFor(effectiveSecure)} пока не отдаёт " +
                                        "данные (cf=$cf, down=${humanBytes(down)}) — " +
                                        "оставляю как выбрано"
                                )
                            } else if (!probeTried.contains(effectiveSecure) &&
                                probeSwitches < PROBE_MAX_SWITCHES
                            ) {
                                probeTried.add(effectiveSecure)
                                probeSwitches++
                                AppLog.w(
                                    TAG,
                                    "автоподбор: канал ${channelLabelFor(effectiveSecure)} " +
                                        "не работает (cf=$cf, down=${humanBytes(down)}, " +
                                        "total=$total) — пробую ${channelLabelFor(alternative)}"
                                )
                                statsRunning = false
                                applyChannel(alternative, "автоподбор канала")
                                return@thread
                            }
                            /*
                             * Оба транспорта не дали данных. Возвращаемся на
                             * канал, сохранённый для этой сети (он когда-то
                             * работал), и больше не дёргаем: дальше решает
                             * обычный watchdog.
                             */
                            val fallback = TelegramPrefs.secureFor(networkKind)
                                ?: TelegramPrefs.secureFallback()
                            probeDone = true
                            probeFinished = true
                            TelegramRuntime.update { it.copy(probing = false) }
                            if (fallback != effectiveSecure) {
                                AppLog.w(
                                    TAG,
                                    "автоподбор: оба канала не дали данных " +
                                        "(cf=$cf, down=${humanBytes(down)}) — " +
                                        "возвращаюсь на сохранённый " +
                                        channelLabelFor(fallback)
                                )
                                statsRunning = false
                                applyChannel(fallback, "автоподбор: возврат на сохранённый канал")
                                return@thread
                            }
                            AppLog.w(
                                TAG,
                                "автоподбор: оба канала не дали данных " +
                                    "(cf=$cf, down=${humanBytes(down)}) — оставляю " +
                                    channelLabelFor(effectiveSecure)
                            )
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

    /** Запоминает рабочий канал для текущего типа сети (Wi-Fi / мобильная). */
    private fun rememberChannel(secure: Boolean, reason: String) {
        if (TelegramPrefs.secureFor(networkKind) == secure) return
        TelegramPrefs.setSecureFor(networkKind, secure)
        AppLog.i(
            TAG,
            "$reason: ${channelLabelFor(secure)} запомнен для ${networkKindForLog()}"
        )
    }

    /** Переключение канала на лету (автоподбор или ручной выбор). */
    private fun applyChannel(secure: Boolean, reason: String) {
        secureOverride = secure
        TelegramRuntime.update { it.copy(probing = !probeDone) }
        AppLog.w(TAG, "$reason: включаю ${channelLabelFor(secure)}")
        NotificationHelper.notifyProblem(
            this,
            getString(R.string.telegram_channel_switched, channelLabelFor(secure))
        )
        restartProxy(reason, asStall = false)
    }

    /**
     * Перезапускает нативный прокси, не убивая службу.
     *
     * `asStall = true` — это реакция на застой: считаем попытки и со второй
     * подряд меняем транспорт Cloudflare (TLS 443 ↔ HTTP 80). `false` —
     * технический перезапуск (смена сети, автоподбор, ручной выбор канала),
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
             * Пробуем противоположный — у операторов и сетей по-разному:
             * где-то режут TLS по SNI, где-то HTTP на 80.
             */
            if (stallCount >= 2 && secureOverride == null) {
                val next = !effectiveSecure
                secureOverride = next
                rememberChannel(next, "watchdog: переключение транспорта")
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

    private fun channelLabelFor(secure: Boolean, cf: Boolean = effectiveCf): String = when {
        !cf -> getString(R.string.telegram_channel_direct)
        secure -> getString(R.string.telegram_channel_tls)
        else -> getString(R.string.telegram_channel_http)
    }

    private fun networkKindForLog(): String = when (networkKind) {
        TelegramPrefs.NET_WIFI -> "Wi-Fi"
        TelegramPrefs.NET_CELL -> "мобильная сеть"
        else -> "сеть"
    }

    private fun humanBytes(n: Long): String = when {
        n >= 1024 * 1024 -> "${n / (1024 * 1024)}MB"
        n >= 1024 -> "${n / 1024}KB"
        else -> "${n}B"
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
