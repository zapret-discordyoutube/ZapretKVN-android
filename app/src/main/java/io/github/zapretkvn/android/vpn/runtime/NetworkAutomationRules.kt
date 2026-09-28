package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.network.NetworkAutomationDecision
import io.github.zapretkvn.android.network.NetworkAutomationPolicy
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.NetworkRestartPolicy
import io.github.zapretkvn.android.network.UnderlyingNetworkState
import io.github.zapretkvn.android.network.isSettledForConnect

/** Правила паузы VPN по сети: чистые функции поверх [NetworkAutomationPolicy]. */
internal object NetworkAutomationRules {
    /**
     * Сеть обновлений приложения никогда не ставится на паузу; разовое
     * «подключить сейчас» действует, пока не сменилась сеть. SSID читается
     * только если он нужен правилу доверенных Wi-Fi.
     */
    fun decide(
        state: UnderlyingNetworkState,
        updaterRouting: Boolean,
        settings: NetworkAutomationSettings,
        overrideIdentity: String?,
        readWifiSsid: () -> String?,
    ): NetworkAutomationDecision {
        if (updaterRouting) return NetworkAutomationDecision.RunVpn
        if (overrideIdentity != null && state.identity == overrideIdentity) return NetworkAutomationDecision.RunVpn
        val wifiSsid = state.wifiSsid ?: if (
            state.transport == "wifi" && settings.pauseOnTrustedWifi && settings.trustedWifiSsids.isNotEmpty()
        ) {
            runCatching(readWifiSsid).getOrNull()
        } else {
            null
        }
        return NetworkAutomationPolicy.decide(
            settings = settings,
            networkAvailable = state.network != null,
            transport = state.transport,
            wifiSsid = wifiSsid,
        )
    }

    /** Зрелая сеть или затянувшееся ожидание — короткий дебаунс, иначе длинный. */
    fun debounceMillis(state: UnderlyingNetworkState, waitedMillis: Long): Long =
        if (state.isSettledForConnect() || waitedMillis >= NetworkRestartPolicy.MAX_SETTLING_WAIT_MILLIS) {
            NetworkRestartPolicy.SETTLED_DEBOUNCE_MILLIS
        } else {
            NetworkRestartPolicy.SETTLING_DEBOUNCE_MILLIS
        }
}
