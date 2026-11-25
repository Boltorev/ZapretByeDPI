package com.zapret.byedpi.telegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.zapret.byedpi.R

object TelegramProxyController {

    fun start(context: Context) {
        TelegramProxyService.start(context)
    }

    fun stop(context: Context) {
        TelegramProxyService.stop(context)
    }

    /** Ссылка для ручного добавления прокси в Telegram. */
    fun proxyLink(): String? {
        val state = TelegramRuntime.state.value
        val secret = state.secret ?: return null
        return "tg://proxy?server=127.0.0.1&port=${state.port}&secret=$secret"
    }

    /**
     * Открывает Telegram с готовым прокси. Если Telegram не установлен —
     * копирует ссылку в буфер.
     */
    fun apply(context: Context): Boolean {
        val link = proxyLink()
        if (link == null) {
            Toast.makeText(context, R.string.telegram_not_running, Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            copy(context, link)
            Toast.makeText(context, R.string.telegram_link_copied, Toast.LENGTH_LONG).show()
            false
        }
    }

    fun copyLink(context: Context): Boolean {
        val link = proxyLink() ?: return false
        copy(context, link)
        Toast.makeText(context, R.string.telegram_link_copied, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram WS proxy", text))
    }
}
