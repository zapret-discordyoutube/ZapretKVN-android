package io.github.zapretkvn.android.slots.runtime

import io.github.zapretkvn.android.engines.failover.SlowSwitchSnapshot
import io.github.zapretkvn.android.slots.ForeignServers
import io.github.zapretkvn.android.slots.SlotCandidates
import io.github.zapretkvn.android.slots.SlotCatalog
import io.github.zapretkvn.android.slots.SlotFailoverDecision
import io.github.zapretkvn.android.slots.SlotFailoverPolicy
import io.github.zapretkvn.android.slots.SlotIds
import io.github.zapretkvn.android.slots.SlotMove
import io.github.zapretkvn.android.slots.SlotPlanner
import io.github.zapretkvn.android.diagnostics.RuntimeLog
import io.github.zapretkvn.android.slots.SlotSelectors
import io.github.zapretkvn.android.slots.youtube.SlotSwitchContext
import io.github.zapretkvn.android.vpn.SlotState
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.runtime.CoreSession
import io.github.zapretkvn.android.vpn.runtime.PathHint
import io.github.zapretkvn.android.vpn.runtime.PathSupervisor
import io.github.zapretkvn.android.vpn.runtime.ServerSwitcher
import io.github.zapretkvn.networkbootstrap.ServerNameRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The runtime side of the service slots (docs/SERVICE_SLOTS.md): where each slot sits now, failover of a slot server on
 * repeated failures, the user's pins, the cards on Home, and the doorway the YouTube slot's episode uses. The decisions
 * are in the pure [SlotFailoverPolicy]; this class only executes them with the selector commands.
 *
 * Owned by the runtime actor: the state is touched only there. The one exception is [moveSequence], read by the command
 * coroutine that runs on the IO dispatcher.
 */
