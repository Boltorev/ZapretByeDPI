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

    /**
     * Канал нужно создать до первого startForeground().
     * Повторный вызов безопасен: если канал уже есть, ничего не делаем.
     */
    fun createChannel(context: Context) {
        // minSdk = 26, поэтому NotificationChannel доступен всегда.
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

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

    /**
     * Собирает уведомление под текущую фазу.
     * Текст обновляется, когда служба переходит STARTING -> RUNNING.
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

        val text = if (state.phase == VpnPhase.RUNNING) {
            context.getString(R.string.notification_text_running, state.presetName)
        } else {
            context.getString(R.string.notification_text_starting)
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
