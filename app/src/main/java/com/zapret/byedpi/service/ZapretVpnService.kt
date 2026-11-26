package com.zapret.byedpi.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.zapret.byedpi.core.AppMode
import com.zapret.byedpi.core.AppRules
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.AutoRotator
import com.zapret.byedpi.core.AutoState
import com.zapret.byedpi.core.AutoStateStore
import com.zapret.byedpi.core.DomainStore
import com.zapret.byedpi.core.DnsResolver
import com.zapret.byedpi.core.EngineConfig
import com.zapret.byedpi.core.NetworkDiagnostics
import com.zapret.byedpi.core.PortPicker
import com.zapret.byedpi.core.ProbeFailure
import com.zapret.byedpi.core.ProbeResult
import com.zapret.byedpi.core.VpnConfig
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.core.VpnState
import com.zapret.byedpi.util.NotificationHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN-служба этапа 2.
 *
 * Схема:
 *
 *   приложения → TUN (VpnService)
 *              → libhev-socks5-tunnel.so (tun2socks, JNI)
 *              → SOCKS5 127.0.0.1:1080
 *              → libbyedpi.so (ciadpi как отдельный процесс)
 *              → интернет
 *
 * Само приложение исключено из VPN через addDisallowedApplication(),
 * поэтому его сокеты и сокеты дочернего ciadpi не заворачиваются в TUN.
 * Для ciadpi дополнительно работает --protect-path.
 *
 * Запуск/остановка движка выполняются в отдельном однопоточном executor,
 * чтобы не блокировать main thread (ожидание SOCKS-порта, остановка процесса).
 */
class ZapretVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.zapret.byedpi.action.START"
        const val ACTION_STOP = "com.zapret.byedpi.action.STOP"

        private const val TAG = "VpnService"
        private const val PROTECT_SOCKET_NAME = "ciadpi-protect.sock"

        /** Сколько раз пробуем establish(): null бывает при гонке с прошлой службой. */
        private const val ESTABLISH_ATTEMPTS = 2

        /** Пауза между попытками establish(). */
        private const val ESTABLISH_RETRY_DELAY_MS = 700L

        /**
         * Бюджет времени на весь перебор стратегий при старте.
         * Дальше стартуем fallback: лучше быстро получить рабочий туннель
         * и дать AutoRotator подобрать вариант, чем минутами показывать
         * «Подключение…».
         */
        private const val SCAN_BUDGET_MS = 40_000L

        /** Таймаут одной TLS-пробы во время перебора (2 адреса на хост). */
        private const val SELECT_PROBE_TIMEOUT_MS = 3_000
    }

    private var tunnel: ParcelFileDescriptor? = null

    @Volatile
    private var protectServer: ProtectServer? = null

    @Volatile
    private var byeDpi: ByeDpiProcess? = null

    @Volatile
    private var tun2Socks: Tun2Socks? = null

    @Volatile
    private var starting = false

    @Volatile
    private var stopping = false

    @Volatile
    private var activeStrategy: EngineConfig.Strategy? = null

    @Volatile
    private var autoRotator: AutoRotator? = null

    @Volatile
    private var rotateInProgress = false

    @Volatile
    private var ytApiIndex = 0

    @Volatile
    private var ytMediaIndex = 0

    @Volatile
    private var discordIndex = 0

    @Volatile
    private var xIndex = 0

    /** Вариант для медиа X (pbs/video/abs.twimg.com), ротируется отдельно. */
    @Volatile
    private var xMediaIndex = 0

    @Volatile
    private var userHosts: List<String> = emptyList()

    private val stopEnqueued = AtomicBoolean(false)

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastNetworkId: String? = null

    @Volatile
    private var networkRestartScheduled = false

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vpn-engine").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        registerNetworkMonitor()
        AppLog.i(TAG, "Служба VPN создана")
    }

    private fun registerNetworkMonitor() {
        try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = manager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val id = network.toString()
                    val previous = lastNetworkId
                    lastNetworkId = id
                    if (previous == null) {
                        AppLog.i(TAG, "Сеть доступна: $id")
                        return
                    }
                    if (previous == id) return
                    AppLog.w(TAG, "Смена сети: $previous -> $id")
                    DnsResolver.clear()
                    scheduleEngineRestartForNetwork()
                }

                override fun onLost(network: Network) {
                    AppLog.w(TAG, "Сеть потеряна: $network")
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

    private fun scheduleEngineRestartForNetwork() {
        if (networkRestartScheduled) return
        val strategy = activeStrategy ?: return
        if (VpnRuntime.state.value.phase != VpnPhase.RUNNING) return
        networkRestartScheduled = true
        try {
            engineExecutor.execute {
                try {
                    Thread.sleep(800L)
                    if (stopping) return@execute
                    AppLog.i(TAG, "Пересоздаём ciadpi после смены сети")
                    if (!restartByeDpi(strategy)) {
                        AppLog.e(TAG, "После смены сети ciadpi не поднялся")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    networkRestartScheduled = false
                }
            }
        } catch (e: RejectedExecutionException) {
            networkRestartScheduled = false
            AppLog.w(TAG, "Перезапуск после смены сети отклонён: ${e.message}")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop("команда STOP", keepError = false)
                return START_NOT_STICKY
            }

            ACTION_START, null -> startTunnel()

            else -> {
                AppLog.w(TAG, "Неизвестное действие: ${intent.action}")
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // -----------------------------------------------------------------------
    // Запуск
    // -----------------------------------------------------------------------

    private fun startTunnel() {
        if (starting || stopping || tunnel != null || tun2Socks != null) {
            AppLog.d(TAG, "Запуск уже идёт или туннель активен — пропускаем")
            return
        }

        starting = true
        stopping = false
        stopEnqueued.set(false)
        DnsResolver.clear()

        VpnRuntime.update {
            it.copy(
                phase = VpnPhase.STARTING,
                presetName = EngineConfig.PRESET_LABEL,
                engineLabel = null,
                rotationCount = 0,
                tunName = null,
                startedAt = null,
                errorMessage = null
            )
        }
        AppLog.i(
            TAG,
            "Старт. Кандидаты стратегий: " +
                EngineConfig.strategies().joinToString { it.name }
        )

        if (!startForegroundOrFail()) {
            starting = false
            return
        }

        engineExecutor.execute { startEngineBlocking() }
    }

    private fun startEngineBlocking() {
        try {
            if (stopping) return

            // 0. Порт SOCKS5 и уборка прошлых процессов.
            //
            // Раньше порт был константой 1080: если предыдущий ciadpi не
            // успел умереть (или работала другая копия приложения), новый
            // падал с code=255 на каждом кандидате, и это выглядело как
            // «не подбирается стратегия».
            prepareSocksPort()

            // 1. protect-сервер должен быть готов раньше ciadpi.
            val protectPath = File(filesDir, PROTECT_SOCKET_NAME).absolutePath
            val protector = ProtectServer(this, protectPath)
            val protectStarted = protector.start()
            val protectReady = protectStarted && protector.verify()
            if (protectReady) {
                protectServer = protector
                AppLog.i(TAG, "protect-сервер готов (self-test OK)")
            } else {
                protector.close()
                protectServer = null
                AppLog.w(
                    TAG,
                    "protect-сервер не прошёл self-test; " +
                        "ciadpi пойдёт без --protect-path (защита по UID)"
                )
            }

            if (stopping) return

            // 2. Подбираем стратегию по реальному TLS через SOCKS.
            //    Пока VPN не поднят, проба идёт напрямую и не зависит от TUN.
            val protectArg = if (protectReady) protectPath else null
            val quicFakePath = prepareQuicFakeAsset()
            ytApiIndex = AutoStateStore.load("yt").index
            discordIndex = AutoStateStore.load("dc").index
            xIndex = AutoStateStore.load("x").index
            xMediaIndex = AutoStateStore.load("xm").index
            userHosts = DomainStore.load(this)
            AppLog.i(TAG, "Пользовательских доменов: ${userHosts.size}")
            val candidates = EngineConfig.strategies().map { strategy ->
                when (strategy.name) {
                    "PER_HOST_YT" -> EngineConfig.hostRoutedStrategy(
                        mediaIndex = 0,
                        apiIndex = 0,
                        discordIndex = 0,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                    "PER_HOST_YT_TLSREC" -> EngineConfig.hostRoutedStrategy(
                        mediaIndex = 1,
                        apiIndex = 0,
                        discordIndex = 0,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                    else -> strategy
                }
            }
            var chosen: EngineConfig.Strategy? = null
            var networkFailure: ProbeResult? = null

            val diagnostics = NetworkDiagnostics.check(this)
            AppLog.i(
                TAG,
                "Сеть: ${diagnostics.network}, DNS: ${diagnostics.dns}, маршрут: ${diagnostics.route}"
            )
            if (!diagnostics.ok) {
                AppLog.w(TAG, "Сеть/DNS не готовы, повторная проверка через 1.5 с")
                Thread.sleep(1_500L)
                val retry = NetworkDiagnostics.check(this)
                AppLog.i(
                    TAG,
                    "Повторная проверка сети: ${retry.network}, DNS=${retry.dns}, route=${retry.route}"
                )
                if (!retry.ok) {
                    throw EngineException(
                        "Сеть/DNS недоступны (${retry.network}, DNS=${retry.dns}, " +
                            "route=${retry.route}). Перебор стратегий не запускаю."
                    )
                }
            }

            /*
             * Перебор ограничен по времени. Раньше при жёстко заблокированном
             * Google каждый кандидат тратил ~45 с (8 IP × 5 с на один хост),
             * и 9 кандидатов давали ~7 минут «бесконечного запуска»
             * (лог 0.6.3, cellular). Теперь проба берёт 2 адреса по 3 с,
             * а весь перебор укладывается в SCAN_BUDGET_MS; если ничего
             * не нашлось — стартуем fallback и даём работать AutoRotator.
             */
            val scanStartedAt = System.currentTimeMillis()
            var candidateNo = 0
            var scanTimedOut = false
            for (strategy in candidates) {
                if (stopping) return
                candidateNo++
                if (System.currentTimeMillis() - scanStartedAt > SCAN_BUDGET_MS) {
                    scanTimedOut = true
                    AppLog.w(
                        TAG,
                        "Перебор остановлен по времени (${SCAN_BUDGET_MS / 1000} с, " +
                            "проверено $candidateNo из ${candidates.size}). " +
                            "Пробую fallback, дальше подхватит AutoRotator."
                    )
                    break
                }

                val proxy = ByeDpiProcess(this)
                proxy.onUnexpectedExit = { code ->
                    onEngineFailed("ciadpi завершился сам (code=$code)")
                }
                // Прогресс в карточке состояния: видно, что перебор идёт,
                // а не «зависло на подключении».
                VpnRuntime.update {
                    it.copy(presetName = "Проверка $candidateNo/${candidates.size}")
                }

                try {
                    AppLog.i(
                        TAG,
                        "Пробуем $candidateNo/${candidates.size} ${strategy.name}: " +
                            EngineConfig.argsPreview(strategy, protectArg, quicFakePath)
                    )
                    proxy.start(EngineConfig.byedpiArgs(strategy, protectArg, quicFakePath))
                    byeDpi = proxy

                    if (!proxy.awaitSocksReady(4_000)) {
                        AppLog.w(
                            TAG,
                            "${strategy.name}: SOCKS-порт не открылся — ${proxy.failureSummary()}"
                        )
                        proxy.stop()
                        byeDpi = null
                        continue
                    }

                    /*
                     * Проверяем два хоста:
                     *  - redirector.googlevideo.com — медиа-цепочка YouTube;
                     *  - youtubei.googleapis.com   — API приложения.
                     *
                     * Раньше проверялся только API, поэтому OOB3 выбирался,
                     * хотя video-хосты googlevideo через него не открывались
                     * (в логе: OOB split, затем recv=0).
                     *
                     * PER_HOST_YT выбирается по API: для медиа у него
                     * отдельная группа SPLIT1 + QUIC fake.
                     */
                    val mediaResult = proxy.probeTlsResult(
                        "redirector.googlevideo.com", 443, SELECT_PROBE_TIMEOUT_MS
                    )
                    val perHost = strategy.name.startsWith("PER_HOST_YT")
                    val apiResult = if (perHost || mediaResult.ok) {
                        proxy.probeTlsResult(
                            "youtubei.googleapis.com", 443, SELECT_PROBE_TIMEOUT_MS
                        )
                    } else {
                        ProbeResult.fail(ProbeFailure.TIMEOUT, "media failed")
                    }

                    if (mediaResult.isNetworkProblem || apiResult.isNetworkProblem) {
                        val failure = if (mediaResult.isNetworkProblem) mediaResult else apiResult
                        networkFailure = failure
                        AppLog.e(
                            TAG,
                            "${strategy.name}: ${failure.failure} — это сеть/DNS, " +
                                "а не стратегия. Перебор остановлен: ${failure.detail}"
                        )
                        proxy.stop()
                        byeDpi = null
                        break
                    }

                    val mediaOk = mediaResult.ok
                    val apiOk = apiResult.ok
                    val youtubeOk = if (perHost) apiOk else (mediaOk && apiOk)
                    AppLog.i(
                        TAG,
                        "${strategy.name}: TLS media=$mediaOk api=$apiOk " +
                            "perHost=$perHost -> $youtubeOk " +
                            "(media=${mediaResult.failure}, api=${apiResult.failure})"
                    )

                    if (youtubeOk) {
                        chosen = strategy
                        break
                    }

                    proxy.stop()
                    byeDpi = null
                } catch (e: Throwable) {
                    AppLog.w(TAG, "${strategy.name}: ошибка запуска/пробы: ${e.message}")
                    try {
                        proxy.stop()
                    } catch (_: Throwable) {
                    }
                    byeDpi = null
                }
            }

            if (chosen == null && networkFailure != null) {
                throw EngineException(
                    "Нет DNS/маршрута (${networkFailure.failure}): " +
                        "${networkFailure.detail}. Проверьте интернет и DNS; " +
                        "перебор стратегий остановлен, чтобы не тратить время."
                )
            }

            if (chosen == null) {
                // Ни одна новая стратегия не прошла YouTube. Запускаем
                // проверенный fallback, чтобы не оставить пользователя
                // вообще без туннеля.
                val fallback = EngineConfig.fallbackStrategy()
                AppLog.w(
                    TAG,
                    "Ни одна стратегия не прошла TLS-пробу YouTube" +
                        (if (scanTimedOut) " (перебор прерван по таймауту)" else "") +
                        ". Запускаем ${fallback.name} как fallback, " +
                        "дальше подбор продолжит AutoRotator."
                )
                val proxy = ByeDpiProcess(this)
                proxy.onUnexpectedExit = { code ->
                    onEngineFailed("ciadpi завершился сам (code=$code)")
                }
                proxy.start(EngineConfig.byedpiArgs(fallback, protectArg, quicFakePath))
                if (!proxy.awaitSocksReady(5_000)) {
                    val summary = proxy.failureSummary()
                    try {
                        proxy.stop()
                    } catch (_: Throwable) {
                    }
                    throw EngineException(
                        "ciadpi не открыл SOCKS-порт " +
                            "${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT}: $summary"
                    )
                }
                chosen = fallback
                byeDpi = proxy
            }

            val selected = chosen ?: throw EngineException("Не выбрана стратегия")
            activeStrategy = selected
            AppLog.i(
                TAG,
                "Выбрана стратегия ${selected.name}: " +
                    EngineConfig.argsPreview(selected, protectArg, quicFakePath)
            )

            if (stopping) return

            // 3. Создаём TUN. Весь трафик, кроме нашего UID, идёт в него.
            //    При null — одна повторная попытка и понятная ошибка.
            val pfd = establishTunnel()
            tunnel = pfd
            AppLog.i(TAG, "TUN создан, fd=${pfd.fd}")

            if (stopping) return

            // 4. Запускаем tun2socks.
            val socks = Tun2Socks()
            socks.onStopped = { code ->
                onEngineFailed("hev завершился сам (code=$code)")
            }
            socks.start(pfd.fd, EngineConfig.tun2SocksConfig(filesDir))
            tun2Socks = socks

            // Даём hev шанс сразу сообщить об ошибке (например, кривой конфиг).
            Thread.sleep(200)
            if (!socks.isRunning) {
                throw EngineException("hev не запустился. Смотрите hev.log и Logcat (ZapretHev).")
            }

            if (stopping) return

            VpnRuntime.update {
                it.copy(
                    phase = VpnPhase.RUNNING,
                    presetName = selected.name,
                    engineLabel = "ciadpi + hev",
                    tunName = VpnConfig.TUN_NAME,
                    startedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
            updateNotification()
            AppLog.i(TAG, "VPN работает: TUN → hev → SOCKS5 → ciadpi (${selected.name})")
            startAutoRotator()
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            AppLog.e(TAG, "Не удалось запустить движок: $message", t)
            VpnRuntime.update {
                it.copy(
                    phase = VpnPhase.ERROR,
                    errorMessage = message,
                    engineLabel = null,
                    tunName = null,
                    startedAt = null
                )
            }
            stopping = true
            enqueueStop(keepError = true)
        } finally {
            starting = false
        }
    }

    /**
     * Готовит SOCKS-порт на сессию: убирает зависшие ciadpi и выбирает
     * свободный порт (1080, если он свободен).
     *
     * Порт фиксируется в [EngineConfig] до запуска ciadpi, поэтому
     * аргументы `-p`, конфиг hev и все TLS-пробы используют одно значение.
     */
    private fun prepareSocksPort() {
        try {
            val stale = StaleProcesses.killAll(ByeDpiProcess.BINARY_NAME)
            if (stale > 0) {
                AppLog.w(TAG, "Перед стартом завершено зависших ciadpi: $stale")
            }
        } catch (t: Throwable) {
            // Не критично: если /proc недоступен, просто стартуем как раньше.
            AppLog.w(TAG, "Поиск зависших ciadpi не удался: ${t.message}")
        }

        val port = PortPicker.pickSocksPort(
            EngineConfig.SOCKS_HOST,
            EngineConfig.DEFAULT_SOCKS_PORT
        )
        if (port <= 0) {
            throw EngineException(
                "Не удалось подобрать свободный локальный порт для SOCKS5. " +
                    "Закройте другие копии приложения и попробуйте снова."
            )
        }

        if (port != EngineConfig.DEFAULT_SOCKS_PORT) {
            AppLog.w(
                TAG,
                "Порт ${EngineConfig.DEFAULT_SOCKS_PORT} занят — использую $port " +
                    "(ciadpi, hev и пробы переключены на него)"
            )
        }
        EngineConfig.useSocksPort(port)
        AppLog.i(TAG, "SOCKS5: ${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT}")
    }

    private fun startAutoRotator() {
        autoRotator?.stop()
        autoRotator = AutoRotator(
            check = { host ->
                val proxy = byeDpi
                if (proxy == null) {
                    ProbeResult.fail(ProbeFailure.IO, "ciadpi не запущен")
                } else if (isXHost(host)) {
                    /*
                     * X проверяем данными, а не рукопожатием: в логах 0.6.2
                     * и api.x.com, и медиа отдавали только серверный полёт
                     * TLS (~340-530 байт) и закрывались, но startHandshake()
                     * при этом проходил и ротации не было.
                     */
                    proxy.probeHttpsResult(host, 443, 6_000)
                } else {
                    val timeout = if (host.endsWith("x.com")) 3_000 else 6_000
                    proxy.probeTlsResult(host, 443, timeout)
                }
            },
            onRotate = { key ->
                try {
                    engineExecutor.execute { rotateStrategy(key) }
                } catch (e: RejectedExecutionException) {
                    AppLog.w(TAG, "Ротатор: executor остановлен: ${e.message}")
                }
            }
        ).also { it.start() }
    }

    private fun stopAutoRotator() {
        autoRotator?.stop()
        autoRotator = null
    }

    /**
     * Хосты X/Twitter, для которых канарейка проверяет реальные данные
     * (HTTP-ответ), а не только TLS-рукопожатие.
     */
    private fun isXHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower.endsWith("x.com") ||
            lower.endsWith("twitter.com") ||
            lower.endsWith("t.co") ||
            lower.endsWith("twimg.com")
    }

    /**
     * Перезапускает только ciadpi, не трогая TUN/hev.
     *
     * Это позволяет сменить стратегию без пересоздания VPN: hev остаётся
     * на месте, приложения переподключаются к новому SOCKS-порту.
     */
    private fun restartByeDpi(
        strategy: EngineConfig.Strategy,
        stopExisting: Boolean = true
    ): Boolean {
        if (stopping) return false

        if (stopExisting) {
            try {
                byeDpi?.stop()
            } catch (t: Throwable) {
                AppLog.w(TAG, "Ротация: ошибка остановки ciadpi: ${t.message}")
            }
            byeDpi = null
            // Если destroyForcibly не успел, процесс мог остаться и держать
            // SOCKS-порт. Тогда следующий ciadpi упал бы с code=255.
            try {
                StaleProcesses.killAll(ByeDpiProcess.BINARY_NAME)
            } catch (t: Throwable) {
                AppLog.w(TAG, "Ротация: уборка зависших ciadpi не удалась: ${t.message}")
            }
        }

        val protectArg = if (protectServer != null) {
            File(filesDir, PROTECT_SOCKET_NAME).absolutePath
        } else {
            null
        }
        val quicFake = File(filesDir, "quic_initial.bin")
            .takeIf { it.isFile }
            ?.absolutePath

        val proxy = ByeDpiProcess(this)
        proxy.onUnexpectedExit = { code ->
            onEngineFailed("ciadpi завершился сам (code=$code)")
        }

        return try {
            proxy.start(EngineConfig.byedpiArgs(strategy, protectArg, quicFake))
            if (!proxy.awaitSocksReady(6_000)) {
                AppLog.w(
                    TAG,
                    "Ротация: ${strategy.name} не открыл SOCKS-порт — ${proxy.failureSummary()}"
                )
                proxy.stop()
                false
            } else {
                byeDpi = proxy
                AppLog.i(TAG, "Ротация: ciadpi перезапущен со стратегией ${strategy.name}")
                true
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "Ротация: ошибка запуска ${strategy.name}: ${t.message}", t)
            try {
                proxy.stop()
            } catch (_: Throwable) {
            }
            false
        }
    }

    private fun rotateStrategy(key: String) {
        if (stopping || rotateInProgress) return
        if (VpnRuntime.state.value.phase != VpnPhase.RUNNING) return

        rotateInProgress = true
        try {
            val current = activeStrategy ?: return

            val next: EngineConfig.Strategy
            val nextIndex: Int
            when (key) {
                "yt" -> {
                    ytApiIndex = (ytApiIndex + 1) % EngineConfig.apiVariantCount
                    nextIndex = ytApiIndex
                    next = EngineConfig.hostRoutedStrategy(
                        mediaIndex = ytMediaIndex,
                        apiIndex = ytApiIndex,
                        discordIndex = discordIndex,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                }
                "dc" -> {
                    discordIndex = (discordIndex + 1) % EngineConfig.discordVariantCount
                    nextIndex = discordIndex
                    next = EngineConfig.hostRoutedStrategy(
                        mediaIndex = ytMediaIndex,
                        apiIndex = ytApiIndex,
                        discordIndex = discordIndex,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                }
                "x" -> {
                    xIndex = (xIndex + 1) % EngineConfig.xVariantCount
                    nextIndex = xIndex
                    next = EngineConfig.hostRoutedStrategy(
                        mediaIndex = ytMediaIndex,
                        apiIndex = ytApiIndex,
                        discordIndex = discordIndex,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                }
                "xm" -> {
                    xMediaIndex = (xMediaIndex + 1) % EngineConfig.xMediaVariantCount
                    nextIndex = xMediaIndex
                    next = EngineConfig.hostRoutedStrategy(
                        mediaIndex = ytMediaIndex,
                        apiIndex = ytApiIndex,
                        discordIndex = discordIndex,
                        xIndex = xIndex,
                        xMediaIndex = xMediaIndex,
                        userHosts = userHosts
                    )
                }
                else -> return
            }

            AppLog.w(
                TAG,
                "AUTO $key: ${current.name} -> index=$nextIndex " +
                    "(yt api=$ytApiIndex, dc=$discordIndex, x=$xIndex, xm=$xMediaIndex)"
            )

            if (restartByeDpi(next)) {
                activeStrategy = next
                AutoStateStore.save(
                    key,
                    AutoState(index = nextIndex, fails = 0, lastFailAt = System.currentTimeMillis())
                )
                VpnRuntime.update {
                    it.copy(
                        presetName = next.name,
                        rotationCount = it.rotationCount + 1
                    )
                }
                updateNotification()
                AppLog.i(TAG, "AUTO $key: переключено на вариант $nextIndex")
            } else {
                AppLog.e(TAG, "AUTO $key: вариант $nextIndex не запустился, возвращаем текущий")
                if (restartByeDpi(current, stopExisting = false)) {
                    activeStrategy = current
                    VpnRuntime.update { it.copy(presetName = current.name) }
                    updateNotification()
                } else {
                    onEngineFailed("AUTO: не удалось поднять ни новый, ни текущий вариант")
                }
            }
        } finally {
            rotateInProgress = false
        }
    }

    /**
     * Копирует QUIC Initial из assets в filesDir: ciadpi принимает -l только
     * путь к файлу. Это нужно для UDP-only группы PER_HOST_YT.
     */
    private fun prepareQuicFakeAsset(): String? {
        return try {
            val out = File(filesDir, "quic_initial.bin")
            assets.open("quic_initial.bin").use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (out.length() > 0) {
                AppLog.i(TAG, "QUIC fake готов: ${out.length()} байт")
                out.absolutePath
            } else {
                AppLog.w(TAG, "QUIC fake пустой; UDP-группа отключена")
                null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "QUIC fake недоступен: ${e.message}")
            null
        }
    }

    private fun establishTunnel(): ParcelFileDescriptor {
        val foreignVpn = activeVpnTransports()
        if (foreignVpn.isNotEmpty()) {
            AppLog.w(
                TAG,
                "Активен другой VPN-транспорт: ${foreignVpn.joinToString()}. " +
                    "Он может помешать establish()"
            )
        }

        for (attempt in 1..ESTABLISH_ATTEMPTS) {
            if (stopping) throw EngineException("Создание TUN прервано остановкой VPN")

            val pfd = createTunnel()
            if (pfd != null) {
                if (attempt > 1) {
                    AppLog.i(TAG, "establish() удался с попытки $attempt")
                }
                return pfd
            }

            AppLog.w(TAG, "establish() вернул null (попытка $attempt из $ESTABLISH_ATTEMPTS)")
            if (attempt < ESTABLISH_ATTEMPTS) {
                try {
                    Thread.sleep(ESTABLISH_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        throw EngineException(establishFailureMessage(foreignVpn))
    }

    /**
     * Создаёт новый Builder и пытается поднять TUN.
     *
     * Builder создаётся заново на каждую попытку: повторный establish()
     * на том же объекте после null не гарантирован, а повторное добавление
     * маршрутов на том же Builder не даёт предсказуемого результата.
     */
    private fun createTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(VpnConfig.SESSION_NAME)
            .setMtu(VpnConfig.MTU)
            .addAddress(VpnConfig.ADDRESS_V4, VpnConfig.PREFIX_V4)
            .addRoute("0.0.0.0", 0)

        if (VpnConfig.ENABLE_IPV6) {
            builder.addAddress(VpnConfig.ADDRESS_V6, VpnConfig.PREFIX_V6)
            builder.addRoute("::", 0)
        }

        builder.addDnsServer(VpnConfig.DNS_PRIMARY)
        builder.addDnsServer(VpnConfig.DNS_SECONDARY)
        builder.addDnsServer(VpnConfig.DNS_TERTIARY)
        builder.addDnsServer(VpnConfig.DNS_QUATERNARY)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // addDisallowedApplication(packageName) — критично: без него исходящие
        // сокеты hev и ciadpi попадут в собственный TUN и получится петля.
        applyPerAppRules(builder)

        return builder.establish()
    }

    /**
     * Активные VPN-транспорты, кроме нашего туннеля. Вызывается до
     * establish(), поэтому в списке — только чужие VPN или зависший
     * туннель предыдущего запуска этого же приложения.
     */
    private fun activeVpnTransports(): List<String> {
        val manager = connectivityManager ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            val networks = manager.allNetworks
            networks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                    ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return@mapNotNull null
                }
                network.toString()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Не удалось перечислить VPN-сети: ${t.message}")
            emptyList()
        }
    }

    /**
     * Объясняет пользователю, что именно помешало поднять TUN.
     *
     * Раньше в этой ситуации был только текст «establish() вернул null»,
     * по которому нельзя понять, что делать.
     */
    private fun establishFailureMessage(foreignVpn: List<String>): String {
        val permissionLost = try {
            // null означает, что разрешение уже выдано (или не требуется).
            VpnService.prepare(this) != null
        } catch (_: Throwable) {
            false
        }

        return buildString {
            append("Не удалось поднять VPN: establish() вернул null ")
            append("($ESTABLISH_ATTEMPTS попытки). ")
            when {
                permissionLost -> append(
                    "У приложения нет разрешения VPN — оно отозвано или сброшено. " +
                        "Откройте ZapretByeDPI и нажмите «Подключить», чтобы выдать его заново."
                )
                foreignVpn.isNotEmpty() -> append(
                    "Сейчас активен другой VPN: ${foreignVpn.joinToString()}. " +
                        "Закройте его (или отключите Always-on VPN) и нажмите «Подключить» снова."
                )
                else -> append(
                    "Возможные причины: другой активный VPN, зависший туннель прошлого " +
                        "запуска или отозванное разрешение. Закройте другой VPN и нажмите " +
                        "«Подключить» ещё раз; если не помогает — перезагрузите телефон."
                )
            }
        }
    }

    /**
     * Per-App VPN:
     *   ALL    — все приложения, само приложение исключено;
     *   ONLY   — только выбранные (само приложение не входит);
     *   EXCEPT — все, кроме выбранных; само приложение исключено.
     *
     * Если после фильтрации не осталось ни одного пакета, откатываемся
     * в ALL: пустой allowed-list в Android означает «все приложения»,
     * и мы получили бы петлю hev → TUN.
     */
    private fun applyPerAppRules(builder: Builder) {
        val mode = AppRules.mode()
        val selected = AppRules.packages().filter { it != packageName }

        when (mode) {
            AppMode.ALL -> {
                excludeSelf(builder)
                AppLog.i(TAG, "Per-App: все приложения (кроме самого ZapretByeDPI)")
            }

            AppMode.ONLY -> {
                if (selected.isEmpty()) {
                    AppLog.w(TAG, "Per-App ONLY: список пуст, откат в ALL")
                    excludeSelf(builder)
                } else {
                    var added = 0
                    for (pkg in selected) {
                        try {
                            builder.addAllowedApplication(pkg)
                            added++
                        } catch (e: PackageManager.NameNotFoundException) {
                            AppLog.w(TAG, "Per-App: нет пакета $pkg")
                        }
                    }
                    if (added == 0) {
                        AppLog.w(TAG, "Per-App ONLY: ни один пакет не добавлен, откат в ALL")
                        excludeSelf(builder)
                    } else {
                        // В ONLY-режиме само приложение уже не входит в allowed-list.
                        // addDisallowedApplication() здесь нельзя: Android запрещает
                        // смешивать allowed и disallowed (в логе было
                        // "addAllowedApplication already called").
                        AppLog.i(TAG, "Per-App ONLY: включено пакетов $added")
                    }
                }
            }

            AppMode.EXCEPT -> {
                excludeSelf(builder)
                var added = 0
                for (pkg in selected) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        added++
                    } catch (e: PackageManager.NameNotFoundException) {
                        AppLog.w(TAG, "Per-App: нет пакета $pkg")
                    }
                }
                AppLog.i(TAG, "Per-App EXCEPT: исключено пакетов $added")
            }
        }
    }

    private fun excludeSelf(builder: Builder) {
        try {
            builder.addDisallowedApplication(packageName)
            AppLog.i(TAG, "Своё приложение исключено из VPN (защита от петли)")
        } catch (e: PackageManager.NameNotFoundException) {
            // Без этого hev и ciadpi могут уйти в собственный TUN.
            AppLog.e(TAG, "Не удалось исключить себя из VPN: ${e.message}")
            throw EngineException(
                "Не удалось исключить приложение из VPN: ${e.message}. " +
                    "Запуск отменён, чтобы не получить петлю."
            )
        }
    }

    // -----------------------------------------------------------------------
    // Остановка
    // -----------------------------------------------------------------------

    private fun requestStop(reason: String, keepError: Boolean) {
        stopping = true
        if (!keepError) {
            VpnRuntime.update {
                if (it.phase == VpnPhase.ERROR) it else it.copy(phase = VpnPhase.STOPPING)
            }
        }
        AppLog.i(TAG, "Остановка VPN: $reason")
        enqueueStop(keepError)
    }

    private fun enqueueStop(keepError: Boolean) {
        if (!stopEnqueued.compareAndSet(false, true)) return
        try {
            engineExecutor.execute { stopEngineBlocking(keepError) }
        } catch (e: RejectedExecutionException) {
            AppLog.w(TAG, "Executor уже остановлен: ${e.message}")
        }
    }

    private fun onEngineFailed(message: String) {
        if (stopping) return
        AppLog.e(TAG, message)
        VpnRuntime.update {
            it.copy(
                phase = VpnPhase.ERROR,
                errorMessage = message,
                engineLabel = null,
                tunName = null,
                startedAt = null
            )
        }
        requestStop("движок завершился", keepError = true)
    }

    @Synchronized
    private fun stopEngineBlocking(keepError: Boolean) {
        AppLog.i(TAG, "Останавливаем движок…")
        stopAutoRotator()
        activeStrategy = null

        try {
            tun2Socks?.stop()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Ошибка остановки hev: ${t.message}")
        }
        tun2Socks = null

        try {
            byeDpi?.stop()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Ошибка остановки ciadpi: ${t.message}")
        }
        byeDpi = null

        try {
            protectServer?.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Ошибка остановки protect-сервера: ${t.message}")
        }
        protectServer = null

        val fd = tunnel
        tunnel = null
        try {
            fd?.close()
        } catch (e: IOException) {
            AppLog.w(TAG, "Ошибка закрытия TUN: ${e.message}")
        }

        if (keepError) {
            VpnRuntime.update {
                it.copy(engineLabel = null, tunName = null, startedAt = null)
            }
        } else {
            VpnRuntime.update {
                VpnState(
                    phase = VpnPhase.STOPPED,
                    presetName = EngineConfig.PRESET_LABEL
                )
            }
        }

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
        AppLog.i(TAG, "VPN остановлен")
    }

    /**
     * Система вызывает onRevoke(), когда пользователь отключил VPN,
     * запустил другой VPN или включил always-on для другого приложения.
     */
    override fun onRevoke() {
        AppLog.w(TAG, "Система отозвала VPN-разрешение")
        requestStop("onRevoke", keepError = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping = true
        unregisterNetworkMonitor()
        stopAutoRotator()
        activeStrategy = null
        engineExecutor.shutdownNow()

        try {
            tun2Socks?.stop()
        } catch (_: Throwable) {
        }
        tun2Socks = null
        try {
            byeDpi?.stop()
        } catch (_: Throwable) {
        }
        byeDpi = null
        try {
            protectServer?.close()
        } catch (_: Throwable) {
        }
        protectServer = null

        val fd = tunnel
        tunnel = null
        try {
            fd?.close()
        } catch (_: IOException) {
        }

        val phase = VpnRuntime.state.value.phase
        if (phase == VpnPhase.RUNNING ||
            phase == VpnPhase.STARTING ||
            phase == VpnPhase.STOPPING
        ) {
            VpnRuntime.update {
                it.copy(phase = VpnPhase.STOPPED, engineLabel = null, tunName = null, startedAt = null)
            }
        }

        AppLog.i(TAG, "Служба VPN уничтожена")
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Foreground-служба и уведомление
    // -----------------------------------------------------------------------

    private fun startForegroundOrFail(): Boolean {
        return try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, VpnRuntime.state.value),
                serviceType
            )
            true
        } catch (e: Exception) {
            val message = "startForeground() не удался: ${e.message}"
            AppLog.e(TAG, message, e)
            VpnRuntime.update {
                it.copy(phase = VpnPhase.ERROR, errorMessage = message, startedAt = null)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            false
        }
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, VpnRuntime.state.value)
            )
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Не удалось обновить уведомление: ${e.message}")
        }
    }
}
