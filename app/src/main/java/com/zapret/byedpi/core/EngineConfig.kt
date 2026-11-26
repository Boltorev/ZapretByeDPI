package com.zapret.byedpi.core

import java.io.File

/**
 * Параметры движка этапа 2.
 *
 * На этапе 3 сюда придёт PresetManager и Kotlin-ротатор, а строки аргументов
 * будут собираться из пресетов.
 */
object EngineConfig {
    const val SOCKS_HOST = "127.0.0.1"

    /**
     * Порт SOCKS5, на котором слушает ciadpi.
     *
     * Раньше это была константа 1080. Из-за неё падение предыдущего ciadpi
     * (или чужая копия приложения на том же порту) приводило к `code=255`
     * на каждом старте и бесконечному перезапуску.
     *
     * Теперь при старте VPN порт подбирается через [PortPicker] и
     * фиксируется на всю сессию: он одинаково используется в аргументах
     * ciadpi, в конфиге hev и во всех TLS-пробах. 1080 остаётся
     * предпочтительным, чтобы журнал и внешние инструкции не расходились
     * с реальностью.
     */
    @Volatile
    private var socksPort: Int = DEFAULT_SOCKS_PORT

    /** Порт SOCKS5 текущей сессии. */
    val SOCKS_PORT: Int get() = socksPort

    /** Предпочтительный порт: используется, если свободен. */
    const val DEFAULT_SOCKS_PORT = 1080

    /**
     * Фиксирует порт на сессию. Вызывать только из службы VPN
     * до запуска ciadpi/hev.
     */
    fun useSocksPort(port: Int) {
        socksPort = port
    }

    const val PRESET_LABEL = "SIMPLE"

    /** Сколько ждём, пока ciadpi откроет SOCKS-порт. */
    const val START_TIMEOUT_MS = 10_000L

    /**
     * Кандидаты стратегий.
     *
     * Порядок основан на логе с Xiaomi/Android 15 (ноябрь 2026): split/disorder
     * с позицией `1+s` не проходили YouTube, а стратегия рабочего десктопного
     * zapret2-easy — fake TLS + multidisorder. Поэтому сначала идут fake и
     * disorder по первому байту, а не по SNI:
     *
     *  - FAKE          — fake TLS CH (`-f -1:11`, SNI google/microsoft, TTL 8/3);
     *  - FAKE_MD5      — fake + TCP MD5 Signature (если ядро поддерживает);
     *  - MULTIDISORDER — `-d 1 -d 1+sm` (аналог multidisorder pos=1,midsld);
     *  - DISORDER1     — `-d 1` (оптимальный Linux-вариант из README ByeDPI);
     *  - OOB3          — `-o 3+s`;
     *  - SPLIT1        — `-s 1`;
     *  - TLSREC        — `-r 3+s`.
     *
     * Все опции — реальные опции ByeDPI 0.17.3. Никаких seqovl/hostfake.
     */
    data class Strategy(
        val name: String,
        val options: List<String>
    )

    /**
     * Порядок важен: сначала стратегии, которые не вставляют OOB в длинный
     * ClientHello googlevideo (OOB3 проходит API, но ломает медиа).
     */
    private val strategyOrder = listOf(
        "PER_HOST_YT",
        "PER_HOST_YT_TLSREC",
        "SPLIT1",
        "TLSREC",
        "FAKE",
        "FAKE_MD5",
        "MULTIDISORDER",
        "DISORDER1",
        "OOB3"
    )

