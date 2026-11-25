package com.zapret.byedpi.telegram

import com.zapret.byedpi.core.Prefs
import java.security.SecureRandom

/** Настройки Telegram WS-прокси. */
object TelegramPrefs {
    private const val KEY_SECRET = "tg_secret"
    private const val KEY_PORT = "tg_port"
    private const val KEY_CF = "tg_cf_enabled"

    const val DEFAULT_PORT = 1443

    fun port(): Int {
        val value = Prefs.getString(KEY_PORT, DEFAULT_PORT.toString()).toIntOrNull()
        return (value ?: DEFAULT_PORT).coerceIn(1024, 65535)
    }

    fun cfEnabled(): Boolean = Prefs.getString(KEY_CF, "1") == "1"

    fun setCfEnabled(enabled: Boolean) {
        Prefs.putString(KEY_CF, if (enabled) "1" else "0")
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
