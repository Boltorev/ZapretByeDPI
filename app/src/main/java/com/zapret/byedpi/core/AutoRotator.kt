package com.zapret.byedpi.core

import kotlin.concurrent.thread

/**
 * Канарейка этапа 3.
 *
 * Раз в 45 секунд проверяет два ключа через локальный SOCKS5:
 *   yt — youtubei.googleapis.com (API YouTube);
 *   dc — discord.com.
 *
 * Если 4 проверки подряд (в окне 45 секунд) не прошли, вызывается onRotate.
 * Состояние пишется в autostate.yt / autostate.dc.
 *
 * Это упрощённый аналог circular из zapret2 и SiteCheckUtils из ByeByeDPI:
 * проверяем реальный HTTP-ответ через ciadpi, а не только открытие порта.
 */
class AutoRotator(
    private val check: (String) -> ProbeResult,
    private val onRotate: (String) -> Unit
) {
    companion object {
        const val FAILS_THRESHOLD = 4
        const val WINDOW_MS = 60_000L

        // 10 секунд, чтобы несколько отказов реально укладывались в окно.
        const val INTERVAL_MS = 10_000L

        private data class Check(val key: String, val host: String, val threshold: Int)

        private val checks = listOf(
            // X перебираем быстрее: там 5 вариантов, и пользователь ждёт.
            Check("x", "api.x.com", 2),
            Check("yt", "youtubei.googleapis.com", FAILS_THRESHOLD),
            Check("dc", "discord.com", FAILS_THRESHOLD)
        )

        /**
         * nld=2: ключ по последним двум меткам домена, но YouTube/Discord
         * сводим в отдельные ключи, чтобы их ротация не пересекалась.
         */
        fun keyForHost(host: String): String {
            val lower = host.lowercase()
            return when {
                lower.endsWith("youtube.com") ||
                    lower.endsWith("googlevideo.com") ||
                    lower.endsWith("ytimg.com") ||
                    lower.endsWith("ggpht.com") -> "yt"
                lower.endsWith("discord.com") ||
                    lower.endsWith("discordapp.com") ||
                    lower.endsWith("discord.gg") ||
                    lower.endsWith("discord.media") -> "dc"
                lower.endsWith("x.com") ||
                    lower.endsWith("twitter.com") ||
                    lower.endsWith("t.co") ||
                    lower.endsWith("twimg.com") -> "x"
                else -> lower.split('.').takeLast(2).joinToString(".")
            }
        }
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "auto-rotator", isDaemon = true) {
            AppLog.i("Auto", "канарейка запущена: fails=$FAILS_THRESHOLD, time=${WINDOW_MS / 1000}s")
            while (running) {
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break

                for (item in checks) {
                    if (!running) break
                    val key = item.key
                    val host = item.host
                    val threshold = item.threshold
                    val result = try {
                        check(host)
                    } catch (t: Throwable) {
                        ProbeResult.fail(ProbeFailure.IO, "${t.javaClass.simpleName}: ${t.message}")
                    }

                    if (result.ok) {
                        AutoStateStore.resetFails(key)
                        AppLog.d("Auto", "$key: $host OK")
                    } else if (result.isNetworkProblem) {
                        // Это не ошибка стратегии. Менять split/disorder бессмысленно,
                        // пока не появится DNS/маршрут.
                        AppLog.w(
                            "Auto",
                            "$key: $host ${result.failure} — сеть/DNS, ротация пропущена (${result.detail})"
                        )
                    } else {
                        val state = AutoStateStore.load(key)
                        val now = System.currentTimeMillis()
                        val fails = if (
                            state.lastFailAt == 0L ||
                            now - state.lastFailAt > WINDOW_MS
                        ) {
                            1
                        } else {
                            state.fails + 1
                        }
                        AutoStateStore.save(
                            key,
                            state.copy(fails = fails, lastFailAt = now)
                        )
                        AppLog.w("Auto", "$key: $host FAIL $fails/$threshold")

                        if (fails >= threshold) {
                            AutoStateStore.save(
                                key,
                                state.copy(fails = 0, lastFailAt = now)
                            )
                            AppLog.w("Auto", "$key: порог $threshold — ротация")
                            try {
                                onRotate(key)
                            } catch (t: Throwable) {
                                AppLog.e("Auto", "ротация $key не запустилась: ${t.message}", t)
                            }
                        }
                    }
                }
            }
            AppLog.i("Auto", "канарейка остановлена")
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
