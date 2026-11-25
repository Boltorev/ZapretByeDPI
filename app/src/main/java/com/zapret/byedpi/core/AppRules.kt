package com.zapret.byedpi.core

/** Режим Per-App VPN. */
enum class AppMode {
    ALL,
    ONLY,
    EXCEPT
}

/**
 * Правила Per-App VPN.
 *
 * Хранятся в SharedPreferences:
 *   app_mode = ALL / ONLY / EXCEPT
 *   app_packages = package1\npackage2\n...
 *
 * Само приложение в список не попадает: оно всегда исключено из VPN,
 * иначе hev/ciadpi уйдут в собственный TUN.
 */
object AppRules {
    private const val KEY_MODE = "app_mode"
    private const val KEY_PACKAGES = "app_packages"

    fun mode(): AppMode {
        val raw = Prefs.getString(KEY_MODE, AppMode.ALL.name)
        return runCatching { AppMode.valueOf(raw) }.getOrDefault(AppMode.ALL)
    }

    fun packages(): Set<String> {
        return Prefs.getString(KEY_PACKAGES, "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun save(mode: AppMode, packages: Set<String>) {
        Prefs.putString(KEY_MODE, mode.name)
        Prefs.putString(
            KEY_PACKAGES,
            packages.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString("\n")
        )
    }
}
