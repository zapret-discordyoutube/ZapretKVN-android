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
    /** Избранные серверы всех профилей; читать через [FavoriteServers.of]. */
    val favoriteServers: Set<String> = emptySet(),
    /** Автообновление подписок: раз в столько часов; 0 — только вручную. */
    val subscriptionAutoUpdateHours: Int = SubscriptionAutoUpdate.DEFAULT_HOURS,
)

/** Как часто фоновое задание обновляет подписки. */
object SubscriptionAutoUpdate {
    const val DEFAULT_HOURS = 24
    val CHOICES = listOf(0, 6, 12, 24)

    /** Неизвестное значение (старая или испорченная запись) — по умолчанию, а не «никогда». */
    fun normalize(hours: Int?): Int = hours?.takeIf { it in CHOICES } ?: DEFAULT_HOURS

    fun title(hours: Int): String = when (hours) {
        0 -> "Только вручную"
        24 -> "Раз в сутки"
        else -> "Каждые $hours ч"
    }

    /** Подпись в шапке профиля: «автообновление раз в сутки». */
    fun shortLabel(hours: Int): String = when (hours) {
        0 -> "обновление вручную"
        24 -> "автообновление раз в сутки"
        else -> "автообновление каждые $hours ч"
    }
}

/**
 * Избранные серверы: «профиль + тег сервера». Тег — имя из подписки, поэтому звёздочка
 * переживает обновление подписки, пока сервер называется так же.
 */
object FavoriteServers {
    /** Сколько звёздочек храним всего: настройка не должна расти без предела. */
    const val LIMIT = 500
    private const val SEPARATOR = '\u001F'

    fun token(profileId: String, tag: String): String = "$profileId$SEPARATOR$tag"

    /** Теги избранных серверов профиля. */
    fun of(tokens: Set<String>, profileId: String?): Set<String> {
        if (profileId == null) return emptySet()
        val prefix = "$profileId$SEPARATOR"
        return tokens.filter { it.startsWith(prefix) }.mapTo(LinkedHashSet()) { it.removePrefix(prefix) }
    }

    fun toggle(tokens: Set<String>, profileId: String, tag: String, favorite: Boolean): Set<String> {
        val token = token(profileId, tag)
        return if (favorite) (tokens - token + token).toList().takeLast(LIMIT).toSet() else tokens - token
    }

    /** Звёздочки удалённых профилей не нужны. */
    fun retainProfiles(tokens: Set<String>, profileIds: Set<String>): Set<String> =
        tokens.filterTo(LinkedHashSet()) { it.substringBefore(SEPARATOR) in profileIds }
}

enum class NetworkTransportSetting {
    Wifi,
    Cellular,
    Ethernet,
    Other,
}