    fun strategies(): List<Strategy> {
        val all = listOf(
        Strategy(
            name = "PER_HOST_YT",
            options = listOf(
                // Медиа YouTube: split по первому байту не вставляет OOB
                // в длинный ClientHello googlevideo.
                "-H", ":googlevideo.com",
                "-s", "1",
                "--auto=none",
                // API приложения: OOB3 уже проверен на этом операторе.
                "-H", ":youtubei.googleapis.com",
                "-o", "3+s",
                "--auto=none",
                // Страницы и статика YouTube.
                "-H", ":youtube.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":ytimg.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":ggpht.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":gstatic.com",
                "-o", "3+s",
                "--auto=none",
                // Discord.
                "-H", ":discord.com",
                "-s", "1",
                "--auto=none",
                "-H", ":discordapp.com",
                "-s", "1",
                "--auto=none",
                "-H", ":discord.gg",
                "-s", "1",
                "--auto=none",
                "-H", ":discord.media",
                "-s", "1",
                "--auto=none",
                // X / Twitter: split по первому байту; их DPI не пропускает
                // общий fallback с disorder по SNI.
                "-H", ":x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":api.x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":mobile.x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":api.twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":mobile.twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":t.co",
                "-s", "1",
                "--auto=none",
                "-H", ":twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":pbs.twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":video.twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":abs.twimg.com",
                "-s", "1",
                "--auto=none",
                // Всё остальное.
                "-s", "1+s",
                "-d", "1+s"
            )
        ),
        Strategy(
            name = "PER_HOST_YT_TLSREC",
            options = listOf(
                "-H", ":googlevideo.com",
                "-r", "3+s",
                "--auto=none",
                "-H", ":youtubei.googleapis.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":youtube.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":ytimg.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":ggpht.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":gstatic.com",
                "-o", "3+s",
                "--auto=none",
                "-H", ":discord.com",
                "-s", "1",
                "--auto=none",
                "-H", ":discordapp.com",
                "-s", "1",
                "--auto=none",
                "-H", ":discord.gg",
                "-s", "1",
                "--auto=none",
                "-H", ":discord.media",
                "-s", "1",
                "--auto=none",
                "-H", ":x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":api.x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":mobile.x.com",
                "-s", "1",
                "--auto=none",
                "-H", ":twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":api.twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":mobile.twitter.com",
                "-s", "1",
                "--auto=none",
                "-H", ":t.co",
                "-s", "1",
                "--auto=none",
                "-H", ":twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":pbs.twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":video.twimg.com",
                "-s", "1",
                "--auto=none",
                "-H", ":abs.twimg.com",
                "-s", "1",
                "--auto=none",
                "-s", "1+s",
                "-d", "1+s"
            )
        ),
        Strategy(
            name = "FAKE",
            options = listOf(
                "-f", "-1:11",
                "-t", "8",
                "-Q", "rand",
                "-n", "www.google.com",
                "-n", "www.microsoft.com",
                "--auto=torst",
                "-f", "-1:11",
                "-t", "3",
                "-Q", "rand",
                "-n", "www.google.com",
                "-n", "www.microsoft.com"
            )
        ),
        Strategy(
            name = "FAKE_MD5",
            options = listOf(
                "-f", "-1:11",
                "-t", "8",
                "-Q", "rand",
                "-n", "www.google.com",
                "-n", "www.microsoft.com",
                "-S",
                "--auto=torst",
                "-f", "-1:11",
                "-t", "3",
                "-Q", "rand",
                "-n", "www.google.com",
                "-n", "www.microsoft.com",
                "-S"
            )
        ),
        Strategy(
            name = "MULTIDISORDER",
            options = listOf(
                "-d", "1",
                "-d", "1+sm",
                "--auto=torst",
                "-d", "1",
                "-d", "1+sm"
            )
        ),
        Strategy(
            name = "DISORDER1",
            options = listOf(
                "-d", "1",
                "--auto=torst",
                "-d", "1"
            )
        ),
        Strategy(
            name = "OOB3",
            options = listOf(
                "-o", "3+s",
                "--auto=torst",
                "-o", "3+s"
            )
        ),
        Strategy(
            name = "SPLIT1",
            options = listOf(
                "-s", "1",
                "--auto=torst",
                "-s", "1"
            )
        ),
        Strategy(
            name = "TLSREC",
            options = listOf(
                "-r", "3+s",
                "--auto=torst",
                "-r", "3+s"
            )
        )
        )
        return strategyOrder.mapNotNull { name -> all.firstOrNull { it.name == name } }
    }

    /**
     * Варианты для независимой ротации по ключам.
     *
     * Меняем только нужную группу, не трогая остальные: отвал Discord
     * не должен менять стратегию YouTube и наоборот.
     */
    private val mediaVariants = listOf(
        listOf("-s", "1"),
        listOf("-r", "3+s"),
        listOf("-d", "1"),
        listOf("-f", "-1:11", "-t", "8", "-Q", "rand",
            "-n", "www.google.com", "-n", "www.microsoft.com")
    )

