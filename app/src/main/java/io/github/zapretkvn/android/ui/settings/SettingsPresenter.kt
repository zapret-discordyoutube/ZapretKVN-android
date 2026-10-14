package io.github.zapretkvn.android.ui.settings

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.network.NetworkAutomationSettings
import io.github.zapretkvn.android.ui.ThemeMode
import io.github.zapretkvn.android.ui.UiSettings
import io.github.zapretkvn.android.updates.UpdateChannel
import io.github.zapretkvn.android.updates.UpdateState

/** Всё, что показывает главный список настроек. */
@Immutable
internal data class SettingsMainUi(
    val dnsSummary: String,
    val automationSummary: String,
    val slowServerSwitch: Boolean,
    val themeMode: ThemeMode,
    val themeSummary: String,
    val hideServerAddresses: Boolean,
    val dynamicColor: Boolean,
    val updateSummary: String,
    val aboutSummary: String,
    val advancedSummary: String,
)

internal fun buildSettingsMain(
    settings: UiSettings,
    updateSummary: String,
    appVersion: String,
): SettingsMainUi = SettingsMainUi(
    dnsSummary = dnsSummary(settings.dnsMode, settings.proxyIpv4Only),
    automationSummary = automationSummary(settings.networkAutomation),
    slowServerSwitch = settings.slowServerSwitch,
    themeMode = settings.themeMode,
    themeSummary = "Тема: ${settings.themeMode.title()}",
    hideServerAddresses = settings.hideServerAddresses,
    dynamicColor = settings.dynamicColor,
    updateSummary = updateSummary,
    aboutSummary = "Zapret KVN $appVersion",
    advancedSummary = ADVANCED_SUMMARY,
)

internal const val ADVANCED_SUMMARY = "Скрытие VPN, диагностика"

internal fun ThemeMode.title(): String = when (this) {
    ThemeMode.System -> "Как в системе"
    ThemeMode.Light -> "Светлая"
    ThemeMode.Dark -> "Тёмная"
    ThemeMode.Amoled -> "AMOLED (чёрная)"
}

internal fun ThemeMode.description(): String = when (this) {
    ThemeMode.System -> "Следует настройкам Android; на Android 12+ цвета берутся из обоев."
    ThemeMode.Light -> "Светлый фон в любое время суток."
    ThemeMode.Dark -> "Тёмно-серый фон."
    ThemeMode.Amoled -> "Полностью чёрный фон: меньше расход заряда на OLED-экранах."
}

internal fun UpdateChannel.title(): String = when (this) {
    UpdateChannel.Stable -> "Stable"
    UpdateChannel.Beta -> "Beta"
}

internal fun DnsMode.title(): String = when (this) {
    DnsMode.Automatic -> "Автоматически"
    DnsMode.Android -> "DNS Android"
    DnsMode.Secure -> "DNS узла через VPN"
    DnsMode.FromJson -> "Из JSON"
}

internal fun DnsMode.description(): String = when (this) {
    DnsMode.Automatic ->
        "Сначала DNS профиля, затем DNS сервера, в последнюю очередь DNS Android. " +
            "Переход происходит только после подтверждённой ошибки."
    DnsMode.Android -> "Адреса сайтов определяет система: используются DNS и Private DNS вашего Android."
    DnsMode.Secure -> "Адреса сайтов определяет сам VPN-сервер: запросы идут внутри туннеля."
    DnsMode.FromJson -> "DNS из JSON профиля применяется как есть; если его там нет, используется DNS Android."
}

internal fun dnsSummary(mode: DnsMode, ipv4Only: Boolean): String = when (mode) {
    // Для «Из JSON» переключатель IPv4 не действует, не обещаем лишнего.
    DnsMode.FromJson -> mode.title()
    else -> "${mode.title()} · ${if (ipv4Only) "только IPv4" else "IPv4 и IPv6"}"
}

/** Как сказано в DNS-режимах: что делает «только IPv4» и когда он не работает. */
internal fun ipv4OnlyHint(mode: DnsMode): String = if (mode == DnsMode.FromJson) {
    "В режиме «Из JSON» не действует: DNS профиля применяется как есть."
} else {
    "Включено по умолчанию. Скрывает IPv6-адреса сайтов, когда DNS берётся у сервера или у Android. " +
        "Выключите, если нужны сайты только с IPv6."
}

