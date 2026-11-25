package com.zapret.byedpi.service

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import com.zapret.byedpi.core.AppLog
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Сервер protect-сокета для ciadpi.
 *
 * Зачем:
 *   ByeDPI открывает исходящие TCP/UDP-соединения к серверам. Если они
 *   попадут обратно в TUN, получится петля. ByeDPI умеет при подключении
 *   каждого нового сокета отправлять его fd в Unix-сокет приложения
 *   (--protect-path). Мы принимаем fd, вызываем VpnService.protect(fd)
 *   и отвечаем одним байтом.
 *
 * Дополнительно UID приложения целиком исключён из VPN через
 * addDisallowedApplication(), поэтому даже если protect-сервер не поднялся,
 * петли быть не должно.
 */
class ProtectServer(
    private val vpnService: VpnService,
    private val socketPath: String
) {
    companion object {
        private const val TAG = "ProtectServer"
    }

    private var server: LocalServerSocket? = null
    private var boundSocket: LocalSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    var isRunning = false
        private set

    /**
     * Создаёт Unix-сокет и запускает поток accept().
     * Возвращает false, если сокет создать не удалось — тогда ciadpi
     * запустится без --protect-path (защита останется за счёт disallow self).
     */
    fun start(): Boolean {
        return try {
            val file = File(socketPath)
            file.parentFile?.mkdirs()
            if (file.exists() && !file.delete()) {
                AppLog.w(TAG, "Не удалось удалить старый protect-сокет: $socketPath")
            }

            val address = LocalSocketAddress(
                socketPath,
                LocalSocketAddress.Namespace.FILESYSTEM
            )

            // LocalServerSocket(String) на Android использует abstract namespace,
            // а ByeDPI подключается к файловому пути. Поэтому явно биндим
            // LocalSocket в FILESYSTEM namespace и оборачиваем его fd.
            val listener = LocalSocket()
            listener.bind(address)
            boundSocket = listener
            // Конструктор LocalServerSocket(fd) сам вызывает listen().
            server = LocalServerSocket(listener.fileDescriptor)
            running = true
            isRunning = true

            worker = thread(name = "ciadpi-protect", isDaemon = true) {
                acceptLoop()
            }
            AppLog.i(TAG, "protect-сервер слушает $socketPath")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось поднять protect-сервер: ${e.message}")
            try {
                server?.close()
            } catch (_: IOException) {
            }
            try {
                boundSocket?.close()
            } catch (_: IOException) {
            }
            server = null
            boundSocket = null
            isRunning = false
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            val client: LocalSocket = try {
                server?.accept() ?: break
            } catch (e: IOException) {
                if (running) {
                    AppLog.w(TAG, "accept() завершился: ${e.message}")
                    // Не роняем весь protect-сервер из-за одного сбоя.
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                break
            } catch (e: Exception) {
                if (running) AppLog.w(TAG, "accept() ошибка: ${e.message}")
                break
            }

            try {
                handleClient(client)
            } catch (e: Exception) {
                AppLog.w(TAG, "protect-запрос не обработан: ${e.message}")
            } finally {
                try {
                    client.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        client.soTimeout = 2_000

        // ByeDPI отправляет 1 байт вместе с fd через SCM_RIGHTS.
        val first = client.inputStream.read()
        if (first < 0) return

        val descriptors: Array<FileDescriptor>? = client.ancillaryFileDescriptors
        val fd = descriptors?.firstOrNull()
        if (fd == null) {
            AppLog.w(TAG, "protect: fd не пришёл")
            // Всё равно отвечаем, чтобы ByeDPI не ждал таймаут.
            client.outputStream.write(1)
            client.outputStream.flush()
            return
        }

        val protected = try {
            // В публичном API VpnService есть protect(int), protect(Socket),
            // protect(DatagramSocket), но нет protect(FileDescriptor).
            // dup() даёт новый fd на тот же сокет; protect(int) помечает сокет.
            val dup = ParcelFileDescriptor.dup(fd)
            try {
                vpnService.protect(dup.fd)
            } finally {
                dup.close()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "protect(fd) выбросил: ${e.message}")
            false
        }

        if (!protected) {
            // UID приложения и так исключён из VPN; не рвём соединение.
            AppLog.w(TAG, "protect(fd) вернул false (это допустимо: self excluded)")
        }

        // Закрываем нашу копию fd: у ByeDPI остаётся своя.
        try {
            Os.close(fd)
        } catch (_: Exception) {
        }

        client.outputStream.write(1)
        client.outputStream.flush()
    }

    /**
     * Проверяет, что сокет реально принимает подключения и умеет
     * принимать fd через SCM_RIGHTS. Если тест не прошёл, вызывающий код
     * не передаёт ciadpi --protect-path (защита останется по UID).
     */
    fun verify(): Boolean {
        if (!isRunning) return false

        return try {
            LocalSocket().use { client ->
                client.connect(
                    LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                client.soTimeout = 1_000

                val devNull = ParcelFileDescriptor.open(
                    File("/dev/null"),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                try {
                    client.setFileDescriptorsForSend(arrayOf(devNull.fileDescriptor))
                    client.outputStream.write(1)
                    client.outputStream.flush()
                    client.inputStream.read() >= 0
                } finally {
                    devNull.close()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "protect self-test failed: ${e.message}")
            false
        }
    }

    fun close() {
        if (!running && server == null) return

        running = false
        isRunning = false

        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null

        try {
            boundSocket?.close()
        } catch (_: IOException) {
        }
        boundSocket = null

        worker?.interrupt()
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null

        try {
            File(socketPath).delete()
        } catch (_: Exception) {
        }
        AppLog.i(TAG, "protect-сервер остановлен")
    }
}
