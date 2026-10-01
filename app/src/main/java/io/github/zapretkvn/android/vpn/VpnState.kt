package io.github.zapretkvn.android.vpn

sealed interface VpnConnectionState {
    data object Stopped : VpnConnectionState
    data class Starting(
        val profileId: String,
        val message: String,
        val updaterRouting: Boolean = false,
    ) : VpnConnectionState
    data class Connected(
        val profileId: String,
        val profileName: String,
        val connectedAtEpochMillis: Long,
        val updaterRouting: Boolean = false,
    ) : VpnConnectionState
    data class Paused(
        val profileId: String,
        val message: String,
    ) : VpnConnectionState
    data class Stopping(val profileId: String?) : VpnConnectionState

    /**
     * Попытка провалилась транзиентно, сервис жив и восстанавливается сам.
     * [code] — код провалившейся попытки, [attempt] и [maxAttempts] показывают
     * пользователю, что ожидание конечно.
     */
    data class Reconnecting(
        val profileId: String,
        val message: String,
        val code: String = "",
        val attempt: Int = 1,
        val maxAttempts: Int = 1,
        val updaterRouting: Boolean = false,
    ) : VpnConnectionState
    data class Error(
        val message: String,
        val code: String = "",
        val technicalDetail: String? = null,
    ) : VpnConnectionState
}

data class RuntimeSelectorGroup(
    val tag: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val items: List<RuntimeOutboundItem>,
    val primary: Boolean = false,
    val probeProgress: LatencyProbeProgress? = null,
) {
    val outbounds: List<String>
        get() = items.map(RuntimeOutboundItem::tag)
}

data class RuntimeOutboundItem(
    val tag: String,
    val type: String,
    val endpoint: String?,
    /** ICMP Echo RTT до адреса endpoint по основной сети Android. */
    val icmp: LatencyProbeState = LatencyProbeState.NotTested,
    /** HTTPS HEAD через outbound; не является ICMP до адреса сервера. */
    val relay: LatencyProbeState = LatencyProbeState.NotTested,
)

data class LatencySample(
    val millis: Int,
    val measuredAtEpochMillis: Long,
    val networkIdentity: String?,
)

enum class LatencyFailure {
    NoResponse,
    Dns,
    Failed,
}

enum class LatencyUnsupportedReason {
    MissingEndpoint,
    NestedGroup,
}

sealed interface LatencyProbeState {
    data object NotTested : LatencyProbeState
    data class Running(val previous: LatencySample?) : LatencyProbeState
    data class Success(val sample: LatencySample) : LatencyProbeState
    data class Failed(
        val reason: LatencyFailure,
        val previous: LatencySample? = null,
        /** Когда проба не удалась; известно для сохранённых между запусками результатов. */
        val failedAtEpochMillis: Long? = null,
    ) : LatencyProbeState
    data class Unsupported(val reason: LatencyUnsupportedReason) : LatencyProbeState
    data class Stale(val sample: LatencySample) : LatencyProbeState
}

data class LatencyProbeProgress(
    val requestId: Long,
    val networkIdentity: String,
    val relayCompleted: Int,
    val relayTotal: Int,
    val icmpCompleted: Int,
    val icmpTotal: Int,
    val running: Boolean,
)

internal fun LatencyProbeState.lastSample(): LatencySample? = when (this) {
    LatencyProbeState.NotTested,
    is LatencyProbeState.Unsupported,
    -> null
    is LatencyProbeState.Running -> previous
    is LatencyProbeState.Success -> sample
    is LatencyProbeState.Failed -> previous
    is LatencyProbeState.Stale -> sample
}

internal fun LatencyProbeState.markStale(): LatencyProbeState =
    lastSample()?.let(LatencyProbeState::Stale) ?: when (this) {
        // Проба оборвана сменой сети, прежнего замера нет: без этого строка
        // навсегда оставалась «Проверяется», хотя раунда уже не существует.
        is LatencyProbeState.Running -> LatencyProbeState.NotTested
        else -> this
    }

/**
 * Порядок «быстрые сверху»: сначала серверы с известной задержкой (Relay HTTPS,
 * иначе ICMP) по возрастанию, затем остальные в исходном порядке.
 */
internal fun List<RuntimeOutboundItem>.sortedByLatency(): List<RuntimeOutboundItem> =
    sortedWith(
        compareBy<RuntimeOutboundItem> { it.latencyRank() == null }
            .thenBy { it.latencyRank() ?: 0 },
    )

/** Те же серверы в порядке [tags]; не упомянутые — в конце, в исходном порядке. */
internal fun List<RuntimeOutboundItem>.inOrderOf(tags: List<String>): List<RuntimeOutboundItem> {
    val position = tags.withIndex().associate { (index, tag) -> tag to index }
    return sortedBy { position[it.tag] ?: Int.MAX_VALUE }
}

private fun RuntimeOutboundItem.latencyRank(): Int? = relay.knownMillis() ?: icmp.knownMillis()

private fun LatencyProbeState.knownMillis(): Int? = when (this) {
    is LatencyProbeState.Success -> sample.millis
    is LatencyProbeState.Stale -> sample.millis
    is LatencyProbeState.Running -> previous?.millis
    else -> null
}

internal fun LatencyProbeState.restoreAfterCancellation(): LatencyProbeState = when (this) {
    is LatencyProbeState.Running -> previous
        ?.let(LatencyProbeState::Success)
        ?: LatencyProbeState.NotTested
    else -> this
}

internal fun LatencyProbeState.withFreshness(
    nowEpochMillis: Long,
    networkIdentity: String?,
): LatencyProbeState = when (this) {
    is LatencyProbeState.Success -> if (
        nowEpochMillis - sample.measuredAtEpochMillis >= LATENCY_FRESHNESS_MILLIS ||
        sample.networkIdentity != null && sample.networkIdentity != networkIdentity
    ) {
        LatencyProbeState.Stale(sample)
    } else {
        this
    }
    else -> this
}

internal fun RuntimeOutboundItem.withRelayHistory(
    testedAtEpochSeconds: Long,
    delayMillis: Int,
): RuntimeOutboundItem = copy(
    relay = if (testedAtEpochSeconds > 0L && delayMillis >= 0) {
        LatencyProbeState.Success(
            LatencySample(
                millis = delayMillis,
                measuredAtEpochMillis = testedAtEpochSeconds * 1_000L,
                networkIdentity = null,
            ),
        )
    } else {
        LatencyProbeState.NotTested
    },
)

internal const val LATENCY_FRESHNESS_MILLIS = 5 * 60 * 1_000L

data class TrafficSample(
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

data class VpnSessionStats(
    val profileId: String? = null,
    val connectedAtEpochMillis: Long? = null,
    val externalIp: String? = null,
    val uploadTotalBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val samples: List<TrafficSample> = emptyList(),
    val statusStreamActive: Boolean = false,
)
