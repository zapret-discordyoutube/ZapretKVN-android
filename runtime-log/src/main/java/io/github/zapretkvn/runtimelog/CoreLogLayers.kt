package io.github.zapretkvn.runtimelog

/**
 * Слой строки лога ядра по её телу. Формат строки sing-box:
 * `INFO[0820] [687738589 0ms] outbound/hysteria2[тег]: текст`; префикс до второй `]` отбрасывается.
 */
object CoreLogLayers {
    fun classify(rawLine: String): LogLayer {
        val body = bodyOf(rawLine).lowercase()
        return when {
            body.startsWith("dns") -> LogLayer.Dns
            body.startsWith("inbound/tun") || body.startsWith("tun") || body.contains("tun: ") -> LogLayer.Tun
            body.startsWith("outbound/") || body.startsWith("endpoint/") ||
                body.contains("hysteria") || body.contains("xray") -> LogLayer.Outbound
            body.startsWith("router") || body.startsWith("route") || body.startsWith("rule") -> LogLayer.Routing
            NETWORK_MARKERS.any(body::contains) -> LogLayer.Network
            else -> LogLayer.Core
        }
    }

    /** Уровень sing-box из имени в начале строки: `ERROR[0200]` → 2; неизвестное → 4 (info). */
    fun levelOf(rawLine: String): Int = when {
        rawLine.startsWith("PANIC") -> 0
        rawLine.startsWith("FATAL") -> 1
        rawLine.startsWith("ERROR") -> 2
        rawLine.startsWith("WARN") -> 3
        rawLine.startsWith("INFO") -> 4
        rawLine.startsWith("DEBUG") -> 5
        rawLine.startsWith("TRACE") -> 6
        else -> 4
    }

    internal fun bodyOf(rawLine: String): String {
        val first = rawLine.indexOf(']')
        if (first < 0) return rawLine
        val second = rawLine.indexOf(']', first + 1)
        return if (second < 0) rawLine.substring(first + 1).trimStart() else rawLine.substring(second + 1).trimStart()
    }

    private val NETWORK_MARKERS = listOf("network", "default interface", "interface", "wifi", "cellular", "connectivity")
}
