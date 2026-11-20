package io.github.zapretkvn.clientreports

/**
 * Что известно о конце одной проверки сервера: из исключения проверки, свидетельства ядра и
 * предварительного TCP-соединения. Только факты; решает vpnbot.
 */
data class AttemptFacts(
    /** Проверку отменили (переключение, остановка): о сервере это ничего не говорит. */
    val cancelled: Boolean = false,
    /** DNS и HTTPS через сервер прошли. */
    val succeeded: Boolean = false,
    /** Код отказа ядра (`runtime-errors.json`), названный именно для этого сервера. */
    val coreCode: String? = null,
    /** Потерялась сеть Android (VPN или основная): виноват не сервер. */
    val networkLost: Boolean = false,
    /** Не прошёл трафик через сервер: HTTPS-проба или срок проверки. */
    val trafficFailed: Boolean = false,
    /** Пустое TCP-соединение к серверу перед запуском не установилось. */
    val tcpPreflightFailed: Boolean = false,
    /** Протокол сервера идёт по UDP (Hysteria 2, WireGuard): TCP-предпроверка к нему ничего не значит. */
    val udpTransport: Boolean = false,
    /** The app's own health check through the tunnel when it got no data (probe `health`, vpnbot 2026-11-20). */
    val health: ReportCheck? = null,
)

/**
 * The attempt as facts, not a verdict (schema 3): the server judges from the core code and the checks (owner
 * 2026-11-19: analytics live in vpnbot).
 */
object AttemptOutcome {
    fun of(facts: AttemptFacts): ReportOutcome = when {
        facts.cancelled -> ReportOutcome.Cancelled
        facts.succeeded -> ReportOutcome.Connected
        else -> ReportOutcome.Failed
    }

    /** The core code a report carries: a lost network is NETWORK_LOST; other codes only in the contract's shape. */
    fun coreCode(facts: AttemptFacts): String =
        if (facts.networkLost) ReportChecks.NETWORK_LOST else ReportChecks.coreCodeOf(facts.coreCode)
}
