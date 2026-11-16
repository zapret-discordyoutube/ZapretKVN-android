package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.vpn.ExternalIpStatus
import io.github.zapretkvn.android.vpn.TrafficSample
import io.github.zapretkvn.android.vpn.VpnSessionStats

/** In-memory, single-session traffic state. No timer, persistence or background polling. */
internal class SessionTrafficAccumulator(
    private val capacity: Int = 60,
) {
    private var generation: Long = Long.MIN_VALUE
    private val samples = ArrayDeque<TrafficSample>(capacity)

    var value: VpnSessionStats = VpnSessionStats()
        private set

    init {
        require(capacity > 0)
    }

    fun start(
        generation: Long,
        profileId: String,
        connectedAtEpochMillis: Long,
    ): VpnSessionStats {
        this.generation = generation
        samples.clear()
        value = VpnSessionStats(
            profileId = profileId,
            connectedAtEpochMillis = connectedAtEpochMillis,
        )
        return value
    }

    fun stop(): VpnSessionStats {
        generation = Long.MIN_VALUE
        samples.clear()
        value = VpnSessionStats()
        return value
    }

    fun setStatusStreamActive(generation: Long, active: Boolean): VpnSessionStats? {
        if (generation != this.generation) return null
        value = value.copy(statusStreamActive = active)
        return value
    }

    fun updateTraffic(
        generation: Long,
        uploadDelta: Long,
        downloadDelta: Long,
        uploadTotal: Long,
        downloadTotal: Long,
    ): VpnSessionStats? {
        if (generation != this.generation || !value.statusStreamActive) return null
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(
            TrafficSample(
                uploadBytesPerSecond = uploadDelta.coerceAtLeast(0),
                downloadBytesPerSecond = downloadDelta.coerceAtLeast(0),
            ),
        )
        value = value.copy(
            uploadTotalBytes = uploadTotal.coerceAtLeast(0),
            downloadTotalBytes = downloadTotal.coerceAtLeast(0),
            samples = samples.toList(),
        )
        return value
    }

    fun beginExternalIp(generation: Long): VpnSessionStats? {
        if (generation != this.generation) return null
        value = value.copy(externalIpStatus = ExternalIpStatus.Loading)
        return value
    }

    /**
     * null — запрос не удался: прежний адрес (если был) остаётся, статус становится Failed,
     * а при [hiddenByServer] (туннель жив, сервис IP закрыт узлом) — HiddenByServer без адреса.
     */
    fun updateExternalIp(generation: Long, externalIp: String?, hiddenByServer: Boolean = false): VpnSessionStats? {
        if (generation != this.generation) return null
        value = when {
            externalIp != null ->
                value.copy(externalIp = externalIp, externalIpStatus = ExternalIpStatus.NotRequested)
            hiddenByServer -> value.copy(externalIp = null, externalIpStatus = ExternalIpStatus.HiddenByServer)
            else -> value.copy(externalIpStatus = ExternalIpStatus.Failed)
        }
        return value
    }

    fun clearConnectionIdentity(generation: Long): VpnSessionStats? {
        if (generation != this.generation) return null
        value = value.copy(externalIp = null, externalIpStatus = ExternalIpStatus.NotRequested)
        return value
    }
}
