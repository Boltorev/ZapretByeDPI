package com.zapret.byedpi.service

import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.nativebridge.HevBridge
import kotlin.concurrent.thread

/**
 * Обёртка над hev-socks5-tunnel (tun2socks).
 *
 * JNI-вызов HevBridge.run() блокируется до остановки, поэтому держим его
 * в отдельном потоке. Остановка — HevBridge.stop().
 */
class Tun2Socks {
    companion object {
        private const val TAG = "hev"
        private const val STOP_TIMEOUT_MS = 2_000L
    }

    private var thread: Thread? = null
    private var statsThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopping = false

    /** Вызывается, если hev завершился сам, пока VPN работал. */
    var onStopped: ((Int) -> Unit)? = null

    val isRunning: Boolean get() = running

    fun start(tunFd: Int, config: String) {
        if (!HevBridge.isAvailable) {
            throw EngineException(
                "hev-socks5-tunnel не загрузился: ${HevBridge.loadError}. " +
                    "Проверьте ABI APK и наличие libhev-socks5-tunnel.so."
            )
        }

        stopping = false
        running = true
        thread = thread(name = "hev-tun2socks", isDaemon = true) {
            val code = try {
                HevBridge.run(config, tunFd)
            } catch (t: Throwable) {
                AppLog.e(TAG, "hev run() упал: ${t.message}", t)
                -100
            }
            running = false
            if (!stopping) {
                AppLog.w(TAG, "hev завершился сам, code=$code")
                onStopped?.invoke(code)
            }
        }
        AppLog.i(TAG, "tun2socks запущен (fd=$tunFd)")

        // Раз в 5 секунд пишем статистику TUN. По ней видно, на каком слое
        // затык: tx=0 — приложения не доходят до TUN; tx>0, rx=0 — hev/ciadpi
        // не возвращают ответ.
        statsThread = thread(name = "hev-stats", isDaemon = true) {
            while (!stopping) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopping) break
                val stats = HevBridge.statsOrNull() ?: continue
                if (stats.size >= 4) {
                    AppLog.i(
                        "Stats",
                        "TUN tx_packets=${stats[0]} tx_bytes=${stats[1]} " +
                            "rx_packets=${stats[2]} rx_bytes=${stats[3]}"
                    )
                }
            }
        }
    }

    fun stop() {
        stopping = true
        if (running) {
            HevBridge.stopSafely()
        }
        try {
            thread?.join(STOP_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread?.isAlive == true) {
            AppLog.w(TAG, "Поток hev не завершился за ${STOP_TIMEOUT_MS} мс")
        }
        thread = null
        statsThread = null
        running = false
        AppLog.i(TAG, "tun2socks остановлен")
    }

    fun statsOrNull(): LongArray? = HevBridge.statsOrNull()
}
