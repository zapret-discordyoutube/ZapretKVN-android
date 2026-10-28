package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.ui.NetworkTransportSetting
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.updates.UpdateChannel

/**
 * Запись настроек из экранов. Настройки, влияющие на работающий туннель, перезапускают VPN
 * с указанной причиной (текст виден в диагностике).
 */
class ProfileSettingsActions internal constructor(
    private val settingsStore: UiSettingsStore,
    private val operations: ProfilesOperations,
    private val vpn: ProfilesVpnPort,
) {
    private fun apply(restartReason: String? = null, write: suspend () -> Unit) =
        operations.run(markBusy = false) {
            write()
            restartReason?.let(vpn::restartIfConnected)
        }

    fun setTheme(mode: ThemeMode) = apply { settingsStore.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = apply { settingsStore.setDynamicColor(enabled) }

    fun setRawEditorLineWrap(enabled: Boolean) = apply { settingsStore.setRawEditorLineWrap(enabled) }

    fun setHideServerAddresses(enabled: Boolean) = apply { settingsStore.setHideServerAddresses(enabled) }

    fun setSubscriptionAutoUpdateHours(hours: Int) = apply { settingsStore.setSubscriptionAutoUpdateHours(hours) }

    fun setServerSort(name: String) = apply { settingsStore.setServerSort(name) }

    fun setFavoriteServer(profileId: String, tag: String, favorite: Boolean) =
        apply { settingsStore.setFavoriteServer(profileId, tag, favorite) }

    fun setSlowServerSwitch(enabled: Boolean) = apply { settingsStore.setSlowServerSwitch(enabled) }

    fun setConnectOnLaunch(enabled: Boolean) = apply { settingsStore.setConnectOnLaunch(enabled) }

    fun setServiceCheck(id: ServiceId, enabled: Boolean) = apply { settingsStore.setServiceCheck(id, enabled) }

    fun setDnsMode(mode: DnsMode) = apply("Смена режима DNS") { settingsStore.setDnsMode(mode) }

    fun setProxyIpv4Only(enabled: Boolean) =
        apply("Смена IP-стратегии DNS") { settingsStore.setProxyIpv4Only(enabled) }

    fun setDnsOverrideEnabled(enabled: Boolean) =
        apply("Смена DNS-переопределения") { settingsStore.setDnsOverrideEnabled(enabled) }

    fun setDnsOverride(hostname: String, ipv4Address: String) =
        apply("Смена DNS-переопределения") { settingsStore.setDnsOverride(hostname, ipv4Address) }

    fun setUpdateChannel(channel: UpdateChannel) = apply { settingsStore.setUpdateChannel(channel) }

    fun setVpnHidingBlockLocalEndpoints(enabled: Boolean) =
        apply("Смена защиты от localhost-чекеров") {
            settingsStore.setVpnHidingBlockLocalEndpoints(enabled)
        }

    fun setVpnHidingNeutralSessionName(enabled: Boolean) =
        apply("Смена имени VPN-сессии") { settingsStore.setVpnHidingNeutralSessionName(enabled) }

    fun setVpnHidingTunMtuMode(mode: TunMtuMode) =
        apply("Смена MTU для скрытия VPN") { settingsStore.setVpnHidingTunMtuMode(mode) }

    fun setNetworkAutomationEnabled(enabled: Boolean) =
        apply { settingsStore.setNetworkAutomationEnabled(enabled) }

    fun setUseVpnOnNetwork(transport: NetworkTransportSetting, enabled: Boolean) =
        apply { settingsStore.setUseVpnOnNetwork(transport, enabled) }

    fun setPauseOnTrustedWifi(enabled: Boolean) = apply { settingsStore.setPauseOnTrustedWifi(enabled) }

    fun addTrustedWifi(ssid: String) = apply { settingsStore.addTrustedWifi(ssid) }

    fun removeTrustedWifi(ssid: String) = apply { settingsStore.removeTrustedWifi(ssid) }
}
