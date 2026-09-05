package io.github.zapretkvn.android.network

import io.github.zapretkvn.networkbootstrap.PrivateDnsMode
import io.github.zapretkvn.networkbootstrap.UnderlyingNetworkState

internal data class UnderlyingPolicyKey(
    val identity: String?,
    val captivePortal: Boolean,
    val strictPrivateDns: Boolean,
    val strictPrivateDnsServerName: String?,
    val strictPrivateDnsReady: Boolean,
)

internal fun UnderlyingNetworkState.policyKey() = UnderlyingPolicyKey(
    identity = identity,
    captivePortal = captivePortal,
    strictPrivateDns = privateDnsMode == PrivateDnsMode.Strict,
    strictPrivateDnsServerName = privateDnsServerName.takeIf { privateDnsMode == PrivateDnsMode.Strict },
    strictPrivateDnsReady = privateDnsMode != PrivateDnsMode.Strict || (privateDnsActive && validated),
)

internal enum class NetworkRestartDecision {
    KeepSession,
    DebounceRestart,
}

internal data class NetworkRestartPlan(
    val decision: NetworkRestartDecision,
    val debounceMillis: Long,
)

internal object NetworkRestartPolicy {
    /** Новая сеть уже настроена Android — перезапускаемся почти сразу. */
    const val SETTLED_DEBOUNCE_MILLIS = 750L

    /** Сеть ещё дозревает (DHCP, validation): ждать дешевле, чем упасть в NET-101. */
    const val SETTLING_DEBOUNCE_MILLIS = 3_000L

    /**
     * Потолок ожидания зрелости. Сети, которые Android никогда не пометит
     * validated, не должны навсегда оставлять сессию на мёртвом линке.
     */
    const val MAX_SETTLING_WAIT_MILLIS = 12_000L

    fun <T> decide(sessionBaseline: T, observed: T): NetworkRestartDecision =
        if (sessionBaseline == observed) {
            NetworkRestartDecision.KeepSession
        } else {
            NetworkRestartDecision.DebounceRestart
        }

    /**
     * [waitedMillis] — сколько прошло с первой незакрытой смены сети; каждое новое
     * событие перевзводит дебаунс, поэтому без потолка флапающий Wi‑Fi откладывал бы
     * перезапуск бесконечно.
     */
    fun <T> plan(
        sessionBaseline: T,
        observed: T,
        observedSettled: Boolean,
        waitedMillis: Long,
    ): NetworkRestartPlan {
        val decision = decide(sessionBaseline, observed)
        if (decision == NetworkRestartDecision.KeepSession) {
            return NetworkRestartPlan(decision, 0L)
        }
        val debounce = if (observedSettled || waitedMillis >= MAX_SETTLING_WAIT_MILLIS) {
            SETTLED_DEBOUNCE_MILLIS
        } else {
            SETTLING_DEBOUNCE_MILLIS
        }
        return NetworkRestartPlan(decision, debounce)
    }
}
