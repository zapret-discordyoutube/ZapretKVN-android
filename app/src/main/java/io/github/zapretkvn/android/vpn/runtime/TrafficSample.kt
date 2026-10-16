package io.github.zapretkvn.android.vpn.runtime

/**
 * Снимок счётчиков ядра (CommandStatus) работающей сессии. Счётчики накопительные
 * с начала сессии; [connections] — открытые соединения в момент снимка.
 * Источник один на сессию (CoreSession), потребители — история сессий в
 * диагностике и детектор «подключено, но трафик не идёт».
 */
internal data class TrafficSample(
    val elapsedRealtimeMillis: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val connections: Int,
    /** Только соединения через прокси (итоги ядра ProxyTotal, патч 0006); null — данных нет. */
    val proxyUplinkTotal: Long? = null,
    val proxyDownlinkTotal: Long? = null,
    val proxyConnections: Int? = null,
)

/**
 * Взгляд детектора: только прокси, когда это известно. Прямой трафик (сайты мимо
 * VPN-сервера) иначе маскирует мёртвый прокси — входящие байты идут, а прокси молчит.
 */
internal fun TrafficSample.proxyView(): TrafficSample =
    if (proxyUplinkTotal == null || proxyDownlinkTotal == null || proxyConnections == null) {
        this
    } else {
        TrafficSample(elapsedRealtimeMillis, proxyUplinkTotal, proxyDownlinkTotal, proxyConnections)
    }
