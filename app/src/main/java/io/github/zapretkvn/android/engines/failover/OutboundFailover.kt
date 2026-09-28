package io.github.zapretkvn.android.engines.failover

import java.util.Locale

/**
 * One structural line of a sing-box core log that names a specific outbound,
 * e.g. `outbound/vless[Tokyo]: connection refused`. The protocol type and tag
 * are extracted here for every protocol; the caller decides how to classify the
 * message into a recoverable/terminal failure using its own taxonomy ("keys").
 */
internal data class TaggedOutboundLine(
    val outboundType: String,
    val outboundTag: String,
    val message: String,
)

internal object OutboundFailureLogParser {
    private val pattern =
        Regex("outbound/([^\\[\\r\\n]+)\\[([^]\\r\\n]+)]", RegexOption.IGNORE_CASE)

    fun all(messages: List<String>): List<TaggedOutboundLine> = messages.mapNotNull { message ->
        val match = pattern.find(message) ?: return@mapNotNull null
        val type = match.groupValues[1].trim().lowercase(Locale.ROOT)
        val tag = match.groupValues[2].trim()
        if (type.isBlank() || tag.isBlank()) null else TaggedOutboundLine(type, tag, message)
    }

    fun first(messages: List<String>): TaggedOutboundLine? = all(messages).firstOrNull()
}

/**
 * Server that automatic switching may select. Each protocol folds its own
 * validity rules into [valid]; the switching decisions live in
 * [io.github.zapretkvn.android.vpn.runtime.PathSupervisor].
 */
internal data class FailoverTarget(
    val id: String,
    /**
     * Whether this target may be selected as a replacement. Each protocol folds
     * its own validity rules (URI capability, runtime execution kind, …) into
     * this flag before handing the target to the engine.
     */
    val valid: Boolean,
    val maintenance: Boolean = false,
)
