package com.zapret.byedpi.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Единая точка доступа к настройкам приложения (SharedPreferences).
 *
 * На этапе 1 хранится только выбранный пресет. На следующих этапах сюда
 * добавятся:
 *  - режим Per-App VPN (все / только выбранные / все кроме выбранных);
 *  - набор выбранных пакетов;
 *  - пользовательский список доменов;
 *  - флаги IPSet/Game Filter и порты.
 *
 * init() вызывается один раз из ZapretApp.onCreate(), поэтому остальной код
 * может обращаться к Prefs без передачи Context.
 */
object Prefs {
    private const val FILE_NAME = "zapret_prefs"
    private const val KEY_PRESET = "preset_name"
    private const val DEFAULT_PRESET = "AUTO"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Имя текущего пресета. Пока это просто строка: на этапе 3 PresetManager
     * начнёт загружать одноимённый файл из assets/presets/.
     */
    var presetName: String
        get() = prefs.getString(KEY_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
