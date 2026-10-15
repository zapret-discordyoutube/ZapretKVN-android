package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.NetworkAutomationDecision
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.NetworkPauseReason
import io.github.zapretkvn.android.network.NetworkRestartDecision
import io.github.zapretkvn.android.network.NetworkRestartPolicy
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.isSettledForConnect
import io.github.zapretkvn.android.network.policyKey
import io.github.zapretkvn.android.network.userMessage
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Операции актора, нужные автоматике по сети. */
internal interface NetworkAutomationOps : RuntimeActorOps {
    /**
     * Останавливает попытки и операции, отцепляет и закрывает сессии, а затем
     * зовёт [then] с новым поколением (в акторе, после закрытия).
     */
    fun suspendForPause(then: (token: Long) -> Unit)

    /** Пауза началась: жизненного цикла запуска больше нет, ошибки нет. */
    fun onPauseEntered()

    /** Сеть под сессией сменилась: прежние пробы и выводы о серверах устарели. */
    fun onUnderlyingNetworkReplaced()
}

/**
 * Автоматика по сети: пауза VPN на доверенном Wi-Fi / мобильной сети, возобновление,
 * перезапуск при смене сети и единый дебаунс этих решений.
 *
 * Состояние (настройки, разовое «подключить сейчас», пауза, окно смены сети,
 * дебаунс) меняется только в акторе; фоновые задачи лишь ждут и возвращаются
 * в актор через [postToActor].
 */
