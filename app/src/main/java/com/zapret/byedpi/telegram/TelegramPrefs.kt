package com.zapret.byedpi.telegram

import com.zapret.byedpi.core.Prefs
import java.security.SecureRandom

/** Настройки Telegram WS-прокси. */
object TelegramPrefs {
    private const val KEY_SECRET = "tg_secret"
    private const val KEY_PORT = "tg_port"
    private const val KEY_CF = "tg_cf_enabled"

    /**
     * Транспорт до Cloudflare: TLS на 443 или «голый» HTTP на 80.
     *
     * В библиотеке есть вызов SetDisableSecure(), который переключает
     * ВСЁ, что она открывает (CF-прокси, воркер, TCP-fallback на DC),
     * на порт 80 без TLS. Значение по умолчанию в библиотеке — порт 80,
     * но у Cloudflare этот путь сейчас мёртв: TCP-соединение есть,
     * а ответ на HTTP-запрос не приходит (проверено 28.11.2026 на
     * всех 20 доменах из cfproxy-domains.txt — на 443 handshake 101,
     * на 80 тишина). Поэтому по умолчанию включаем TLS/443, как в
     * оригинальном tg-ws-proxy (disable_secure=False), и оставляем
     * возможность вернуться на 80, если у оператора режут TLS.
     */
    private const val KEY_SECURE = "tg_secure_443"

    const val DEFAULT_PORT = 1443

    fun port(): Int {
        val value = Prefs.getString(KEY_PORT, DEFAULT_PORT.toString()).toIntOrNull()
        return (value ?: DEFAULT_PORT).coerceIn(1024, 65535)
    }

    fun cfEnabled(): Boolean = Prefs.getString(KEY_CF, "1") == "1"

    fun setCfEnabled(enabled: Boolean) {
        Prefs.putString(KEY_CF, if (enabled) "1" else "0")
    }

    /** true — TLS на 443 (по умолчанию), false — HTTP на 80. */
    fun secure(): Boolean = Prefs.getString(KEY_SECURE, "1") == "1"

    fun setSecure(enabled: Boolean) {
        Prefs.putString(KEY_SECURE, if (enabled) "1" else "0")
    }

    /** 32 hex-символа: 16 байт MTProto-секрета. */
    fun secret(): String {
        val existing = Prefs.getString(KEY_SECRET, "")
        if (existing.length == 32 && existing.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return existing
        }
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        Prefs.putString(KEY_SECRET, value)
        return value
    }
}
