package io.github.zapretkvn.android.vpn.runtime

/**
 * Причина перезапуска (текст, который виден в журнале и приходит в `onRestart`)
 * -> стабильный триггер диагностической попытки и конца сессии в
 * `session_history`. Каждая известная причина имеет свой триггер: общий
 * `restart` остаётся только для текстов, которых здесь нет, и по нему
 * нельзя понять, что именно оборвало связь.
 */
internal object RestartTriggers {
    const val REPLACEMENT_REASON = "Переключение на резервный сервер"
    const val GENERIC = "restart"

    /** Все причины, которые приложение передаёт в перезапуск, и их триггеры. */
    val KNOWN: Map<String, String> = linkedMapOf(
        // Действия пользователя.
        "Перезапуск пользователем" to "user_restart",
        "Перезапуск из уведомления" to "notification_restart",
        "Перезапуск VPN" to "vpn_restart",
        "Подписка обновлена пользователем" to "subscription_refresh",
        "Группа подписки обновлена пользователем" to "subscription_group_refresh",
        "Изменение маршрутизации" to "routing_change",
        "Сброс DNS-состояния" to "dns_cache_clear",
        // Настройки, требующие нового запуска ядра.
        "Смена режима DNS" to "dns_mode_change",
        "Смена IP-стратегии DNS" to "dns_strategy_change",
        "Смена DNS-переопределения" to "dns_override_change",
        "Смена защиты от localhost-чекеров" to "endpoint_policy_change",
        "Смена имени VPN-сессии" to "session_name_change",
        "Смена MTU для скрытия VPN" to "mtu_change",
        // Маршрут для обновления приложения.
        "Временный маршрут updater" to "updater_route_on",
        "Завершение маршрута updater" to "updater_route_off",
        // Автоматика.
        "Смена сети Android" to "network_change",
        "Автоматическое переподключение" to "auto_recovery",
        "Подключение до смены сети" to "network_resume_manual",
        "Автоматическое подключение по сети" to "network_resume_auto",
        "Перезапуск после сбоя управления ядром" to "core_control_failure",
        REPLACEMENT_REASON to "server_replacement",
    )

    fun fromReason(reason: String): String = KNOWN[reason] ?: GENERIC
}
