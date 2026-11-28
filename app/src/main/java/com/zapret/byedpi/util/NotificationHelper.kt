package com.zapret.byedpi.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zapret.byedpi.MainActivity
import com.zapret.byedpi.R
import com.zapret.byedpi.core.VpnPhase
import com.zapret.byedpi.core.VpnState
import com.zapret.byedpi.service.ZapretVpnService

/**
 * Создание канала и уведомления foreground-службы.
 *
 * VPN обязана быть foreground-службой: иначе система может убить её
 * в фоне, а пользователь не увидит, что туннель активен.
 */
object NotificationHelper {
    const val CHANNEL_ID = "vpn_status"
    const val NOTIFICATION_ID = 1001

    /** Отдельный канал для разовых предупреждений о проблемах. */
    const val PROBLEM_CHANNEL_ID = "vpn_problems"
    const val PROBLEM_NOTIFICATION_ID = 1002

    /**
     * Канал нужно создать до первого startForeground().
     * Повторный вызов безопасен: если канал уже есть, ничего не делаем.
     */
    fun createChannel(context: Context) {
        // minSdk = 26, поэтому NotificationChannel доступен всегда.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        if (manager.getNotificationChannel(PROBLEM_CHANNEL_ID) == null) {
            val problems = NotificationChannel(
                PROBLEM_CHANNEL_ID,
                context.getString(R.string.problem_channel_name),
                // DEFAULT, а не LOW: о проблеме нужно узнать сразу.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.problem_channel_description)
                setShowBadge(true)
            }
            manager.createNotificationChannel(problems)
        }
    }

    /**
     * Разовое уведомление о проблеме: ошибка движка, туннель не поднялся,
     * Telegram-прокси не слушает порт. В отличие от постоянного уведомления,
     * его можно смахнуть, и оно не висит, когда всё в порядке.
     */
    fun notifyProblem(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, PROBLEM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(ContextCompat.getColor(context, R.color.state_off))
            .setContentTitle(context.getString(R.string.problem_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(PROBLEM_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Уведомления запрещены — ничего не поделать.
        }
    }

    /**
     * Собирает уведомление под текущую фазу.
     * Текст обновляется при старте, смене стратегии и ошибках.
     */
    fun build(context: Context, state: VpnState): Notification {
        // Тап по уведомлению открывает главный экран.
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Кнопка «Остановить» отправляет службе ACTION_STOP.
        val stopIntent = Intent(context, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when (state.phase) {
            VpnPhase.ERROR -> context.getString(
                R.string.notification_text_error,
                state.errorMessage ?: context.getString(R.string.state_value_error)
            )
            VpnPhase.RUNNING -> buildString {
                append(context.getString(R.string.notification_text_running, state.presetName))
                val event = state.lastEvent
                if (!event.isNullOrBlank()) {
                    append('\n').append(event)
                }
            }
            else -> context.getString(R.string.notification_text_starting)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_stat_stop,
                context.getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .build()
    }
}
