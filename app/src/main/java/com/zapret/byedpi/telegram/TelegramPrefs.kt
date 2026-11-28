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
     * на порт 80 без TLS. По умолчанию в ней именно порт 80, поэтому
     * транспорт всегда выставляется явно.
     *
     * Рабочий транспорт зависит от сети: на мобильном интернете проходит
     * TLS 443, а на домашнем Wi-Fi — HTTP 80 (проверено на логах 0.6.5
     * и 0.6.6: там на 443 `ws tls fail … timeout`, а на 80 `CF ok`).
     * Поэтому выбор запоминается отдельно для Wi-Fi и для мобильной сети,
     * а при первой встрече с сетью подбирается автоматически.
     */
    private const val KEY_SECURE_SUFFIX = "tg_secure_443"

    /** Ключ «последний выбранный вручную канал» — для фолбэка, пока сеть неизвестна. */
    private const val KEY_SECURE_LAST = "tg_secure_last"

    const val DEFAULT_PORT = 1443

    /** Вид сети для запоминания канала. */
    const val NET_WIFI = "wifi"
    const val NET_CELL = "cell"
    const val NET_OTHER = "other"

    fun port(): Int {
        val value = Prefs.getString(KEY_PORT, DEFAULT_PORT.toString()).toIntOrNull()
        return (value ?: DEFAULT_PORT).coerceIn(1024, 65535)
    }

    fun cfEnabled(): Boolean = Prefs.getString(KEY_CF, "1") == "1"

    fun setCfEnabled(enabled: Boolean) {
        Prefs.putString(KEY_CF, if (enabled) "1" else "0")
    }

    private fun secureKey(kind: String) = "${KEY_SECURE_SUFFIX}_$kind"

    /**
     * Сохранённый транспорт для этой сети: true — TLS 443, false — HTTP 80,
     * null — ещё не проверяли (тогда работает автоподбор).
     */
    fun secureFor(kind: String): Boolean? {
        val raw = Prefs.getString(secureKey(kind), "")
        return when (raw) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun setSecureFor(kind: String, enabled: Boolean) {
        Prefs.putString(secureKey(kind), if (enabled) "1" else "0")
        Prefs.putString(KEY_SECURE_LAST, if (enabled) "1" else "0")
    }

    /** Транспорт для сети, где ещё не проверяли: последний удачный или TLS. */
    fun secureFallback(): Boolean = Prefs.getString(KEY_SECURE_LAST, "1") == "1"

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
