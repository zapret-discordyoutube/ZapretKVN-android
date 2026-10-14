package io.github.zapretkvn.android.routing

enum class RoutingPreset(val title: String, val detail: String) {
    AllThroughVpn("Всё через VPN", "Все назначения выбранных приложений → VPN"),
    BypassLan("Обход LAN", "LAN → напрямую, остальное → VPN"),
    OnlySelectedSites("Только выбранные сайты", "Выбранные правила → VPN, остальное → напрямую"),
    RussiaDirect("Россия напрямую", "Россия и LAN → напрямую, остальное → VPN"),
    RussiaVpn("Россия через VPN", "Россия → VPN, LAN и остальное → напрямую"),
    Custom("Пользовательский", "Использовать правила настоящего JSON"),
}

enum class RoutingRuleAction(val title: String) {
    Proxy("Через VPN"),
    Direct("Напрямую"),
    Block("Блокировать"),
}

enum class RoutingMatchType(val title: String) {
    Domain("Точный домен"),
    DomainSuffix("Суффикс домена"),
    IpCidr("IP / CIDR"),
    DomainRuleSet("Domain rule-set"),
    IpRuleSet("IP rule-set"),
}

data class ManagedRoutingRule(
    val matchType: RoutingMatchType,
    val values: List<String>,
    val action: RoutingRuleAction,
    val outboundTag: String? = null,
)

data class RoutingInspection(
    val preset: RoutingPreset,
    val rules: List<ManagedRoutingRule>,
    val proxyTag: String?,
    val directTag: String?,
    val outboundTags: List<String>,
    val customRuleCount: Int,
    val remoteRuleSetCount: Int,
    val summary: String,
)

data class RoutingEditResult(
    val json: String,
    val inspection: RoutingInspection,
    val diff: String,
)
