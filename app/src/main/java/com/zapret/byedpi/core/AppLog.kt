package com.zapret.byedpi.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Уровни журнала. Совпадают с будущими фильтрами на экране логов (этап 4).
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Одна запись журнала.
 *
 * @param timestamp время события в миллисекундах (System.currentTimeMillis()).
 * @param level     уровень важности.
 * @param tag       короткий тег источника, например "VpnService".
 * @param message   текст события.
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    /**
     * Строка для показа в TextView. Формат фиксированный, чтобы журнал
     * выглядел ровно в моноширинном шрифте:
     *   14:03:21 INF VpnService: TUN создан
     */
    fun toDisplayLine(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        val levelLabel = when (level) {
            LogLevel.DEBUG -> "DBG"
            LogLevel.INFO -> "INF"
            LogLevel.WARN -> "WRN"
            LogLevel.ERROR -> "ERR"
        }
        return "$time $levelLabel $tag: $message"
    }
}

/**
 * Глобальный журнал приложения.
 *
 * Зачем свой журнал, а не только Logcat:
 *  - события видны прямо в приложении, без adb;
 *  - на этапе 3 сюда будут попадать сообщения ротатора
 *    ("circular: rotate strategy to N");
 *  - на этапе 4 экран логов получит фильтры по уровню.
 *
 * StateFlow выбран специально: UI подписывается на изменения и сам
 * перерисовывается, а запись можно делать из любого потока.
 */
object AppLog {
    private const val MAX_ENTRIES = 500
    private const val LOGCAT_PREFIX = "Zapret"

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = add(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = add(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        add(LogLevel.ERROR, tag, message, throwable)

    /** Полностью очищает журнал (кнопка «Очистить» на главном экране). */
    fun clear() {
        _entries.value = emptyList()
    }

    private fun add(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        // takeLast не даёт журналу расти бесконечно.
        _entries.update { current -> (current + entry).takeLast(MAX_ENTRIES) }

        // Дублируем в Logcat для отладки через adb.
        // Android ограничивает тег 23 символами на старых версиях — подстрахуемся.
        val logcatTag = "$LOGCAT_PREFIX/$tag".take(23)
        when (level) {
            LogLevel.DEBUG -> Log.d(logcatTag, message, throwable)
            LogLevel.INFO -> Log.i(logcatTag, message, throwable)
            LogLevel.WARN -> Log.w(logcatTag, message, throwable)
            LogLevel.ERROR -> Log.e(logcatTag, message, throwable)
        }
    }
}
