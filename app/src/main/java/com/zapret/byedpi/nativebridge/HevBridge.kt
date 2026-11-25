package com.zapret.byedpi.nativebridge

import android.util.Log
import com.zapret.byedpi.core.AppLog

/**
 * JNI-мост к hev-socks5-tunnel.
 *
 * Родные методы реализованы в app/src/main/jni/hevbridge/hev_bridge.c.
 * Загрузка библиотек выполняется один раз; если что-то не так (например,
 * APK собран без нужной ABI), приложение не падает, а показывает ошибку.
 */
object HevBridge {
    private const val TAG = "HevBridge"

    private var loadFailure: Throwable? = null

    private val loaded: Boolean = try {
        // hevbridge зависит от libhev-socks5-tunnel.so через DT_NEEDED,
        // но грузим обе явно, чтобы ошибка была понятнее.
        System.loadLibrary("hev-socks5-tunnel")
        System.loadLibrary("hevbridge")
        true
    } catch (t: Throwable) {
        loadFailure = t
        Log.e(TAG, "Не удалось загрузить нативные библиотеки hev", t)
        false
    }

    val isAvailable: Boolean get() = loaded

    val loadError: String
        get() = loadFailure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown"

    /** Блокируется, пока tun2socks работает. Вызывать только из отдельного потока. */
    external fun run(config: String, fd: Int): Int

    /** Просит hev завершить цикл. */
    external fun stop()

    /** [tx_packets, tx_bytes, rx_packets, rx_bytes]. */
    external fun stats(): LongArray

    fun statsOrNull(): LongArray? {
        if (!loaded) return null
        return try {
            stats()
        } catch (t: Throwable) {
            AppLog.w(TAG, "stats() failed: ${t.message}")
            null
        }
    }

    fun stopSafely() {
        if (!loaded) return
        try {
            stop()
        } catch (t: Throwable) {
            AppLog.w(TAG, "stop() failed: ${t.message}")
        }
    }
}