internal class SlotCoordinator(
    private val scope: CoroutineScope,
    private val controller: VpnController,
    private val switcher: ServerSwitcher,
    private val host: SlotRuntimeHost,
    private val clock: () -> Long,
) {
    /** Failover of the slots; exists while the session has slots. Separate from the main supervisor on purpose. */
    private var failover: SlotFailoverPolicy? = null

    /** The user's pins (slot id to server tag); they arrive as a message and apply to the running session at once. */
    private var pins: Map<String, String> = emptyMap()

    /** Last command number per slot: written when a command is created, read by the command itself. */
    private val moveSequence = ConcurrentHashMap<String, Long>()

    /** The stored pins (profile-qualified) as runtime tags of this session; a pin whose server is gone is dropped. */
    private fun runtimePins(session: CoreSession): Map<String, String> =
        ForeignServers.runtimePins(pins, session.profileId, session.slotPlan?.foreign.orEmpty())

    /** Server a slot sits on now, or null when the session has no such slot. */
    fun serverOf(slotId: String): String? = failover?.serverOf(slotId)

    /** The session became active: build the policy from its plan and show the cards. */
    fun onSessionActivated(session: CoreSession) {
        failover = session.slotPlan?.let { plan ->
            val initial = plan.assignments.mapNotNull { a -> a.serverId?.let { a.slotId to it } }.toMap()
            val servers = plan.specs.flatMap { it.members }.distinct()
            SlotFailoverPolicy(
                SlotCatalog.withPins(runtimePins(session)),
                servers,
                initial,
                recentlyFailed = { host.slotServerHealth(session).failed },
                alive = { host.slotServerHealth(session).alive },
                proven = {
                    mapOf(
                        SlotIds.YOUTUBE to servers.filter {
                            RuntimeLog.youtubeProven(it, SlotPlanner.YOUTUBE_PROVEN_BYTES_PER_SECOND)
                        }.toSet(),
                    )
                },
            ) { clock() }
        }
        publish(session)
    }

    /**
     * Failure hints of a server a slot sits on. The main selector is not touched: its supervisor probes through the
     * main server and cannot judge a slot's server, so a slot server is judged by repetition ([SlotFailoverPolicy]).
     */
    fun onHints(session: CoreSession, hints: List<PathHint>, mainServer: String?) {
        val policy = failover ?: return
        for (hint in hints) {
            // A server that rejects our credentials or certificate is not "slow": a slot leaves it at the first hint
            // (the main selector ends the session instead, see PathSupervisor; a slot has no session to end).
            val security = hint.code in PathSupervisor.SECURITY_FAILURES
            if (!security && hint.code !in PathSupervisor.SWITCHABLE_FAILURES) continue
            val decision = policy.onFailureHint(hint.outboundTag, mainServer, host.mainDeadServers(), immediate = security)
            // Failed connections (not just a slow DNS) make the server «не отвечает» for the next start too.
            if (decision is SlotFailoverDecision.Move && !security) host.recordUnreachable(session, decision.failedServer)
            apply(session, policy, hint.code.name, decision)
        }
    }

    /**
     * The own DNS of these slots timed out. A dead slot server often shows only this: the client retries quietly and
     * the log has no connect errors to count (field case 2026-11-14: a pinned YouTube server stopped answering and
     * every YouTube lookup hung for 100 s without a single move).
     */
    fun onSlotDnsFailures(session: CoreSession, slotIds: Set<String>, mainServer: String?) {
        val policy = failover ?: return
        for (slotId in slotIds) {
            apply(session, policy, "DNS слота", policy.onSlotDnsFailure(slotId, mainServer, host.mainDeadServers()))
        }
    }

    private fun apply(session: CoreSession, policy: SlotFailoverPolicy, what: String, decision: SlotFailoverDecision) {
        when (decision) {
            is SlotFailoverDecision.Ignore -> Unit
            is SlotFailoverDecision.Move -> {
                // Any reason counts, DNS included: the slot's DNS goes through its server alone, so a server that fails
                // it is no place for the slot for the next hour, whatever it does for ordinary traffic.
                host.markSlotUnusable(session, decision.failedServer)
                host.timeline(
                    "${host.serverName(decision.failedServer)}: ${decision.hints} отказов за минуту ($what), " +
                        "слоты уходят: ${decision.moves.joinToString { "${title(it.slotId)} → ${host.serverName(it.to)}" }}.",
                )
                // The new server's name must resolve before the command.
                ServerNameRegistry.useForSlots(policy.slotServers())
                publish(session)
                decision.moves.forEach { move -> move(session, policy, move) }
            }
        }
    }

    /**
     * The user pinned a server for a slot or removed the pin. The running session moves the slot at once with the
     * slot selector's command, without a restart; a removed pin moves nothing.
     */
    fun onPins(session: CoreSession?, newPins: Map<String, String>) {
        pins = newPins
        session ?: return
        val policy = failover ?: return
        if (!host.isCurrent(session)) return
        val specs = session.slotPlan?.specs.orEmpty().associateBy { it.slotId }
        val moves = policy.setPins(runtimePins(session)) { slotId -> specs[slotId]?.members.orEmpty() }
        if (moves.isEmpty()) return
        host.timeline("Закреплено вручную: ${moves.joinToString { "${title(it.slotId)} → ${host.serverName(it.to)}" }}.")
        ServerNameRegistry.useForSlots(policy.slotServers())
        publish(session)
        moves.forEach { move -> move(session, policy, move) }
    }

    private fun move(session: CoreSession, policy: SlotFailoverPolicy, move: SlotMove) {
        // Commands run on the IO dispatcher, so launch order is not execution order: every command carries a number
        // per slot and an older one that was overtaken by a newer move does nothing (the newer command sets the core).
        val sequence = (moveSequence[move.slotId] ?: 0L) + 1
        moveSequence[move.slotId] = sequence
        scope.launch {
            if (moveSequence[move.slotId] != sequence) return@launch
            val failure = switcher.moveSlot(session, SlotSelectors.tag(move.slotId), move.to)
            host.postToActor {
                if (failure != null && failover === policy) {
                    policy.moveFailed(move)
                    ServerNameRegistry.useForSlots(policy.slotServers())
                    publish(session)
                    host.timeline(
                        "Слот ${title(move.slotId)}: команда на ${host.serverName(move.to)} не прошла, " +
                            "остаётся на ${host.serverName(move.from)}.",
                    )
                } else if (failure == null) {
                    host.timeline("Слот ${title(move.slotId)}: теперь через ${host.serverName(move.to)}.")
                }
            }
        }
    }

    /** The cards on Home: where each slot sits now. Actor only. */
    fun publish(session: CoreSession) {
        val policy = failover
        val states = if (policy == null) {
            emptyList()
        } else {
            val foreign = session.slotPlan?.foreign.orEmpty()
            session.slotPlan?.specs.orEmpty().mapNotNull { spec ->
                policy.serverOf(spec.slotId)?.let { runtimeTag ->
                    val other = foreign.firstOrNull { it.runtimeTag == runtimeTag }
                    SlotState(
                        slotId = spec.slotId,
                        title = title(spec.slotId),
                        serverTag = runtimeTag,
                        serverName = other?.ref?.tag ?: runtimeTag,
                        profileName = other?.profileName,
                        serverRef = ForeignServers.refOf(runtimeTag, session.profileId, foreign).encode(),
                    )
                }
            }
        }
        controller.publishSlots(session.generation, states)
    }

    private fun title(slotId: String): String =
        SlotCatalog.builtIn().firstOrNull { it.id == slotId }?.title ?: slotId

    /** The doorway of a slot's switching episode (YouTube) into the runtime state; every call goes through the actor. */
    fun switchContext(main: (CoreSession) -> String?): SlotSwitchContext =
        object : SlotSwitchContext {
            override suspend fun snapshot(session: CoreSession, slotId: String) = host.ask {
                if (!host.isCurrent(session)) return@ask null
                val policy = failover ?: return@ask null
                val spec = session.slotPlan?.specs?.firstOrNull { it.slotId == slotId } ?: return@ask null
                val currentId = policy.serverOf(slotId) ?: return@ask null
                val usedByOthers = policy.assignments().filterKeys { it != slotId }.values.toSet() + listOfNotNull(main(session))
                // The one-hour slot marks count here too: a replacement the slot has just left for DNS or connect
                // failures is no candidate for a trial (field case 2026-11-15: the only rescue attempt of the episode
                // went to a server that fails the slot's DNS, so the video never got a second try). Soft, like the rest.
                val health = host.slotServerHealth(session)
                val marked = health.failed
                SlowSwitchSnapshot(
                    currentId = currentId,
                    targets = SlotCandidates.targets(
                        members = spec.members,
                        planTargets = host.plannedTargets(session),
                        currentId = currentId,
                        usedByOthers = usedByOthers,
                        dead = policy.deadServers() + host.mainDeadServers() + marked,
                    ),
                    hints = host.latencyHints(session.profileId),
                    busy = host.busy(),
                    alive = health.alive,
                )
            }

            override suspend fun canTrial(session: CoreSession, slotId: String, currentId: String) = host.ask {
                host.isCurrent(session) && !host.busy() && failover?.serverOf(slotId) == currentId
            }

            override suspend fun place(session: CoreSession, slotId: String, serverId: String) = host.ask {
                val policy = failover ?: return@ask
                policy.place(slotId, serverId)
                publish(session)
                // The new server's name must resolve before the command (it follows).
                ServerNameRegistry.useForSlots(policy.slotServers())
            }

            override suspend fun placeOf(session: CoreSession, slotId: String) = host.ask {
                if (host.isCurrent(session)) failover?.serverOf(slotId) else null
            }

            override fun markLeft(session: CoreSession, serverId: String) {
                host.postToActor {
                    // The YouTube episode marks a server only because the video starved there: a softer reason.
                    if (host.isCurrent(session)) host.markSlotUnusable(session, serverId, video = true)
                }
            }

            override fun forgiveLeft(session: CoreSession, serverId: String) {
                host.postToActor {
                    if (host.isCurrent(session)) host.forgiveSlotMark(session, serverId)
                }
            }

            override fun announce(session: CoreSession, message: String, notificationDetail: String) =
                host.announce(session, message, notificationDetail)

            override fun timeline(message: String) = host.timeline(message)
        }
}
