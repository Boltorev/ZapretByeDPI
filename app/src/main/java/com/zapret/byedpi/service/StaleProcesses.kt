package com.zapret.byedpi.service

import android.os.Process
import com.zapret.byedpi.core.AppLog
import java.io.File

/**
 * Поиск и завершение «зависших» процессов ciadpi.
 *
 * Откуда они берутся:
 *  - предыдущая копия службы упала и не успела вызвать destroyForcibly();
 *  - пользователь убил приложение из недавних, а дочерний процесс остался;
 *  - два экземпляра приложения (например, после обновления) делили порт 1080.
 *
 * Такой процесс держит SOCKS-порт, из-за чего новый ciadpi падает
 * с `code=255`, а hev не может подключиться. Поэтому перед стартом VPN
 * мы ищем процессы по имени исполняемого файла и убиваем их.
 *
 * Безопасность: ciadpi запускается от нашего UID, поэтому sendSignal
 * разрешён. Чужие UID мы не трогаем — если /proc отдаёт пустой cmdline,
 * процесс просто пропускается.
 */
internal object StaleProcesses {
    private const val TAG = "StaleProc"

    /** Сколько ждём, пока убитый процесс исчезнет из /proc. */
    private const val KILL_WAIT_MS = 600L

    /**
     * Ищет процессы, в командной строке которых есть [binaryName].
     *
     * Читаем /proc напрямую, без `ps`: на Android `ps -A` в некоторых
     * прошивках фильтрует вывод по UID, а чтение /proc своего UID работает
     * всегда и не требует shell.
     */
    fun findPids(binaryName: String): List<Int> {
        val self = Process.myPid()
        val entries = try {
            File("/proc").listFiles()
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val result = ArrayList<Int>(2)
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = try {
                File(entry, "cmdline").readText()
            } catch (_: Throwable) {
                continue
            }
            if (cmdline.contains(binaryName)) {
                result.add(pid)
            }
        }
        return result
    }

    /**
     * Убивает все найденные процессы (SIGKILL) и ждёт их исчезновения.
     *
     * @return сколько процессов было найдено (не обязательно убито).
     */
    fun killAll(binaryName: String): Int {
        val pids = findPids(binaryName)
        if (pids.isEmpty()) return 0

        AppLog.w(TAG, "Найдены зависшие ciadpi: ${pids.joinToString()}, завершаем")
        var killed = 0
        for (pid in pids) {
            try {
                Process.sendSignal(pid, Process.SIGNAL_KILL)
                killed++
            } catch (t: Throwable) {
                AppLog.w(TAG, "Не удалось завершить pid=$pid: ${t.message}")
            }
        }

        // Ждём, чтобы ядро успело закрыть сокеты и освободить порт.
        val deadline = System.currentTimeMillis() + KILL_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (pids.none { isAlive(it) }) break
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val survivors = pids.filter { isAlive(it) }
        if (survivors.isNotEmpty()) {
            AppLog.w(TAG, "Не завершились: ${survivors.joinToString()}")
        } else {
            AppLog.i(TAG, "Зависшие ciadpi завершены ($killed)")
        }
        return pids.size
    }

    /** Процесс с таким pid ещё существует? */
    private fun isAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (_: Throwable) {
            false
        }
    }
}
