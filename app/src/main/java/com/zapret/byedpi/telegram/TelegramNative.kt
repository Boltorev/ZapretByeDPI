package com.zapret.byedpi.telegram

import android.os.Build
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.zapret.byedpi.core.AppLog

/**
 * C-экспорты libtgwsproxy.so (Telegram WS proxy, GPLv3).
 *
 * Библиотека собрана из amurcanov/tg-ws-proxy-android; в APK лежат
 * libtgwsproxy.so для arm64-v8a и armeabi-v7a. На x86/x86_64 её нет,
 * поэтому модуль Telegram на эмуляторе недоступен (остальной VPN работает).
 */
internal interface TelegramProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int

    fun StopProxy(): Int

    fun SetPoolSize(size: Int)

    fun SetBufferSizeKb(kb: Int)

    fun SetCfProxyCacheDir(cacheDir: String)

    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)

    fun SetCfWorkerDomains(domains: String)

    fun SetFakeTlsDomain(domain: String)

    fun SetDisableSecure(v: Int)

    fun SetForceTestDc(v: Int)

    fun SetProxyProtocol(v: Int)

    fun GetSecretWithPrefix(): Pointer?

    fun GetStats(): Pointer?

    fun FreeString(p: Pointer)
}

object TelegramNative {

    private const val TAG = "TelegramNative"

    private val loaded: TelegramProxyLibrary? by lazy {
        try {
            Native.load("tgwsproxy", TelegramProxyLibrary::class.java).also {
                AppLog.i(TAG, "libtgwsproxy загружена")
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "libtgwsproxy недоступна: ${t.message}", t)
            null
        }
    }

    val available: Boolean get() = loaded != null

    val loadError: String?
        get() = if (loaded == null) {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            "libtgwsproxy.so не загрузилась для ABI $abi: библиотека собрана " +
                "только для arm64-v8a и armeabi-v7a, поэтому Telegram-прокси " +
                "на этой архитектуре недоступен (VPN при этом работает)"
        } else {
            null
        }

    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Boolean): Int {
        val lib = loaded ?: return -100
        return lib.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0)
    }

    fun stopProxy(): Int = loaded?.StopProxy() ?: -100

    /**
     * Транспорт до Cloudflare и дата-центров.
     *
     * `disableSecure = true` — порт 80 без TLS, `false` — TLS на 443.
     * Библиотека по умолчанию сидит на порту 80, но у Cloudflare этот
     * путь сейчас не отвечает (см. TelegramPrefs.KEY_SECURE). Вызывать
     * обязательно до StartProxy: настройка читается при каждом коннекте.
     */
    fun setDisableSecure(disableSecure: Boolean) {
        val lib = loaded ?: return
        try {
            lib.SetDisableSecure(if (disableSecure) 1 else 0)
        } catch (t: Throwable) {
            AppLog.w(TAG, "SetDisableSecure: ${t.message}")
        }
    }

    fun configurePool(poolSize: Int, cacheDir: String, cfEnabled: Boolean) {
        val lib = loaded ?: return
        try {
            lib.SetPoolSize(poolSize.coerceIn(0, 16))
            lib.SetCfProxyCacheDir(cacheDir)
            lib.SetCfProxyConfig(if (cfEnabled) 1 else 0, 1, "")
        } catch (t: Throwable) {
            AppLog.w(TAG, "configurePool: ${t.message}")
        }
    }

    fun secretWithPrefix(): String? {
        val lib = loaded ?: return null
        val ptr = lib.GetSecretWithPrefix() ?: return null
        return try {
            ptr.getString(0)
        } finally {
            lib.FreeString(ptr)
        }
    }

    fun stats(): String? {
        val lib = loaded ?: return null
        val ptr = lib.GetStats() ?: return null
        return try {
            ptr.getString(0)
        } finally {
            lib.FreeString(ptr)
        }
    }
}
