package com.zapret.byedpi.service

import android.content.Context
import com.zapret.byedpi.core.AppLog
import com.zapret.byedpi.core.DnsResolver
import com.zapret.byedpi.core.EngineConfig
import com.zapret.byedpi.core.PortPicker
import com.zapret.byedpi.core.ProbeFailure
import com.zapret.byedpi.core.ProbeResult
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Запуск и контроль процесса ByeDPI (ciadpi).
 *
 * Вариант A: libbyedpi.so — это ELF executable, лежащий в nativeLibraryDir.
 * Мы запускаем его через ProcessBuilder, читаем stdout/stderr в журнал,
 * ждём открытия SOCKS-порта на 127.0.0.1:EngineConfig.SOCKS_PORT и
 * корректно убиваем при остановке.
 *
 * Диагностика: последние строки вывода ciadpi (stderr объединён с stdout)
 * хранятся в кольцевом буфере. Если процесс умер на старте, в журнал
 * попадают и код возврата, и хвост вывода — раньше на экране была только
 * фраза «SOCKS-порт не открылся», по которой причину не понять.
 */
class ByeDpiProcess(private val context: Context) {
    companion object {
        private const val TAG = "ciadpi"

        /** Имя исполняемого файла. Нужно и снаружи — для поиска зависших процессов. */
        const val BINARY_NAME = "libbyedpi.so"

        private const val START_TIMEOUT_MS = 10_000L

        /** Сколько последних строк вывода держим для диагностики. */
        private const val OUTPUT_TAIL_MAX = 40
    }

    private var process: Process? = null
    private var logReader: Thread? = null
    private var exitWatcher: Thread? = null

    /** Последние строки вывода ciadpi (stdout+stderr). */
    private val outputTail = ArrayDeque<String>()
    private val tailLock = Any()

    /** Код завершения, если процесс уже умер (null — жив или не запускался). */
    @Volatile
    private var lastExitCode: Int? = null

    /** Полный лог ciadpi для экспорта (AppLog хранит только 500 строк). */
    private val ciadpiLogFile = File(context.filesDir, "ciadpi.log")

    @Volatile
    private var stopping = false

    /** Вызывается, если процесс завершился сам, пока VPN работал. */
    var onUnexpectedExit: ((Int) -> Unit)? = null

    fun start(args: List<String>) {
        val exe = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!exe.isFile) {
            throw EngineException(
                "Не найден $BINARY_NAME в ${exe.parent}. " +
                    "Проверьте, что APK собран с useLegacyPackaging и ABI устройства."
            )
        }
        if (!exe.canExecute()) {
            // На некоторых прошивках права снимаются при извлечении. Пробуем
            // восстановить; если не выйдет — ProcessBuilder ниже даст понятную ошибку.
            val fixed = exe.setExecutable(true)
            if (!fixed) {
                AppLog.w(TAG, "Файл $exe не исполняемый; пробуем запуск всё равно")
            }
        }

        val command = ArrayList<String>(args.size + 1).apply {
            add(exe.absolutePath)
            addAll(args)
        }
        AppLog.i(TAG, "Запуск: ${command.joinToString(" ")}")
        AppLog.d(TAG, "SOCKS5 ожидается на ${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT}")

        lastExitCode = null
        synchronized(tailLock) { outputTail.clear() }

        // Не стираем лог при каждом кандидате: при подборе стратегий
        // важно видеть, что происходило с предыдущими попытками.
        try {
            if (ciadpiLogFile.length() > 2_000_000) {
                ciadpiLogFile.writeText("")
            }
            ciadpiLogFile.appendText("\n=== ciadpi start ${System.currentTimeMillis()} ===\n")
            ciadpiLogFile.appendText(command.joinToString(" ") + "\n")
        } catch (e: IOException) {
            AppLog.w(TAG, "Не удалось подготовить ciadpi.log: ${e.message}")
        }