    private val apiVariants = listOf(
        listOf("-o", "3+s"),
        listOf("-s", "1"),
        listOf("-r", "3+s"),
        listOf("-d", "1")
    )

    private val discordVariants = listOf(
        listOf("-s", "1"),
        listOf("-o", "3+s"),
        listOf("-r", "3+s"),
        listOf("-d", "1")
    )

    /**
     * X/Twitter. `-s 1` оказался недостаточным: соединение устанавливается,
     * но ответа нет. Перебираем OOB/TLS-record/disorder-варианты, пока
     * canary `api.x.com` не начнёт проходить TLS.
     *
     * Порядок переставлен после логов 0.6.2: fake (`-f -1:11 -t 8`) на
     * `api.x.com` проходит рукопожатие (он за Cloudflare), а на медиа-хостах
     * отдаёт только серверный полёт и закрывается. Поэтому fake — последним.
     */
    private val xVariants = listOf(
        listOf("-o", "3+s"),
        listOf("-r", "3+s"),
        listOf("-d", "1"),
        listOf("-s", "1"),
        listOf("-s", "1+s", "-d", "1+s"),
        listOf("-f", "-1:11", "-t", "8", "-Q", "rand",
            "-n", "www.google.com", "-n", "www.microsoft.com")
    )

    /**
     * Медиа X (картинки, видео, аватарки). Отдельный набор вариантов:
     * в логах 0.6.2 именно эти хосты отдавали ~530 байт и закрывались,
     * хотя API при этом «работал». Ротируются независимо от API X.
     */
    private val xMediaVariants = listOf(
        listOf("-o", "3+s"),
        listOf("-s", "1"),
        listOf("-r", "3+s"),
        listOf("-d", "1"),
        listOf("-f", "-1:11", "-t", "8", "-Q", "rand",
            "-n", "www.google.com", "-n", "www.microsoft.com")
    )

    /** API и веб X/Twitter. */
    private val xApiHosts = listOf(
        "x.com",
        "api.x.com",
        "mobile.x.com",
        "twitter.com",
        "api.twitter.com",
        "mobile.twitter.com",
        "t.co"
    )

    /** Медиа X: картинки, видео, статика. */
    private val xMediaHosts = listOf(
        "twimg.com",
        "pbs.twimg.com",
        "video.twimg.com",
        "abs.twimg.com"
    )

    val mediaVariantCount: Int get() = mediaVariants.size
    val apiVariantCount: Int get() = apiVariants.size
    val discordVariantCount: Int get() = discordVariants.size
    val xVariantCount: Int get() = xVariants.size
    val xMediaVariantCount: Int get() = xMediaVariants.size

    /**
     * Собирает per-host стратегию с выбранными вариантами групп.
     * Индексы берутся по модулю, чтобы ротатор не вылетал за границы.
     */
    fun hostRoutedStrategy(
        mediaIndex: Int = 0,
        apiIndex: Int = 0,
        discordIndex: Int = 0,
        xIndex: Int = 0,
        xMediaIndex: Int = 0,
        userHosts: List<String> = emptyList()
    ): Strategy {
        val media = mediaVariants[mediaIndex.mod(mediaVariants.size)]
        val api = apiVariants[apiIndex.mod(apiVariants.size)]
        val discord = discordVariants[discordIndex.mod(discordVariants.size)]
        val x = xVariants[xIndex.mod(xVariants.size)]
        val xm = xMediaVariants[xMediaIndex.mod(xMediaVariants.size)]

        val options = mutableListOf<String>()
        fun host(name: String, opts: List<String>) {
            options += "-H"
            options += ":$name"
            options += opts
            options += "--auto=none"
        }

        host("googlevideo.com", media)
        host("youtubei.googleapis.com", api)
        host("youtube.com", api)
        host("ytimg.com", api)
        host("ggpht.com", api)
        host("gstatic.com", api)
        host("discord.com", discord)
        host("discordapp.com", discord)
        host("discord.gg", discord)
        host("discord.media", discord)

        for (name in xApiHosts) {
            host(name, x)
        }
        for (name in xMediaHosts) {
            host(name, xm)
        }

        // Пользовательские домены: отдельная группа перед общим fallback.
        if (userHosts.isNotEmpty()) {
            options += "-H"
            options += ":" + userHosts.joinToString(" ")
            options += listOf("-s", "1")
            options += "--auto=none"
        }

        options += listOf("-s", "1+s", "-d", "1+s")

        return Strategy("PER_HOST_YT", options)
    }

