package com.zapret.byedpi.util

import android.content.Context
import android.net.Uri
import android.os.Build
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.AppRules
import com.zapret.byedpi.core.DomainStore
import com.zapret.byedpi.core.EngineConfig
import com.zapret.byedpi.core.NetworkDiagnostics
import com.zapret.byedpi.core.Prefs
import com.zapret.byedpi.core.VpnConfig
import com.zapret.byedpi.core.VpnRuntime
import com.zapret.byedpi.nativebridge.HevBridge
import com.zapret.byedpi.telegram.TelegramRuntime
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Собирает диагностический отчёт и сохраняет его через SAF
 * (ActivityResultContracts.CreateDocument), без разрешений на хранилище.
 *
 * В отчёт попадает:
 *  - устройство и версия приложения;
 *  - текущее состояние VPN, выбранный пресет/движок;
 *  - проверка загрузки hev и статистика TUN;
 *  - список нативных библиотек;
 *  - полный AppLog (до 500 строк) и хвосты ciadpi.log / hev.log.
 */
object LogExporter {
    private const val MAX_TAIL_BYTES = 300_000

    fun suggestFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "zapret-log-$stamp.txt"
    }

    fun write(context: Context, uri: Uri): Boolean {
        return try {
            val report = buildReport(context)
            context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(report.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return false
            true
        } catch (t: Throwable) {
            AppLog.e("LogExporter", "Не удалось сохранить отчёт: ${t.message}", t)
            false
        }
    }

    fun buildReport(context: Context): String {
        val state = VpnRuntime.state.value
        val stats = HevBridge.statsOrNull()

        return buildString {
            appendLine("ZapretByeDPI — диагностический отчёт")
            appendLine("generated: ${Date()}")
            appendLine()

            appendLine("=== Приложение ===")
            appendLine("package: ${context.packageName}")
            appendLine("version: ${appVersion(context)}")
            appendLine()

            appendLine("=== Устройство ===")
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()

            val diagnostics = NetworkDiagnostics.check(context)
            appendLine("=== Сеть ===")
            appendLine("network: ${diagnostics.network}")
            appendLine("dns: ${diagnostics.dns}")
            appendLine("route: ${diagnostics.route}")
            appendLine("ok: ${diagnostics.ok}")
            appendLine()

            appendLine("=== Состояние VPN ===")
            appendLine("phase: ${state.phase}")
            appendLine("preset: ${state.presetName} (prefs=${Prefs.presetName})")
            appendLine("engine: ${state.engineLabel}")
            appendLine("tun: ${state.tunName}")
            appendLine("socks5: ${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT} " +
                "(default ${EngineConfig.DEFAULT_SOCKS_PORT})")
            appendLine("startedAt: ${state.startedAt}")
            appendLine("rotations: ${state.rotationCount}")
            appendLine("autostate.yt: ${Prefs.getString("autostate.yt")}")
            appendLine("autostate.dc: ${Prefs.getString("autostate.dc")}")
            appendLine("autostate.x: ${Prefs.getString("autostate.x")}")
            appendLine("error: ${state.errorMessage}")
            appendLine("ipv6 enabled: ${VpnConfig.ENABLE_IPV6}")
            appendLine("per-app mode: ${AppRules.mode()}")
            appendLine("per-app packages: ${AppRules.packages().size}")
            appendLine("user hosts: ${DomainStore.load(context).size}")
            val telegram = TelegramRuntime.state.value
            appendLine("telegram: ${telegram.phase} port=${telegram.port} error=${telegram.error}")
            appendLine()

            appendLine("=== hev / native ===")
            appendLine("hev available: ${HevBridge.isAvailable}")
            appendLine("hev load error: ${HevBridge.loadError}")
            if (stats != null && stats.size >= 4) {
                appendLine("tun stats: tx_packets=${stats[0]} tx_bytes=${stats[1]} " +
                    "rx_packets=${stats[2]} rx_bytes=${stats[3]}")
            } else {
                appendLine("tun stats: нет данных (hev не запущен или не загрузился)")
            }
            appendLine("nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}")
            listNativeLibs(context)?.forEach { (name, size) ->
                appendLine("  $name ($size bytes)")
            }
            appendLine()

            appendLine("=== Кандидаты стратегий ===")
            EngineConfig.strategies().forEach { strategy ->
                appendLine("${strategy.name}: ${EngineConfig.argsPreview(strategy)}")
            }
            appendLine()

            appendLine("=== Файлы приложения ===")
            for (name in listOf("ciadpi.log", "hev.log", "ciadpi-protect.sock")) {
                val file = File(context.filesDir, name)
                appendLine("$name: exists=${file.exists()} size=${if (file.isFile) file.length() else "-"}")
            }
            appendLine()

            appendLine("=== AppLog (${AppLog.entries.value.size}) ===")
            AppLog.entries.value.forEach { entry ->
                appendLine(entry.toDisplayLine())
            }
            appendLine()

            appendLine("=== ciadpi.log (хвост) ===")
            appendLine(readTail(File(context.filesDir, "ciadpi.log")))
            appendLine()

            appendLine("=== hev.log (хвост) ===")
            appendLine(readTail(File(context.filesDir, "hev.log")))
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun listNativeLibs(context: Context): List<Pair<String, Long>>? {
        return try {
            File(context.applicationInfo.nativeLibraryDir)
                .listFiles()
                ?.sortedBy { it.name }
                ?.map { it.name to it.length() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readTail(file: File, maxBytes: Int = MAX_TAIL_BYTES): String {
        if (!file.isFile) return "(файла нет)"
        return try {
            val length = file.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val size = (length - start).toInt()
                val buffer = ByteArray(size)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            "(ошибка чтения: ${t.message})"
        }
    }
}