internal class NetworkAutomationCoordinator(
    private val scope: CoroutineScope,
    private val sessions: SessionSlot,
    private val controller: VpnController,
    private val host: RuntimeHost,
    private val ops: NetworkAutomationOps,
    private val monitor: () -> DefaultNetworkMonitor,
    private val readWifiSsid: () -> String?,
    private val postToActor: (() -> Unit) -> Unit,
    private val clock: () -> Long,
) {
    private data class PausedSession(
        val profileId: String,
        val updaterRouting: Boolean,
        val generation: Long,
        val reason: NetworkPauseReason,
    )

    var settings = NetworkAutomationSettings()
        private set

    /** Разовое «подключить сейчас» действует, пока не сменилась сеть. */
    var overrideIdentity: String? = null
        private set

    private var paused: PausedSession? = null
    @Volatile private var pausedObserver: AutoCloseable? = null
    private var networkChangeSince = 0L
    private var debounceJob: Job? = null

    val debouncing: Boolean get() = debounceJob?.isActive == true

    fun clearOverride() {
        overrideIdentity = null
    }

    /** Свободное завершение при уничтожении сервиса. */
    fun close() {
        runCatching { pausedObserver?.close() }
    }

    /** Попытка завершилась удачно: окно ожидания смены сети начинается заново. */
    fun resetChangeWindow() {
        networkChangeSince = 0L
    }

    fun onSettingsChanged(newSettings: NetworkAutomationSettings) {
        val changed = settings != newSettings
        settings = newSettings
        if (!changed) return
        sessions.active()?.let { session ->
            if (ops.phase == RuntimePhase.Running) requestPause(session)
            return
        }
        val current = paused ?: return
        when (val decision = decision(monitor().current, current.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> updatePauseReason(current, decision.reason)
            NetworkAutomationDecision.RunVpn -> onResume(0, manual = false)
            NetworkAutomationDecision.WaitForNetwork -> Unit
        }
    }

    // ---- Сеть под работающей сессией ----

    fun onSessionNetwork(session: CoreSession, state: UnderlyingNetworkState) {
        if (sessions.active() !== session) return
        controller.publishDiagnosticNetwork(session.generation, state)
        if (state.identity != session.networkPolicyKey.identity) {
            session.onNetworkChanged()
            // Новая сеть: прежние выводы о серверах и пробы «умной проверки» устарели.
            ops.onUnderlyingNetworkReplaced()
        }
        if (decision(state, session.updaterRouting) is NetworkAutomationDecision.PauseVpn) {
            debounce(debounceMillis(state)) { onPauseDue(session) }
            return
        }
        val now = clock()
        val waited = if (networkChangeSince == 0L) 0L else now - networkChangeSince
        val plan = NetworkRestartPolicy.plan(
            sessionBaseline = session.networkPolicyKey,
            observed = state.policyKey(),
            observedSettled = state.isSettledForConnect(),
            waitedMillis = waited,
        )
        if (plan.decision == NetworkRestartDecision.KeepSession) {
            cancelDebounce()
            return
        }
        if (networkChangeSince == 0L) networkChangeSince = now
        debounce(plan.debounceMillis) { onRestartDue(session) }
    }

    private fun onRestartDue(session: CoreSession) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        if (NetworkRestartPolicy.decide(session.networkPolicyKey, monitor().current.policyKey()) ==
            NetworkRestartDecision.KeepSession
        ) {
            return
        }
        ops.restart(
            profileId = session.profileId,
            reason = "Смена сети Android",
            startId = 0,
            updaterRouting = null,
            expectedGeneration = session.generation,
        )
    }

    private fun onPauseDue(session: CoreSession) {
        if (sessions.active() !== session || session.generation != controller.currentGeneration()) return
        requestPause(session)
    }

    // ---- Пауза и возобновление ----

    private fun requestPause(session: CoreSession) {
        val decision = decision(monitor().current, session.updaterRouting)
        if (decision !is NetworkAutomationDecision.PauseVpn) return
        ops.suspendForPause { token ->
            enterPause(token, session.profileId, session.updaterRouting, decision.reason)
        }
    }

    fun enterPause(
        generation: Long,
        profileId: String,
        updaterRouting: Boolean,
        reason: NetworkPauseReason,
    ) {
        clearPaused()
        val current = PausedSession(profileId, updaterRouting, generation, reason)
        paused = current
        ops.phase = RuntimePhase.Paused
        ops.onPauseEntered()
        controller.publish(generation, VpnConnectionState.Paused(profileId, reason.userMessage()))
        host.showForeground(ForegroundState.Paused)
        pausedObserver = monitor().observe { state -> postToActor { onPausedNetwork(state) } }
        onPausedNetwork(monitor().current)
    }

    private fun onPausedNetwork(state: UnderlyingNetworkState) {
        val current = paused ?: return
        when (val decision = decision(state, current.updaterRouting)) {
            is NetworkAutomationDecision.PauseVpn -> {
                cancelDebounce()
                updatePauseReason(current, decision.reason)
            }
            NetworkAutomationDecision.WaitForNetwork -> cancelDebounce()
            NetworkAutomationDecision.RunVpn -> debounce(debounceMillis(state)) { onResumeDue(current.generation) }
        }
    }

    private fun updatePauseReason(current: PausedSession, reason: NetworkPauseReason) {
        if (reason == current.reason) return
        paused = current.copy(reason = reason)
        controller.publish(current.generation, VpnConnectionState.Paused(current.profileId, reason.userMessage()))
        host.showForeground(ForegroundState.Paused)
    }

    private fun onResumeDue(generation: Long) {
        if (paused?.generation != generation) return
        onResume(0, manual = false)
    }

    fun onResume(startId: Int, manual: Boolean) {
        val current = paused ?: return
        if (!manual &&
            decision(monitor().current, current.updaterRouting) != NetworkAutomationDecision.RunVpn
        ) {
            return
        }
        clearPaused()
        if (manual) overrideIdentity = monitor().current.identity
        ops.restart(
            profileId = current.profileId,
            reason = if (manual) "Подключение до смены сети" else "Автоматическое подключение по сети",
            startId = startId,
            updaterRouting = current.updaterRouting,
        )
    }

    /** @return профиль приостановленной сессии, если она была. */
    fun clearPaused(): String? {
        val current = paused
        paused = null
        runCatching { pausedObserver?.close() }
        pausedObserver = null
        if (current != null && ops.phase == RuntimePhase.Paused) ops.phase = RuntimePhase.Idle
        return current?.profileId
    }

    // ---- Правила и дебаунс ----

    private fun decision(state: UnderlyingNetworkState, updaterRouting: Boolean): NetworkAutomationDecision {
        val decision = NetworkAutomationRules.decide(state, updaterRouting, settings, overrideIdentity, readWifiSsid)
        if (overrideIdentity != null && state.identity != overrideIdentity) {
            overrideIdentity = null
        }
        return decision
    }

    private fun debounceMillis(state: UnderlyingNetworkState): Long {
        val now = clock()
        val waited = if (networkChangeSince == 0L) 0L else now - networkChangeSince
        if (networkChangeSince == 0L) networkChangeSince = now
        return NetworkAutomationRules.debounceMillis(state, waited)
    }

    /**
     * Единственный дебаунс сети: перезапуск, пауза и возобновление по правилам
     * сети не бывают активны одновременно, новое событие заменяет старое.
     */
    private fun debounce(millis: Long, then: () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(millis)
            postToActor(then)
        }
    }

    fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
        networkChangeSince = 0L
    }
}
