package com.zapret.byedpi

import android.app.Application
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.EngineConfig
import com.zapret.byedpi.core.Prefs
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.util.NotificationHelper

/**
 * Application-класс — самая ранняя точка входа в процесс приложения.
 *
 * Здесь:
 *  1. инициализируем SharedPreferences (Prefs);
 *  2. создаём канал уведомлений (Android 8+ требует канал заранее);
 *  3. пишем первую запись в журнал.
 *
 * Класс зарегистрирован в AndroidManifest.xml через android:name=".ZapretApp".
 */
class ZapretApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Prefs.init(this)
        NotificationHelper.createChannel(this)

        // Показываем в UI сохранённый пресет ещё до запуска службы.
        VpnRuntime.update {
            it.copy(presetName = EngineConfig.PRESET_LABEL)
        }

        AppLog.i(TAG, "Приложение запущено")
        val firstStrategy = EngineConfig.strategies().first()
        AppLog.i(
            TAG,
            "Стратегия по умолчанию: ${firstStrategy.name}, " +
                "аргументы: ${EngineConfig.argsPreview(firstStrategy)}"
        )
    }

    companion object {
        private const val TAG = "App"
    }
}
