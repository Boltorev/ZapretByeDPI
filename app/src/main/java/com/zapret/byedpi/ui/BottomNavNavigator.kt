package com.zapret.byedpi.ui

import android.app.Activity
import android.content.Intent
import com.zapret.byedpi.MainActivity
import com.zapret.byedpi.R

/** Переключение трёх разделов через нижнюю полоску. */
object BottomNavNavigator {
    fun navigate(activity: Activity, currentItemId: Int, selectedItemId: Int) {
        if (selectedItemId == currentItemId) return
        val target = when (selectedItemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_telegram -> TelegramActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            R.id.nav_logs -> LogActivity::class.java
            else -> return
        }
        val intent = Intent(activity, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }
}
