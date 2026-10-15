package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.network.DefaultNetworkMonitor
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.isSettledForConnect
import io.github.zapretkvn.android.network.isUsableForConnect
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.VpnRecoveryDecision
import io.github.zapretkvn.android.vpn.VpnRecoveryPolicy
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Повтор подключения после отказа попытки: счётчики серий, решение
 * [VpnRecoveryPolicy], ожидание сети и перезапуск. Сервис остаётся жив между
 * попытками, а появление рабочей сети само поднимает VPN.
 *
 * Счётчики и задача ожидания меняются только в акторе; задача лишь ждёт и
 * возвращается в актор через [postToActor].
 */
internal class RecoveryCoordinator(
    private val scope: CoroutineScope,
    private val controller: VpnController,
    private val host: RuntimeHost,
    private val ops: RuntimeActorOps,
    private val monitor: () -> DefaultNetworkMonitor,
    private val postToActor: (() -> Unit) -> Unit,
) {
    private var attempt = 0
    private var totalAttempts = 0
    private var attemptNetworkIdentity: String? = null
    private var job: Job? = null

    val active: Boolean get() = job?.isActive == true

    /** Сеть, на которой началась текущая попытка подключения. */
    fun onAttemptNetwork(identity: String?) {
        attemptNetworkIdentity = identity
    }

    fun resetCounters() {
        attempt = 0
        totalAttempts = 0
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun decide(profileId: String, failure: VpnConnectionState.Error): VpnRecoveryDecision {
        if (ops.stopInProgress || profileId.isBlank()) return VpnRecoveryDecision.Terminal
        val startedOn = attemptNetworkIdentity
        return VpnRecoveryPolicy.decide(
            failureCode = failure.code,
            attempt = attempt,
            totalAttempts = totalAttempts,
            networkChangedDuringAttempt = startedOn != null && startedOn != monitor().current.identity,
        )
    }

    /**
     * Держит сервис живым между попытками. Наблюдатель сети переживает отказ,
     * поэтому появление рабочей сети само поднимает VPN.
     */
    fun schedule(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        decision: VpnRecoveryDecision,
    ) {
        attempt += 1
        totalAttempts += 1
        val attemptNumber = attempt
        val failedOn = attemptNetworkIdentity
        ops.phase = RuntimePhase.Recovering
        controller.publishRecoverableFailure(token, failure)
        cancel()
        job = scope.launch {
            if (decision is VpnRecoveryDecision.RetryAfter) {
                publishRecovering(token, profileId, updaterRouting, failure,
                    "Повтор подключения через ${decision.delayMillis / 1_000} с", attemptNumber)
                host.showForeground(ForegroundState.Retrying)
                delay(decision.delayMillis)
            }
            if (!monitor().current.isSettledForConnect()) {
                publishRecovering(token, profileId, updaterRouting, failure, "Ожидание сети Android", attemptNumber)
                host.showForeground(ForegroundState.AwaitingNetwork)
            }
            val ready = awaitRecoveryNetwork()
            postToActor { onDue(token, profileId, updaterRouting, failedOn, ready.identity) }
        }
    }

    private fun onDue(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failedOnIdentity: String?,
        readyIdentity: String?,
    ) {
        if (token != controller.currentGeneration() || ops.stopInProgress) return
        job = null
        if (failedOnIdentity != null && readyIdentity != failedOnIdentity) {
            attempt = 0
        }
        ops.restart(
            profileId = profileId,
            reason = "Автоматическое переподключение",
            startId = 0,
            updaterRouting = updaterRouting,
            expectedGeneration = token,
            resetRecovery = false,
        )
    }

    /**
     * Ждёт сеть столько, сколько нужно: выключенный на ночь Wi-Fi не должен
     * превращаться в ошибку. Это ожидание события ConnectivityManager, без
     * таймера и опроса; границы применяются к числу попыток, а не к простою.
     */
    private suspend fun awaitRecoveryNetwork(): UnderlyingNetworkState {
        while (true) {
            awaitNetworkStage(RECOVERY_SETTLE_WAIT_MILLIS) { it.isSettledForConnect() }?.let { return it }
            awaitNetworkStage(RECOVERY_USABLE_WAIT_MILLIS) { it.isUsableForConnect() }?.let { return it }
        }
    }

    private suspend fun awaitNetworkStage(
        timeoutMillis: Long,
        accept: (UnderlyingNetworkState) -> Boolean,
    ): UnderlyingNetworkState? = try {
        monitor().awaitUnderlying(timeoutMillis, accept)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: BootstrapFailureException) {
        null
    }

    private fun publishRecovering(
        token: Long,
        profileId: String,
        updaterRouting: Boolean,
        failure: VpnConnectionState.Error,
        message: String,
        attempt: Int,
    ) {
        controller.publish(
            token,
            VpnConnectionState.Reconnecting(
                profileId = profileId,
                message = message,
                code = failure.code,
                attempt = attempt,
                maxAttempts = VpnRecoveryPolicy.MAX_ATTEMPTS,
                updaterRouting = updaterRouting,
            ),
        )
    }

    private companion object {
        /** Бюджеты ступеней ожидания сети при восстановлении; само ожидание не ограничено. */
        const val RECOVERY_SETTLE_WAIT_MILLIS = 30_000L
        const val RECOVERY_USABLE_WAIT_MILLIS = 180_000L
    }
}
