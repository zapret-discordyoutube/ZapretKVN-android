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
 * Protocol-neutral failover engine.
 *
 * sing-box is the thin entry point and router; every proxy protocol is just a
 * consumer that supplies its own targets ("keys"). The switching machinery —
 * one automatic switch per failure episode, per-target cooldown, the stale-log
 * fence right after a commit — is identical for all of them, so it lives here
 * once instead of being duplicated per protocol.
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

internal sealed interface FailoverOutcome {
    data class Candidate(val target: FailoverTarget) : FailoverOutcome
    data object NoCompatibleTarget : FailoverOutcome
    data object StaleFailureIgnored : FailoverOutcome
    data object TransitionAlreadyInFlight : FailoverOutcome
    data object FailureAlreadyHandled : FailoverOutcome
    data object FailureNotRecoverable : FailoverOutcome
}

internal class OutboundFailoverCoordinator(
    private val monotonicMillis: () -> Long,
    private val cooldownMillis: Long = 300_000,
) {
    private val cooldownUntil = mutableMapOf<String, Long>()
    private var replacementInFlight = false
    private var replacementAttempted = false
    private var lastCommitAt = Long.MIN_VALUE
    var failureEpisodeId: Long = 0
        private set

    /**
     * @param recoverable whether the failure taxonomy of the calling protocol
     *   classifies this failure as eligible for an automatic switch.
     */
    fun chooseReplacement(
        failedId: String,
        recoverable: Boolean,
        orderedTargets: List<FailoverTarget>,
        ignoreStaleLogFence: Boolean = false,
    ): FailoverOutcome {
        if (!recoverable) return FailoverOutcome.FailureNotRecoverable
        if (replacementInFlight) return FailoverOutcome.TransitionAlreadyInFlight
        if (replacementAttempted) return FailoverOutcome.FailureAlreadyHandled
        val now = monotonicMillis()
        if (
            !ignoreStaleLogFence &&
            lastCommitAt != Long.MIN_VALUE &&
            now - lastCommitAt < STALE_LOG_FENCE_MILLIS
        ) {
            return FailoverOutcome.StaleFailureIgnored
        }
        val replacement = orderedTargets.firstOrNull { target ->
            target.id != failedId &&
                !target.maintenance &&
                target.valid &&
                cooldownUntil.getOrDefault(target.id, 0) <= now
        }
        failureEpisodeId++
        replacementAttempted = true
        cooldownUntil[failedId] = now + cooldownMillis
        if (replacement == null) return FailoverOutcome.NoCompatibleTarget
        replacementInFlight = true
        return FailoverOutcome.Candidate(replacement)
    }

    fun commitReplacement() {
        replacementInFlight = false
        replacementAttempted = false
        lastCommitAt = monotonicMillis()
    }

    fun failReplacement() {
        replacementInFlight = false
        // replacementAttempted intentionally stays true: one episode gets no
        // automatic second target.
    }

    fun automaticAttempted(): Boolean = replacementAttempted

    fun replacementInFlight(): Boolean = replacementInFlight

    fun onSessionReady() {
        replacementInFlight = false
        replacementAttempted = false
    }

    fun reset() {
        replacementInFlight = false
        replacementAttempted = false
        lastCommitAt = Long.MIN_VALUE
        // A brand-new connection lifecycle must reconsider every target. Without
        // this a node that failed in a previous session stays excluded for the
        // full cooldown, so a two-node profile reports "no compatible fallback"
        // on reconnect until the 5-minute window expires ("иногда чинится само").
        cooldownUntil.clear()
    }

    private companion object {
        const val STALE_LOG_FENCE_MILLIS = 2_000L
    }
}