    /**
     * Запасная стратегия, если ни один кандидат не прошёл HTTPS-пробу.
     * Она уже проверена на устройстве: часть Google-сервисов через неё
     * работает, хоть YouTube и блокируется.
     */
    fun fallbackStrategy(): Strategy = Strategy(
        name = "SIMPLE",
        options = listOf(
            "-s", "1+s",
            "-d", "1+s",
            "--auto=torst",
            "-s", "1+s",
            "-d", "1+s"
        )
    )

    /**
     * Полная командная строка ciadpi.
     *
     * -i/-p ограничивают SOCKS-порт локальной петлёй.
     * -x 1 включает подробный лог (он читается в журнал приложения).
     * --protect-path добавляется только если protect-сервер прошёл self-test.
     */
    fun byedpiArgs(
        strategy: Strategy,
        protectPath: String?,
        quicFakePath: String? = null
    ): List<String> = buildList {
        add("-i")
        add(SOCKS_HOST)
        add("-p")
        add(SOCKS_PORT.toString())
        add("-x")
        add("1")
        if (protectPath != null) {
            add("--protect-path")
            add(protectPath)
        }
        if (!VpnConfig.ENABLE_IPV6) {
            // Android-туннель только IPv4. Иначе ciadpi резолвит AAAA и уходит
            // напрямую по IPv6, где YouTube может отдавать 480p/рвать медиа.
            add("-X")
        }
        // Только 443: не трогаем DoT 853, mtalk 5228 и DNS. Иначе desync
        // случайно ломает Private DNS, который Android гонит через туннель.
        add("-V")
        add("443")

        if (quicFakePath != null && strategy.name.startsWith("PER_HOST_YT")) {
            /*
             * Отдельная UDP-only группа: QUIC Initial fake.
             * -K u не даёт этой группе перехватывать TCP.
             * -a 11 — 11 поддельных QUIC Initial перед настоящим.
             * -l — реальный QUIC Initial (assets/quic_initial.bin).
             * Без этого YouTube отдаёт страницу, но видео висит на QUIC.
             */
            add("-K")
            add("u")
            add("-a")
            add("11")
            add("-l")
            add(quicFakePath)
            add("--auto=none")
            add("-V")
            add("443")
        }

        for (option in strategy.options) {
            add(option)
            if (option.startsWith("--auto=")) {
                // --auto создаёт новую группу; порт-фильтр не наследуется.
                add("-V")
                add("443")
            }
        }
    }

    fun argsPreview(
        strategy: Strategy,
        protectPath: String? = null,
        quicFakePath: String? = null
    ): String =
        byedpiArgs(strategy, protectPath, quicFakePath).joinToString(" ")

    /**
     * YAML-конфиг hev-socks5-tunnel.
     *
     * tun-fd не указывается: fd передаётся отдельным аргументом JNI.
     * mtu совпадает с VpnConfig.MTU. log-file пишется в filesDir,
     * чтобы при сбое можно было посмотреть причину.
     */
    fun tun2SocksConfig(filesDir: File): String {
        val logPath = File(filesDir, "hev.log").absolutePath
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${VpnConfig.MTU}")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  connect-timeout: 8000")
            appendLine("  read-write-timeout: 60000")
            appendLine("  tcp-read-write-timeout: 90000")
            appendLine("  udp-read-write-timeout: 30000")
            appendLine("  max-session-count: 512")
            appendLine("  log-level: info")
            appendLine("  log-file: $logPath")
            appendLine("socks5:")
            appendLine("  address: $SOCKS_HOST")
            appendLine("  port: $SOCKS_PORT")
            appendLine("  udp: udp")
        }
    }
}
