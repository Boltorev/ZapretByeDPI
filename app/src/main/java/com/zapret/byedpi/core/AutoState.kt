package com.zapret.byedpi.core

/**
 * Состояние AUTO-ротации для одного ключа (`yt`, `dc`, ...).
 *
 * Хранится как строка `index,fails,lastFailAt` в SharedPreferences:
 *   autostate.yt = 0,0,0
 *   autostate.dc = 1,2,179...
 */
data class AutoState(
    val index: Int = 0,
    val fails: Int = 0,
    val lastFailAt: Long = 0L
)

object AutoStateStore {
    private const val PREFIX = "autostate."

    fun load(key: String): AutoState {
        val raw = Prefs.getString(PREFIX + key, "")
        if (raw.isBlank()) return AutoState()
        val parts = raw.split(',')
        if (parts.size != 3) return AutoState()
        return AutoState(
            index = parts[0].toIntOrNull() ?: 0,
            fails = parts[1].toIntOrNull() ?: 0,
            lastFailAt = parts[2].toLongOrNull() ?: 0L
        )
    }

    fun save(key: String, state: AutoState) {
        Prefs.putString(
            PREFIX + key,
            "${state.index},${state.fails},${state.lastFailAt}"
        )
    }

    fun resetFails(key: String) {
        val state = load(key)
        if (state.fails != 0 || state.lastFailAt != 0L) {
            save(key, state.copy(fails = 0, lastFailAt = 0L))
        }
    }
}
