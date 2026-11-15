package io.github.zapretkvn.android.slots.runtime

import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
import io.github.zapretkvn.android.slots.SlotServerHealth
import io.github.zapretkvn.android.vpn.runtime.CoreSession

/**
 * What the slot coordinator needs from the runtime actor. The coordinator owns the slots' state and decisions; this
 * is the narrow doorway to everything else (the actor queue, the log, the main selector's state), so the slot code does
 * not reach into the server switch coordinator.
 */
internal interface SlotRuntimeHost {
    /** The session is the active one and not being stopped. */
    fun isCurrent(session: CoreSession): Boolean

    /** Run [block] in the actor. */
    fun postToActor(block: () -> Unit)

    /** Run [block] in the actor and wait for its result. */
    suspend fun <T> ask(block: () -> T): T

    fun timeline(message: String)

    /** Display name of a server tag (redacted). */
    fun serverName(tag: String): String

    /** Servers the main supervisor confirmed dead. */
    fun mainDeadServers(): Set<String>

    /** Failover targets of the main plan, or null when the plan is unavailable. */
    fun plannedTargets(session: CoreSession): List<FailoverTarget>?

    fun latencyHints(profileId: String): Map<String, LatencyHint>

    /**
     * A slot left [serverId] because its connections failed: remember it as "не отвечает" (the latency store), so the
     * next start does not begin on it and the Servers tab says so.
     */
    fun recordUnreachable(session: CoreSession, serverId: String)

    /** What is known about the slot servers: failed ones (latency store or slot mark) and proven-alive ones. */
    fun slotServerHealth(session: CoreSession): SlotServerHealth

    /** A slot left [serverId] because it did not work for the slot: remember it for slot placement (not a ping row). */
    fun markSlotUnusable(session: CoreSession, serverId: String, video: Boolean = false)

    /** The last mark of [serverId] was a mistake (the video starved everywhere, not on it): put back what was before. */
    fun forgiveSlotMark(session: CoreSession, serverId: String)

    /** Another switching operation (manual, failover, smart check) is running. */
    fun busy(): Boolean

    /** Show [message] to the user and refresh the foreground notification. */
    fun announce(session: CoreSession, message: String, notificationDetail: String)
}
