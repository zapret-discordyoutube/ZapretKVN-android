package io.github.zapretkvn.android.slots.runtime

import io.github.zapretkvn.android.engines.failover.FailoverTarget
import io.github.zapretkvn.android.engines.failover.LatencyHint
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

    /** Runtime tags of the servers that have a fresh «не отвечает» mark (the latency store). */
    fun recentlyFailedServers(session: CoreSession): Set<String>

    /** Another switching operation (manual, failover, smart check) is running. */
    fun busy(): Boolean

    /** Show [message] to the user and refresh the foreground notification. */
    fun announce(session: CoreSession, message: String, notificationDetail: String)
}
