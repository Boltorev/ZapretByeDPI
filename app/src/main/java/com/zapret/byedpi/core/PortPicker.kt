package com.zapret.byedpi.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Подбор локального порта для SOCKS5 (ciadpi).
 *
 * Зачем: раньше порт был жёстко зашит в 1080. Если предыдущий процесс
 * ciadpi не успел умереть (или порт занят другой копией приложения),
 * новый ciadpi падал с `code=255`, а приложение бесконечно перезапускало
 * его, не понимая причины.
 *
 * Теперь порт проверяется bind'ом перед стартом: сначала пробуем
 * предпочтительный 1080 (привычный по логам и по внешним инструкциям),
 * а если он занят — берём любой свободный, который выдаст система.
 *
 * Гонка между проверкой и стартом ciadpi теоретически возможна, но
 * обработчик failed-старта теперь пишет и код возврата, и хвост вывода,
 * поэтому такая ситуация сразу видна в журнале.
 */
object PortPicker {
    private const val TAG = "PortPicker"
    private const val EPHEMERAL_ATTEMPTS = 5

    /**
     * Проверяет, можно ли занять порт [port] на [host].
     *
     * ServerSocket создаётся с backlog 1 и сразу закрывается; SO_REUSEADDR
     * выключен намеренно: иначе только что закрытый сокет «освобождал»
     * порт формально, а bind у ciadpi мог всё равно не пройти.
     */
    fun isFree(host: String, port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName(host), port), 1)
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Возвращает свободный порт: [preferred], если он свободен, иначе
     * эфемерный порт от системы.
     *
     * @return номер порта или -1, если свободный порт найти не удалось.
     */
    fun pickSocksPort(host: String, preferred: Int): Int {
        if (isFree(host, preferred)) {
            AppLog.d(TAG, "Порт $preferred свободен, используем его")
            return preferred
        }

        AppLog.w(TAG, "Порт $preferred занят, подбираем свободный")
        val address = try {
            InetAddress.getByName(host)
        } catch (e: IOException) {
            AppLog.w(TAG, "Не удалось разрешить $host: ${e.message}")
            null
        } ?: return -1

        repeat(EPHEMERAL_ATTEMPTS) {
            try {
                // Порт 0 — «выдай любой свободный», после закрытия он
                // остаётся свободным ещё достаточно долго для bind ciadpi.
                ServerSocket(0, 1, address).use { socket ->
                    val port = socket.localPort
                    AppLog.i(TAG, "Выбран свободный порт $port")
                    return port
                }
            } catch (e: IOException) {
                AppLog.w(TAG, "Подбор эфемерного порта не удался: ${e.message}")
            }
        }
        return -1
    }
}
