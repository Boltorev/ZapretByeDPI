package com.zapret.byedpi.core

import android.content.Context
import com.zapret.byedpi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Проверка новых версий на GitHub.
 *
 * Зачем: приложение распространяется APK-файлами, магазина нет, поэтому
 * пользователь иначе никогда не узнает, что вышло обновление.
 *
 * Как работает:
 *  - запрашивает последний релиз через GitHub API;
 *  - сравнивает тег (`v0.6.5` → `0.6.5`) с текущей версией;
 *  - результат кладёт в [info], чтобы главный экран показал баннер;
 *  - кэширует тег и время проверки в prefs, чтобы не дёргать API при каждом
 *    запуске и показывать баннер даже без сети.
 *
 * Запрос идёт раз в 12 часов, с таймаутом 6 секунд; любая ошибка сети
 * молча игнорируется — проверка обновлений не должна мешать работе.
 */
object UpdateChecker {
    private const val TAG = "Update"
    private const val API_URL =
        "https://api.github.com/repos/Boltorev/ZapretByeDPI/releases/latest"
    const val RELEASES_URL =
        "https://github.com/Boltorev/ZapretByeDPI/releases/latest"

    private const val PREF_LATEST = "update.latest"
    private const val PREF_CHECKED = "update.checkedAt"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 6_000

    /** Что показать на главном экране. */
    data class Info(
        val current: String,
        val latest: String?,
        val updateAvailable: Boolean
    )

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private val _info = MutableStateFlow(
        Info(current = BuildConfig.VERSION_NAME, latest = null, updateAvailable = false)
    )
    val info: StateFlow<Info> = _info.asStateFlow()

    @Volatile
    private var fetching = false

    /**
     * Показывает закэшированный результат и, если он устарел, обновляет его
     * в фоне. Вызывается при старте экрана и при возврате в приложение.
     */
    fun refresh(context: Context, force: Boolean = false) {
        publish(Prefs.getString(PREF_LATEST, ""))

        val now = System.currentTimeMillis()
        val checkedAt = Prefs.getString(PREF_CHECKED, "0").toLongOrNull() ?: 0L
        if (!force && now - checkedAt < CHECK_INTERVAL_MS) return
        if (fetching) return
        fetching = true

        thread(name = "update-check", isDaemon = true) {
            try {
                val tag = JSONObject(httpGet(API_URL)).optString("tag_name").ifBlank { null }
                if (tag != null) {
                    Prefs.putString(PREF_LATEST, tag)
                    Prefs.putString(PREF_CHECKED, now.toString())
                    publish(tag)
                    AppLog.d(TAG, "на GitHub последняя версия $tag, текущая $currentVersion")
                }
            } catch (t: Throwable) {
                // Нет сети, GitHub недоступен, лимит API — не наша проблема.
                AppLog.d(TAG, "проверка обновлений не удалась: ${t.message}")
            } finally {
                fetching = false
            }
        }
    }

    private fun publish(tag: String?) {
        val latest = tag
            ?.trim()
            ?.removePrefix("v")
            ?.substringBefore('-')
            ?.takeIf { it.isNotEmpty() }
        _info.value = Info(
            current = currentVersion,
            latest = latest,
            updateAvailable = latest != null && isNewer(latest, currentVersion)
        )
    }

    /** true, если [candidate] новее [current] (сравнение по числам 1.2.3). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "ZapretByeDPI/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
