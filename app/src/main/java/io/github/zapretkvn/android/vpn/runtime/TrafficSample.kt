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
)
