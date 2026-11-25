package com.zapret.byedpi.core

import android.content.Context
import java.io.File

/**
 * Пользовательский список доменов.
 *
 * Файл `files/user_hosts.txt`, по одному домену на строку.
 * Домены попадают в отдельную группу ciadpi `-H ":a b c"` со стратегией
 * `-s 1` — то есть для них включается split по первому байту, даже если
 * это не YouTube/Discord.
 */
object DomainStore {
    const val FILE_NAME = "user_hosts.txt"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun loadText(context: Context): String {
        val file = file(context)
        return if (file.isFile) file.readText() else ""
    }

    fun load(context: Context): List<String> {
        return loadText(context)
            .lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("*.").trim('.') }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
            .distinct()
            .toList()
    }

    /** Сохраняет нормализованный список, возвращает количество доменов. */
    fun save(context: Context, text: String): Int {
        val domains = text
            .lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removePrefix("*.").trim('.') }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
            .distinct()
            .toList()

        file(context).writeText(domains.joinToString("\n"))
        return domains.size
    }
}
