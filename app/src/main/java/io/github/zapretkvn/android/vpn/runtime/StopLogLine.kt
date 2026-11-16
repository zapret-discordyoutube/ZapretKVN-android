package io.github.zapretkvn.android.vpn.runtime

/**
 * The on-disk runtime log line for a VPN stop. The stop diagnostic and the session history live only in
 * memory, so when Android later kills the idle process (LOW_MEMORY) the reason the VPN went off is lost
 * (field case 2026-11-16 09:40 UTC: traffic ended mid-flow and no stop reason survived anywhere).
 */
internal fun stopLogLine(trigger: String, errorMessage: String?): String {
    val who = when (trigger) {
        "user_stop" -> "команда «Отключить» (приложение, уведомление или внешнее управление)"
        "permission_revoked" -> "Android отозвал VPN (включён другой VPN или отозвано разрешение)"
        "policy_stop" -> "остановка по ошибке или политике системы"
        else -> trigger.take(40)
    }
    val reason = errorMessage?.trim()?.take(160)?.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
    return "VPN останавливается ($trigger), причина — $who$reason"
}

/** Android closed the service (onDestroy) while a session was still live and no stop was requested. */
internal fun destroyLogLine(liveSessions: Int): String =
    "сервис VPN закрыт системой без команды остановки (onDestroy), живых сессий: $liveSessions"