        val builder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)

        val started = try {
            builder.start()
        } catch (e: IOException) {
            throw EngineException("ProcessBuilder не смог запустить ciadpi: ${e.message}", e)
        }

        process = started
        stopping = false

        logReader = thread(name = "ciadpi-log", isDaemon = true) {
            readLogs(started)
        }
        exitWatcher = thread(name = "ciadpi-wait", isDaemon = true) {
            watchExit(started)
        }
    }

    private fun readLogs(p: Process) {
        try {
            p.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        // Хвост вывода нужен для сообщения об ошибке старта:
                        // при redirectErrorStream(true) здесь и stdout, и stderr.
                        synchronized(tailLock) {
                            outputTail.addLast(line)
                            while (outputTail.size > OUTPUT_TAIL_MAX) {
                                outputTail.removeFirst()
                            }
                        }
                        // Полный лог пишем в файл. В AppLog не дублируем
                        // каждую строку, иначе 500 записей вытеснят события
                        // старта VPN. Файл целиком попадает в отчёт «Скачать».
                        try {
                            ciadpiLogFile.appendText(line + "\n")
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (!stopping) {
                AppLog.w(TAG, "Чтение лога ciadpi прервано: ${e.message}")
            }
        }
    }

    /** Последние строки вывода ciadpi. */
    fun lastOutput(maxLines: Int = 12): List<String> =
        synchronized(tailLock) {
            outputTail.takeLast(maxLines.coerceAtLeast(1))
        }

    /** Код завершения процесса или null, если он ещё работает. */
    fun exitCode(): Int? {
        val p = process
        if (p != null && !p.isAlive && lastExitCode == null) {
            lastExitCode = try {
                p.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
        }
        return lastExitCode
    }

    /**
     * Однострочное объяснение, почему ciadpi не поднялся: код возврата,
     * расшифровка типового кода и хвост вывода.
     *
     * Именно эта строка попадает в сообщение об ошибке на главном экране
     * и в журнал, чтобы диагноз не приходилось угадывать.
     */
    fun failureSummary(): String {
        val code = exitCode()
        val port = EngineConfig.SOCKS_PORT
        val reason = when (code) {
            null -> "процесс жив, но SOCKS-порт $port не открылся"
            255 -> buildString {
                append("code=255 (ciadpi не смог стартовать: занят порт $port или неверный аргумент)")
                if (!PortPicker.isFree(EngineConfig.SOCKS_HOST, port)) {
                    append(", порт всё ещё занят другим процессом")
                }
            }
            0 -> "code=0 (процесс завершился сам, не заняв порт $port)"
            else -> "code=$code"
        }
        val tail = lastOutput(8).joinToString(" | ").take(400)
        return if (tail.isEmpty()) reason else "$reason; вывод: $tail"
    }

    private fun watchExit(p: Process) {
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        lastExitCode = code
        if (!stopping) {
            val tail = lastOutput(5).joinToString(" | ").take(300)
            AppLog.w(
                TAG,
                "ciadpi завершился с кодом $code" + if (tail.isEmpty()) "" else "; вывод: $tail"
            )
            onUnexpectedExit?.invoke(code)
        } else {
            AppLog.d(TAG, "ciadpi остановлен (code=$code)")
        }
    }

    /**
     * Ждёт, пока ByeDPI начнёт отвечать по SOCKS5.
     * Проверка — приветствие 05 01 00, ожидаем 05 00.
     */
    fun awaitSocksReady(timeoutMs: Long = START_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopping) return false
            val p = process ?: return false
            if (!p.isAlive) {
                AppLog.e(TAG, "ciadpi умер, не открыв SOCKS-порт: ${failureSummary()}")
                return false
            }
            if (probeSocks()) return true
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        AppLog.e(
            TAG,
            "SOCKS-порт ${EngineConfig.SOCKS_HOST}:${EngineConfig.SOCKS_PORT} " +
                "не открылся за $timeoutMs мс: ${failureSummary()}"
        )
        return false
    }

    private fun probeSocks(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(EngineConfig.SOCKS_HOST, EngineConfig.SOCKS_PORT),
                    300
                )
                socket.soTimeout = 300
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                socket.getOutputStream().flush()
                val input = socket.getInputStream()
                input.read() == 5 && input.read() == 0
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Проверяет не только открытие SOCKS-порта, но и реальный HTTPS-запрос
     * через цепочку SOCKS5 → ciadpi → сайт.
     *
     * HttpsURLConnection сам сделает DNS, TCP, TLS и HTTP. Если ответ получен
     * (любой HTTP-код), значит ciadpi соединяется с интернетом и стратегия
     * не ломает TLS handshake. Если нет — проблема в стратегии/сети/аргументах.
     */
    fun probeTls(host: String, port: Int, timeoutMs: Int = 6_000): Boolean {
        return probeTlsResult(host, port, timeoutMs).ok
    }

    /**
     * Проверяет TLS через SOCKS5, но резолвит хост самим приложением и
     * подключается к уже полученному IP. Так системный DNS/ciadpi не может
     * превратить нормальную стратегию в «not resolved».
     */
    fun probeTlsResult(host: String, port: Int, timeoutMs: Int = 6_000): ProbeResult {
        val dns = DnsResolver.resolve(host, timeoutMs.coerceAtMost(1_800))
        if (!dns.ok) {
            AppLog.w(TAG, "HTTPS probe $host: DNS не разрешил (${dns.source}: ${dns.error})")
            return ProbeResult.fail(ProbeFailure.DNS, dns.error ?: "DNS failed")
        }

        val addresses = dns.addresses
            .filter { it.address.size == 4 }
            .ifEmpty { dns.addresses }

        var last: ProbeResult? = null
        for (address in addresses) {
            try {
                val raw = Socket(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(EngineConfig.SOCKS_HOST, EngineConfig.SOCKS_PORT)
                    )
                )
                raw.connect(InetSocketAddress(address, port), timeoutMs)
                raw.soTimeout = timeoutMs

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
                ssl.close()
                raw.close()

                AppLog.d(TAG, "HTTPS probe $host:$port -> TLS OK (${dns.source}, ip=$address)")
                return ProbeResult.ok()
            } catch (t: Throwable) {
                last = classifyProbeFailure(t)
                AppLog.d(
                    TAG,
                    "HTTPS probe $host:$port failed via $address: " +
                        "${last.failure} ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }

        return last ?: ProbeResult.fail(ProbeFailure.DNS, "no address")
    }

    private fun classifyProbeFailure(t: Throwable): ProbeResult {
        val message = t.message.orEmpty()
        val lower = message.lowercase()
        return when {
            t is UnknownHostException -> ProbeResult.fail(ProbeFailure.DNS, message)
            t is SocketTimeoutException -> ProbeResult.fail(ProbeFailure.TIMEOUT, message)
            lower.contains("unreachable") ||
                lower.contains("no route") ||
                lower.contains("network is down") ->
                ProbeResult.fail(ProbeFailure.ROUTE, message)
            lower.contains("reset") ||
                lower.contains("connection closed") ||
                lower.contains("broken pipe") ->
                ProbeResult.fail(ProbeFailure.RESET, message)
            t is SSLException -> ProbeResult.fail(ProbeFailure.TLS, message)
            t is SocketException -> ProbeResult.fail(ProbeFailure.IO, message)
            else -> ProbeResult.fail(ProbeFailure.IO, "${t.javaClass.simpleName}: $message")
        }
    }

    /**
     * Более строгая проверка, чем [probeTlsResult]: после TLS-рукопожатия
     * реально запрашивает страницу и требует, чтобы пришли данные.
     *
     * Зачем: в логах 0.6.2 X отдавал ровно серверный полёт TLS (~530 байт)
     * и закрывался — рукопожатие проходило, а картинки/видео/комментарии
     * не приходили. Канарейка на одном `startHandshake()` считала такую
     * стратегию рабочей и не ротировала.
     *
     * Порядок: DNS → SOCKS5 к IP → TLS с SNI → HTTP GET → читаем ответ.
     * Если ответа нет или он не похож на HTTP — [ProbeFailure.NO_DATA].
     */
    fun probeHttpsResult(
        host: String,
        port: Int = 443,
        timeoutMs: Int = 6_000,
        path: String = "/"
    ): ProbeResult {
        val dns = DnsResolver.resolve(host, timeoutMs.coerceAtMost(1_800))
        if (!dns.ok) {
            AppLog.w(TAG, "HTTP probe $host: DNS не разрешил (${dns.source}: ${dns.error})")
            return ProbeResult.fail(ProbeFailure.DNS, dns.error ?: "DNS failed")
        }

        val addresses = dns.addresses
            .filter { it.address.size == 4 }
            .ifEmpty { dns.addresses }

        var last: ProbeResult? = null
        for (address in addresses) {
            var raw: Socket? = null
            var ssl: SSLSocket? = null
            try {
                raw = Socket(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(EngineConfig.SOCKS_HOST, EngineConfig.SOCKS_PORT)
                    )
                )
                raw.connect(InetSocketAddress(address, port), timeoutMs)
                raw.soTimeout = timeoutMs

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                ssl = factory.createSocket(raw, host, port, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()

                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: ZapretByeDPI-check\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                ssl.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                ssl.outputStream.flush()

                val response = readHttpHead(ssl, maxBytes = 2048)
                if (response.isEmpty()) {
                    last = ProbeResult.fail(ProbeFailure.NO_DATA, "нет ответа после рукопожатия")
                    AppLog.d(TAG, "HTTP probe $host:$port via $address -> NO_DATA")
                } else {
                    val head = String(response, Charsets.ISO_8859_1)
                    val code = parseStatusCode(head)
                    if (code == null) {
                        last = ProbeResult.fail(
                            ProbeFailure.NO_DATA,
                            "ответ не похож на HTTP: ${head.take(40)}"
                        )
                    } else {
                        AppLog.d(
                            TAG,
                            "HTTP probe $host:$port -> HTTP $code, ${response.size} байт " +
                                "(${dns.source}, ip=$address)"
                        )
                        return ProbeResult.ok(code)
                    }
                }
            } catch (t: Throwable) {
                last = classifyProbeFailure(t)
                AppLog.d(
                    TAG,
                    "HTTP probe $host:$port failed via $address: " +
                        "${last.failure} ${t.javaClass.simpleName}: ${t.message}"
                )
            } finally {
                try {
                    ssl?.close()
                } catch (_: IOException) {
                }
                try {
                    raw?.close()
                } catch (_: IOException) {
                }
            }
        }

        return last ?: ProbeResult.fail(ProbeFailure.DNS, "no address")
    }

    /**
     * Дочитывает начало HTTP-ответа: заголовки или хотя бы первые байты.
     * Возвращает пустой массив, если сервер закрыл соединение молча.
     */
    private fun readHttpHead(ssl: SSLSocket, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        val deadline = System.currentTimeMillis() + 4_000
        try {
            while (total < maxBytes && System.currentTimeMillis() < deadline) {
                val read = ssl.inputStream.read(buffer, total, maxBytes - total)
                if (read <= 0) break
                total += read
                // Заголовки закончились — дальше тело нам не нужно.
                if (containsHeaderEnd(buffer, total)) break
            }
        } catch (e: SocketTimeoutException) {
            // Успели прочитать часть — вернём её; иначе пусто = NO_DATA.
            AppLog.d(TAG, "HTTP probe: таймаут чтения, получено $total байт")
        } catch (_: SocketException) {
        }
        return if (total == 0) ByteArray(0) else buffer.copyOf(total)
    }

    private fun containsHeaderEnd(buffer: ByteArray, length: Int): Boolean {
        for (i in 3 until length) {
            if (buffer[i - 3] == 13.toByte() && buffer[i - 2] == 10.toByte() &&
                buffer[i - 1] == 13.toByte() && buffer[i] == 10.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun parseStatusCode(head: String): Int? {
        val match = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(head.trim())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun stop() {
        stopping = true
        val p = process
        process = null
        if (p != null) {
            try {
                p.inputStream.close()
            } catch (_: IOException) {
            }

            p.destroy()
            val deadline = System.currentTimeMillis() + 1_500
            while (p.isAlive && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (p.isAlive) {
                AppLog.w(TAG, "ciadpi не завершился штатно, destroyForcibly()")
                p.destroyForcibly()
            }
        }

        try {
            logReader?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            exitWatcher?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logReader = null
        exitWatcher = null
        AppLog.i(TAG, "Процесс ciadpi остановлен")
    }
}
