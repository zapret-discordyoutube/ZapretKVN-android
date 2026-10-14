package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.diagnostics.VpnErrorContext
import io.github.zapretkvn.android.network.probes.PersistedServerLatency
import io.github.zapretkvn.android.network.probes.primaryGroup
import io.github.zapretkvn.android.profiles.ProfileServerSummary
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.core.uimodel.CountryGuess
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.inferCountries
import io.github.zapretkvn.core.uimodel.protocolLabel
import io.github.zapretkvn.core.uimodel.protocolMonogram

/*
 * Сборка карточки сервера Главной из состояния VPN или из сохранённого профиля.
 * Чистые функции: HomeRoute в :app только подбирает входные данные.
 */

fun onlineServer(
    groups: List<RuntimeSelectorGroup>,
    hide: Boolean,
    hostCountries: Map<String, String>,
): HomeServerUi? {
    val group = groups.primaryGroup() ?: return null
    val item = group.items.firstOrNull { it.tag == group.selected } ?: return null
    val name = ServerName.parse(item.tag)
    val hint = inferCountries(
        groups.flatMap { g -> g.items.map { it.tag to it.endpoint } }.distinctBy { it.first },
        hostCountries,
    )[item.tag]
    return HomeServerUi(
        title = homeServerTitle(item.tag, name, hide),
        flag = name.flag ?: hint?.let(CountryGuess::flagOf),
        protocol = protocolLabel(item.type),
        monogram = protocolMonogram(item.type),
        modeLabel = groupModeLabel(group.type),
        latency = latencyBadge(item.relay) ?: latencyBadge(item.icmp),
        relay = item.relay,
        icmp = item.icmp,
        switchable = group.items.size > 1,
    )
}

fun offlineServer(
    summary: ProfileServerSummary?,
    latency: Map<String, PersistedServerLatency>,
    hide: Boolean,
    hostCountries: Map<String, String>,
): HomeServerUi? {
    if (summary == null || summary.serverCount == 0) return null
    val label = summary.selectedLabel ?: return null
    val option = summary.primaryGroup?.options?.firstOrNull { it.tag == label }
    val name = ServerName.parse(label)
    val saved = latency[label]?.displayState()
    val hint = inferCountries(
        summary.groups.flatMap { g -> g.options.map { it.tag to it.endpoint } }.distinctBy { it.first },
        hostCountries,
    )[label]
    return HomeServerUi(
        title = homeServerTitle(label, name, hide),
        flag = name.flag ?: hint?.let(CountryGuess::flagOf),
        protocol = option?.type?.let(::protocolLabel) ?: "",
        monogram = protocolMonogram(option?.type.orEmpty()),
        modeLabel = if (summary.switchable) "Серверов в профиле: ${summary.serverCount}" else "Единственный сервер",
        latency = latencyBadge(saved),
        relay = null,
        icmp = null,
        switchable = summary.switchable,
    )
}

/** Флаг и страна берутся из настоящего тега; скрывается только показываемый текст. */
private fun homeServerTitle(tag: String, name: ServerName, hide: Boolean): String {
    val masked = ScreenshotPrivacy.serverLabel(tag, hide)
    return if (masked != tag) masked else name.title
}

fun ProfileServerSummary?.errorContext(): VpnErrorContext {
    if (this == null) return VpnErrorContext()
    val selected = primaryGroup?.options?.firstOrNull { it.tag == selectedLabel }
    return VpnErrorContext(
        protocol = selected?.type?.takeIf(String::isNotBlank),
        hasOtherServers = switchable,
    )
}

