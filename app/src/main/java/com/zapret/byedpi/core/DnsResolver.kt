package com.zapret.byedpi.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Параллельный DNS-резолвер приложения.
 *
 * Зачем: ciadpi при probe получает домен и резолвит его системным
 * getaddrinfo. На Android 16 / Xiaomi системный DNS периодически отдаёт
 * «not resolved», и авто-подбор принимает это за плохую стратегию.
 *
 * Здесь мы резолвим имя сами (UDP-гонка + system + DoH), после чего probe
 * идёт в SOCKS5 уже по IP. SNI при этом остаётся настоящим доменом.
 */
object DnsResolver {

    data class DnsResult(
        val addresses: List<InetAddress>,
        val source: String,
        val error: String? = null
    ) {
        val ok: Boolean get() = addresses.isNotEmpty()
    }

    private data class CacheEntry(val result: DnsResult, val expiresAt: Long)

    private val servers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8")
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private const val POSITIVE_TTL_MS = 5 * 60_000L
    private const val NEGATIVE_TTL_MS = 30_000L

    fun resolve(host: String, timeoutMs: Int = 1_500): DnsResult {
        val key = host.trim().lowercase().trimEnd('.')
        if (key.isEmpty()) return DnsResult(emptyList(), "cache", "empty host")

        cache[key]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis()) return entry.result
            cache.remove(key, entry)
        }

        val queue = LinkedBlockingQueue<DnsResult>()
        val deadline = System.currentTimeMillis() + timeoutMs

        for (server in servers) {
            thread(name = "dns-$server", isDaemon = true) {
                val result = queryUdp(key, server, timeoutMs.coerceAtLeast(500))
                if (result.ok) queue.offer(result)
            }
        }

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val result = try {
                queue.poll(remaining, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } ?: break
            if (result.ok) {
                cache[key] = CacheEntry(result, System.currentTimeMillis() + POSITIVE_TTL_MS)
                return result
            }
        }

        val system = systemLookup(key)
        if (system.ok) {
            cache[key] = CacheEntry(system, System.currentTimeMillis() + POSITIVE_TTL_MS)
            return system
        }

        val doh = queryDoh(key, timeoutMs)
        if (doh.ok) {
            cache[key] = CacheEntry(doh, System.currentTimeMillis() + POSITIVE_TTL_MS)
            return doh
        }

        val failure = DnsResult(
            emptyList(),
            "none",
            "UDP/system/DoH не разрешили $key"
        )
        cache[key] = CacheEntry(failure, System.currentTimeMillis() + NEGATIVE_TTL_MS)
        return failure
    }

    fun clear() {
        cache.clear()
    }

    private fun queryUdp(host: String, server: String, timeoutMs: Int): DnsResult {
        val started = System.currentTimeMillis()
        return try {
            val id = Random.nextInt(1, 0xffff)
            val query = buildQuery(id, host)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        InetAddress.getByName(server),
                        53
                    )
                )
                val buffer = ByteArray(1500)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val ips = parseAnswer(buffer, packet.length, id)
                DnsResult(
                    ips.map { InetAddress.getByAddress(it) },
                    "udp:$server",
                    if (ips.isEmpty()) "empty answer" else null
                )
            }
        } catch (e: Exception) {
            DnsResult(
                emptyList(),
                "udp:$server",
                "${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - started}ms)"
            )
        }
    }

    private fun systemLookup(host: String): DnsResult {
        return try {
            val addresses = InetAddress.getAllByName(host).toList()
            DnsResult(addresses, "system", if (addresses.isEmpty()) "empty" else null)
        } catch (e: Exception) {
            DnsResult(emptyList(), "system", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Простой DoH JSON-запрос Cloudflare. Используется только как последний fallback. */
    private fun queryDoh(host: String, timeoutMs: Int): DnsResult {
        val url = "https://cloudflare-dns.com/dns-query?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=A"
        return try {
            val connection = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/dns-json")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val ips = Regex("\"data\"\\s*:\\s*\"([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\"")
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            DnsResult(
                ips.map { InetAddress.getByName(it) },
                "doh:cloudflare",
                if (ips.isEmpty()) "empty DoH answer" else null
            )
        } catch (e: Exception) {
            DnsResult(emptyList(), "doh:cloudflare", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildQuery(id: Int, host: String): ByteArray {
        val labels = host.split('.').filter { it.isNotEmpty() }
        val out = java.io.ByteArrayOutputStream()
        out.write((id ushr 8) and 0xff)
        out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // RD
        out.write(0x00); out.write(0x01) // QDCOUNT
        out.write(0x00); out.write(0x00) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(0x00); out.write(0x01) // A
        out.write(0x00); out.write(0x01) // IN
        return out.toByteArray()
    }

    private fun parseAnswer(data: ByteArray, length: Int, expectedId: Int): List<ByteArray> {
        if (length < 12) return emptyList()
        val id = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        if (id != expectedId) return emptyList()
        val flags = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if (flags and 0x8000 == 0) return emptyList()
        val qd = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val an = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
        var offset = 12
        repeat(qd) {
            offset = skipName(data, offset, length)
            if (offset + 4 > length) return emptyList()
            offset += 4
        }
        val result = mutableListOf<ByteArray>()
        repeat(an) {
            offset = skipName(data, offset, length)
            if (offset + 10 > length) return emptyList()
            val type = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            val klass = ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
            val rdLength = ((data[offset + 8].toInt() and 0xff) shl 8) or (data[offset + 9].toInt() and 0xff)
            offset += 10
            if (offset + rdLength > length) return emptyList()
            if (type == 1 && klass == 1 && rdLength == 4) {
                result += data.copyOfRange(offset, offset + 4)
            }
            offset += rdLength
        }
        return result
    }

    private fun skipName(data: ByteArray, start: Int, length: Int): Int {
        var offset = start
        while (offset < length) {
            val len = data[offset].toInt() and 0xff
            if (len == 0) return offset + 1
            if (len and 0xc0 == 0xc0) return offset + 2
            offset += 1 + len
        }
        return offset
    }
}
