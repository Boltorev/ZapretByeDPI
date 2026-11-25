package com.zapret.byedpi.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Быстрая проверка: есть ли вообще сеть, DNS и маршрут до интернета.
 *
 * Нужна, чтобы не запускать перебор 8 стратегий, когда проблема в сети.
 */
object NetworkDiagnostics {

    data class Report(
        val network: String,
        val dns: String,
        val route: String,
        val ok: Boolean
    )

    fun check(context: Context, timeoutMs: Int = 2_000): Report {
        val network = activeNetwork(context)
        if (network == "none") {
            return Report(network, "skipped", "skipped", false)
        }

        val dnsResult = DnsResolver.resolve("youtubei.googleapis.com", timeoutMs)
        val dns = if (dnsResult.ok) {
            "ok:${dnsResult.source}"
        } else {
            "fail:${dnsResult.error}"
        }

        val route = if (dnsResult.ok) {
            val targets = listOf("1.1.1.1", "8.8.8.8")
            var result = "fail:no target"
            for (target in targets) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(target, 443), timeoutMs)
                    }
                    result = "ok:$target"
                    break
                } catch (t: Throwable) {
                    result = "fail:$target ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            result
        } else {
            "skipped"
        }

        return Report(
            network = network,
            dns = dns,
            route = route,
            // Маршрут до 1.1.1.1/8.8.8.8 может быть закрыт DPI/оператором,
            // хотя обычный интернет работает. Поэтому gate — DNS + наличие
            // сети; route нужен для диагностики и последующей классификации.
            ok = dnsResult.ok && network != "none"
        )
    }

    private fun activeNetwork(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return "none"
            val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (t: Throwable) {
            "error:${t.message}"
        }
    }
}
