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
) {
    val outbounds: List<String>
        get() = items.map(RuntimeOutboundItem::tag)
}

data class RuntimeOutboundItem(
    val tag: String,
    val type: String,
    val endpoint: String?,
    val pingMillis: Int?,
    val pingMeasuredAtEpochSeconds: Long?,
)

data class TrafficSample(
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
)

data class VpnSessionStats(
    val profileId: String? = null,
    val connectedAtEpochMillis: Long? = null,
    val externalIp: String? = null,
    val pingMillis: Long? = null,
    val uploadTotalBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val samples: List<TrafficSample> = emptyList(),
    val statusStreamActive: Boolean = false,
)
