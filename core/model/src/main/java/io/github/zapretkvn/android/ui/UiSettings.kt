package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.network.probes.services.ServiceCatalog
import io.github.zapretkvn.android.network.probes.services.ServiceId
import io.github.zapretkvn.android.updates.UpdateChannel

enum class ThemeMode {
    System,
    Light,
    Dark,
    Amoled,
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val activeProfileId: String? = null,
    val rawEditorLineWrap: Boolean = false,
    val hideServerAddresses: Boolean = true,
    val dnsMode: DnsMode = DnsMode.FromJson,
    val proxyIpv4Only: Boolean = true,
    val dnsOverride: DnsOverride = DnsOverride(),
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
    val vpnHiding: VpnHidingOptions = VpnHidingOptions(),
    val networkAutomation: NetworkAutomationSettings = NetworkAutomationSettings(),
    /** «Умная проверка»: переключать сервер при подтверждённой низкой скорости. */
    val slowServerSwitch: Boolean = true,
    /** Цвета обоев (Material You). По умолчанию — фирменная яркая палитра. */
    val dynamicColor: Boolean = false,
    /** Какие сервисы проверяет карточка «Доступность сервисов»; по умолчанию — набор каталога. */
    val serviceChecks: Set<ServiceId> = ServiceCatalog.defaultIds,
)

enum class NetworkTransportSetting {
    Wifi,
    Cellular,
    Ethernet,
    Other,
}