internal fun dnsOverrideHint(mode: DnsMode): String = when (mode) {
    DnsMode.FromJson -> "Сохранено, но не применяется в режиме «Из JSON»."
    DnsMode.Automatic ->
        "Применяется только после автоматического перехода с DNS профиля на DNS сервера или DNS Android."
    else ->
        "Указанный сайт получает заданный IPv4-адрес до обращения к DNS. " +
            "Если приложение использует собственный защищённый DNS, правило может не сработать."
}

internal fun automationSummary(settings: NetworkAutomationSettings): String {
    if (!settings.enabled) return "Выключена"
    val enabled = buildList {
        if (settings.useVpnOnWifi) add("Wi‑Fi")
        if (settings.useVpnOnCellular) add("мобильная")
        if (settings.useVpnOnEthernet) add("Ethernet")
        if (settings.useVpnOnOther) add("другие")
    }
    val base = if (enabled.isEmpty()) "VPN везде на паузе" else "VPN: ${enabled.joinToString(", ")}"
    val trusted = if (
        settings.useVpnOnWifi &&
        settings.pauseOnTrustedWifi &&
        settings.trustedWifiSsids.isNotEmpty()
    ) {
        " · доверенных Wi‑Fi: ${settings.trustedWifiSsids.size}"
    } else {
        ""
    }
    return base + trusted
}

/** Строка про обновления в главном списке; зависит только от слова состояния. */
internal fun updateSummary(state: UpdateState, currentVersion: String): String = when (state) {
    is UpdateState.UpToDate -> "Версия $currentVersion · актуальна"
    is UpdateState.Available -> "Доступна версия ${state.candidate.metadata.versionName}"
    is UpdateState.Downloading -> "Загружается версия ${state.candidate.metadata.versionName}"
    is UpdateState.Ready -> "Версия ${state.candidate.metadata.versionName} готова к установке"
    is UpdateState.Installing -> "Устанавливается версия ${state.candidate.metadata.versionName}"
    is UpdateState.Checking -> "Версия $currentVersion · проверяем…"
    is UpdateState.Failure -> "Версия $currentVersion · не удалось проверить"
    UpdateState.Idle -> "Версия $currentVersion"
}

internal fun TunMtuMode.title(): String = when (this) {
    TunMtuMode.CoreDefault -> "По профилю"
    TunMtuMode.Normalize1500 -> "Нормализовать 1500"
}

internal fun TunMtuMode.description(): String = when (this) {
    TunMtuMode.CoreDefault ->
        "Используется MTU профиля; для WireGuard без tun.mtu — MTU сервера, " +
            "для остальных — значение по умолчанию у ядра."
    TunMtuMode.Normalize1500 ->
        "Включено по умолчанию: MTU туннеля ограничивается 1500. " +
            "Для WireGuard берётся меньшее из 1500 и MTU сервера. JSON профиля не меняется."
}

internal fun vpnHidingSummary(options: VpnHidingOptions): String = buildList {
    add(if (options.blockLocalEndpoints) "Локальные порты закрыты" else "Локальные порты открыты")
    add(if (options.tunMtuMode == TunMtuMode.Normalize1500) "MTU 1500" else "MTU по профилю")
    if (options.neutralSessionName) add("нейтральное имя")
}.joinToString(" · ")

/** Результат добавления доверенной сети: цвет сообщения зависит от вида, а не от текста. */
internal enum class WifiMessageKind { Success, Error }

internal data class WifiMessage(val kind: WifiMessageKind, val text: String) {
    companion object {
        fun added(ssid: String) = WifiMessage(WifiMessageKind.Success, "Сеть «$ssid» добавлена в доверенные.")

        fun nameUnavailable() = WifiMessage(
            WifiMessageKind.Error,
            "Android не показал имя текущей сети. Проверьте, что вы подключены к Wi‑Fi, " +
                "а доступ к точному местоположению и геолокация включены.",
        )

        fun permissionDenied() = WifiMessage(
            WifiMessageKind.Error,
            "Без доступа к местоположению Android не показывает имя сети. " +
                "Имя можно ввести вручную — правило для всех Wi‑Fi работает и без этого.",
        )
    }
}

internal const val LOCATION_PERMISSION_EXPLANATION =
    "Android разрешает читать имя Wi‑Fi только приложениям с доступом к местоположению. " +
        "Мы его нигде не сохраняем и не передаём."
