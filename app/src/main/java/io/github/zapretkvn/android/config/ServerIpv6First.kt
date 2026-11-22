package io.github.zapretkvn.android.config

import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * IPv6 к VPN-серверу первым (владелец 2026-11-22).
 *
 * Адрес IPv4 сервера часто блокируют по адресу, а IPv6 того же сервера проходит. Поэтому резолвер адреса
 * сервера получает `strategy: prefer_ipv6`, а дальше всё делают ядра за прокладкой sing-box (docs/ARCHITECTURE.md):
 * - TCP (Xray VLESS/VMess/Trojan, Shadowsocks и др.): DialParallel sing-box, IPv6 первым, IPv4 через 300 мс
 *   или сразу при отказе IPv6; память семейства по имени сервера (патч ядра 0020, `common/familymemory`).
 * - Hysteria2: официальный клиент сам переподключается; обвязка открывает следующее подключение на другом
 *   семействе, если рукопожатие на первом не состоялось (тот же патч).
 * - Прочий UDP (TUIC, UDP Shadowsocks, UDP-транспорты Xray): один сокет без отката, поэтому ядро оставляет
 *   IPv4 первым, пока IPv6 не сработал для этого имени.
 * - WireGuard/AWG (endpoints) не трогаются: endpoint берёт один адрес без отката.
 *
 * Решение «включать ли» — [enabled]: настройка и собственный IPv6 у основной сети телефона.
 */
object ServerIpv6First {
    const val STRATEGY = "prefer_ipv6"

    /** Типы без своего сервера: им резолвер адреса сервера не нужен. */
    private val LOCAL_TYPES = setOf("direct", "block", "dns", "selector", "urltest")

    fun enabled(setting: Boolean, uplinkHasIpv6: Boolean): Boolean = setting && uplinkHasIpv6

    /**
     * Есть ли у основной сети собственный глобальный IPv6: адрес 2000::/3 (не Teredo 2001::/32, не 6to4 2002::/16)
     * и маршрут по умолчанию для IPv6. Только данные LinkProperties, никаких зондов в сеть.
     */
    fun uplinkHasIpv6(addresses: List<InetAddress>, hasDefaultIpv6Route: Boolean): Boolean =
        hasDefaultIpv6Route && addresses.any(::isNativeGlobalIpv6)

    fun isNativeGlobalIpv6(address: InetAddress): Boolean {
        if (address !is Inet6Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        if (first and 0xe0 != 0x20) return false // вне 2000::/3
        val second = bytes[1].toInt() and 0xff
        if (first == 0x20 && second == 0x02) return false // 6to4
        if (first == 0x20 && second == 0x01 && bytes[2].toInt() == 0 && bytes[3].toInt() == 0) return false // Teredo
        return true
    }

    /**
     * Резолвер адреса сервера с `prefer_ipv6` у outbound-ов, которые ходят на свой сервер.
     *
     * Строковый `domain_resolver` (в том числе bootstrap-оверлей выбранного сервера) превращается в объект с тем же
     * сервером; объект получает стратегию, если своей нет (заданную пользователем не меняем). Без резолвера —
     * [defaultResolver] (управляемый DNS), иначе outbound остаётся как был. Outbound с `detour` идёт через другой
     * outbound и не трогается; IP-литерал разрешать нечего.
     */
    fun apply(outbounds: JsonArray, defaultResolver: String?): JsonArray = JsonArray(
        outbounds.map { element ->
            val outbound = element as? JsonObject ?: return@map element
            if (!dialsServer(outbound)) return@map element
            val resolver = withStrategy(outbound["domain_resolver"], defaultResolver) ?: return@map element
            JsonObject(outbound.toMutableMap().apply { this["domain_resolver"] = resolver })
        },
    )

    private fun dialsServer(outbound: JsonObject): Boolean {
        val type = outbound.string("type") ?: return false
        if (type in LOCAL_TYPES || outbound.containsKey("detour")) return false
        val server = outbound.string("server")
        return when {
            server != null -> !isIpLiteral(server)
            else -> outbound.containsKey("uri")
        }
    }

    private fun withStrategy(current: JsonElement?, defaultResolver: String?): JsonObject? = when (current) {
        null -> defaultResolver?.let(::resolver)
        is JsonPrimitive -> current.contentOrNullString()?.let(::resolver)
        is JsonObject -> if (current.containsKey("strategy")) {
            current
        } else {
            JsonObject(current.toMutableMap().apply { this["strategy"] = JsonPrimitive(STRATEGY) })
        }
        else -> null
    }

    private fun resolver(server: String): JsonObject = JsonObject(
        mapOf("server" to JsonPrimitive(server), "strategy" to JsonPrimitive(STRATEGY)),
    )

    private fun JsonPrimitive.contentOrNullString(): String? =
        if (isString) content.takeIf { it.isNotBlank() } else null

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (host.contains(':')) return host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
