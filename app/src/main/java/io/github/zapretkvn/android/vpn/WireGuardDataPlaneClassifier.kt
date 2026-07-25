package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.SecretRedactor

/**
 * Разделяет общий провал data-plane (VPN-200/DNS-200) на два WireGuard-паттерна
 * по стартовым core-логам: «рукопожатие прошло, данных нет» (VPN-210) и
 * «рукопожатие без ответа» (VPN-211). Блокировку DPI от сломанного NAT на
 * сервере с клиента отличить нельзя, поэтому оба остаются в VPN-210, а сырые
 * строки-доказательства из лога ядра сохраняются в technicalDetail.
 *
 * Паттерн VPN-210 подтверждён полевыми отчётами (июль 2026): у пользователя в РФ
 * handshake того же WG-профиля проходил на Wi-Fi и mobile, но данные молчали
 * («stopped hearing back», dns deadline exceeded), при этом тот же профиль
 * работал из другой сети и с AmneziaWG-обфускацией — то есть DPI режет именно
 * чистый WireGuard после рукопожатия.
 *
 * VPN-210 требует доказательств мёртвого data-plane (stall или dns deadline),
 * а успешный «dns: exchanged» через туннель является контрдоказательством:
 * без этого DNS-провал из-за strict Private DNS (DoT :853 refused при живом
 * туннеле) ложно помечался блокировкой протокола.
 */
internal object WireGuardDataPlaneClassifier {
    private const val DATA_PLANE_CODE = "VPN-210"
    private const val HANDSHAKE_CODE = "VPN-211"
    private const val MAX_TECHNICAL_DETAIL_CHARS = 240

    private val refinableCodes = setOf("VPN-200", "DNS-200")
    private const val HANDSHAKE_SENT = "sending handshake initiation"
    private const val HANDSHAKE_RECEIVED = "received handshake response"
    private val stallMarkers = listOf(
        "handshake did not complete",
        "retrying handshake because we stopped hearing back",
    )
    private const val DNS_EXCHANGE_FAILED = "dns: exchange failed"
    private const val DNS_EXCHANGE_SUCCEEDED = "dns: exchanged"

    private const val DATA_PLANE_MESSAGE =
        "WireGuard: рукопожатие с сервером проходит, но данные через туннель " +
            "не возвращаются. Обычно это блокировка протокола провайдером либо " +
            "выключенный форвардинг/NAT на сервере; помогает AmneziaWG-обфускация " +
            "или другой транспорт. Сырые строки ядра — в технических деталях."
    private const val HANDSHAKE_MESSAGE =
        "WireGuard: VPN-сервер не отвечает на рукопожатие. Сервер недоступен " +
            "по адресу и порту из профиля либо рукопожатие блокируется сетью. " +
            "Сырые строки ядра — в технических деталях."

    fun refine(
        failure: VpnConnectionState.Error,
        startupCoreLogs: List<DiagnosticLogLine>,
    ): VpnConnectionState.Error {
        if (failure.code !in refinableCodes) return failure
        val messages = startupCoreLogs.map(DiagnosticLogLine::message)
        val wireguard = messages.filter { it.contains("wireguard", ignoreCase = true) }
        if (wireguard.isEmpty()) return failure
        val received = wireguard.lastOrNull { it.contains(HANDSHAKE_RECEIVED, ignoreCase = true) }
        val sent = wireguard.any { it.contains(HANDSHAKE_SENT, ignoreCase = true) }
        val stalled = wireguard.firstOrNull { line ->
            stallMarkers.any { line.contains(it, ignoreCase = true) }
        }
        val dnsFailed = messages.firstOrNull { it.contains(DNS_EXCHANGE_FAILED, ignoreCase = true) }
        val dnsAlive = messages.any { it.contains(DNS_EXCHANGE_SUCCEEDED, ignoreCase = true) }
        return when {
            received != null && !dnsAlive && (stalled != null || dnsFailed != null) ->
                VpnConnectionState.Error(
                    message = DATA_PLANE_MESSAGE,
                    code = DATA_PLANE_CODE,
                    technicalDetail = rawEvidence(failure.code, received, stalled, dnsFailed),
                )
            received == null && sent && stalled != null -> VpnConnectionState.Error(
                message = HANDSHAKE_MESSAGE,
                code = HANDSHAKE_CODE,
                technicalDetail = rawEvidence(failure.code, stalled),
            )
            else -> failure
        }
    }

    /** Точные строки лога вместо пересказа: по ним видно, что именно сломалось. */
    private fun rawEvidence(originalCode: String, vararg lines: String?): String =
        (sequenceOf("original=$originalCode") + lines.asSequence().filterNotNull())
            .map { SecretRedactor.redactInline(it).trim() }
            .joinToString(" | ")
            .take(MAX_TECHNICAL_DETAIL_CHARS)
}
