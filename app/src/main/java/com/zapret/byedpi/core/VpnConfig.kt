package com.zapret.byedpi.core

/**
 * Константы VPN-туннеля этапа 2.
 *
 * Теперь приложение перехватывает ВЕСЬ IPv4/IPv6-трафик и передаёт его
 * в hev-socks5-tunnel → SOCKS5 → ByeDPI.
 *
 * Само приложение исключается из VPN через addDisallowedApplication(),
 * поэтому его сокеты (ciadpi, hev, protect-сервер) не попадают в TUN
 * и не возникает петля. Дополнительно для ciadpi включён --protect-path.
 */
object VpnConfig {
    /** Имя сессии, которое видно в системных настройках VPN. */
    const val SESSION_NAME = "ByeDPI Zapret"

    /** MTU туннеля. Должен совпадать с tunnel.mtu в конфиге hev. */
    const val MTU = 1400

    // Адрес нашего конца туннеля. /32 — типичная схема для VpnService.
    const val ADDRESS_V4 = "10.10.10.10"
    const val PREFIX_V4 = 32

    const val ADDRESS_V6 = "fd00::1"
    const val PREFIX_V6 = 128

    // IPv6: hev поддерживает dual-stack, но на части мобильных сетей
    // IPv6-маршрут в VPN приводит к чёрной дыре: приложения пытаются
    // подключиться по IPv6 и виснут. Для этапа 2 оставляем только IPv4,
    // чтобы гарантировать рабочий туннель. Вернуть true можно после
    // проверки IPv6 на конкретном операторе.
    const val ENABLE_IPV6 = false

    // DNS. 77.88.8.8 (Яндекс) обычно доступен из РФ и не блокируется
    // провайдером; 8.8.8.8 и 1.1.1.1 — резервные.
    const val DNS_PRIMARY = "1.1.1.1"
    const val DNS_SECONDARY = "8.8.8.8"
    const val DNS_TERTIARY = "9.9.9.9"
    const val DNS_QUATERNARY = "77.88.8.8"

    /** Имя TUN-интерфейса для отображения в UI. */
    const val TUN_NAME = "tun0"
}
