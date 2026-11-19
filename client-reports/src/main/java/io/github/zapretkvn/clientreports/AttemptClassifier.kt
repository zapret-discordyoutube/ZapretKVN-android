package io.github.zapretkvn.clientreports

/**
 * Что известно о конце одной проверки сервера: из исключения проверки, свидетельства ядра и
 * предварительного TCP-соединения. Только факты; решение — в [AttemptClassifier].
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
)

/** Исход для отчёта: шаг лестницы и короткий код приложения. */
data class AttemptVerdict(val outcome: ReportOutcome, val failedStep: String, val detail: String)

/**
 * Переводит конец проверки на лестницу шагов vpnbot (resolve → connect → handshake → auth → tunnel →
 * request). Отказ, который не про сервер (локальная ошибка, потеря сети, DNS-режим без свидетельства
 * ядра), отчётом не становится: такой отчёт окрасил бы рабочий сервер.
 */
object AttemptClassifier {
    fun classify(facts: AttemptFacts): AttemptVerdict? {
        if (facts.cancelled) return AttemptVerdict(ReportOutcome.Cancelled, "", "cancelled")
        if (facts.succeeded) return AttemptVerdict(ReportOutcome.Connected, "", "")
        if (facts.networkLost) return null
        val code = facts.coreCode
        if (code != null) {
            if (code.startsWith("LOCAL_")) return null
            byCoreCode(code, facts)?.let { return it }
        }
        if (!facts.trafficFailed) return null
        if (!facts.udpTransport && facts.tcpPreflightFailed) return failed("connect", "tcp_timeout")
        return failed("request", "no_response")
    }

    private fun byCoreCode(code: String, facts: AttemptFacts): AttemptVerdict? = when (code) {
        "TARGET_AUTH_REJECTED" -> failed("auth", "auth_rejected")
        "TARGET_TLS_REJECTED", "TARGET_TLS_INTERNAL", "TARGET_TLS_UNKNOWN_AUTHORITY", "TARGET_PIN_MISMATCH" ->
            failed("handshake", "tls_failed")
        "TARGET_OBFS_REJECTED" -> failed("handshake", "obfs_rejected")
        "TARGET_CONNECTION_REFUSED" -> failed("connect", "connection_refused")
        // Соединение есть, затем обрыв до конца рукопожатия: так выглядит фильтр после SYN.
        "TARGET_CONNECTION_CLOSED" -> failed("handshake", "connection_closed")
        "TARGET_NETWORK_TIMEOUT" -> when {
            facts.udpTransport -> failed("handshake", "udp_no_answer")
            facts.tcpPreflightFailed -> failed("connect", "tcp_timeout")
            else -> failed("handshake", "handshake_timeout")
        }
        "TARGET_DESTINATION_UNREACHABLE" -> failed("request", "destination_unreachable")
        else -> null
    }

    private fun failed(step: String, detail: String) = AttemptVerdict(ReportOutcome.Failed, step, detail)
}
