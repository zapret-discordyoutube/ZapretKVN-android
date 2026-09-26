package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.probes.HttpsProbeVerdict

/** Понятное человеку объяснение ошибки VPN для главного экрана. */
data class VpnErrorExplanation(
    val title: String,
    val hint: String,
)

/** Что известно о выбранном сервере, когда случилась ошибка. */
data class VpnErrorContext(
    /** Тип outbound выбранного сервера (vless, hysteria2, ...), если известен. */
    val protocol: String? = null,
    /** В профиле есть другие серверы, на которые можно переключиться. */
    val hasOtherServers: Boolean = false,
)

/**
 * Переводит код ошибки в объяснение без технических терминов. Код и
 * технические детали остаются на экране отдельно: их видит поддержка.
 */
object VpnErrorExplainer {
    private val UDP_PROTOCOLS = setOf("hysteria", "hysteria2", "tuic", "wireguard")

    fun explain(
        code: String,
        technicalDetail: String? = null,
        context: VpnErrorContext = VpnErrorContext(),
    ): VpnErrorExplanation {
        val normalized = code.trim().uppercase()
        return supportCode(normalized, technicalDetail, context)
            ?: hysteria(normalized, context)
            ?: unknown()
    }

    /** true, если для кода есть конкретное объяснение, а не общий текст. */
    internal fun isKnown(code: String): Boolean {
        val normalized = code.trim().uppercase()
        return supportCode(normalized, null, VpnErrorContext()) != null ||
            hysteria(normalized, VpnErrorContext()) != null
    }

    private fun supportCode(
        code: String,
        technicalDetail: String?,
        context: VpnErrorContext,
    ): VpnErrorExplanation? = when (code) {
        "VPN-101" -> VpnErrorExplanation(
            "Android не разрешил VPN",
            "Разрешите приложению создать VPN. Если в настройках Android включён " +
                "«Постоянный VPN» для другого приложения, отключите его.",
        )
        "CFG-101" -> VpnErrorExplanation(
            "Профиль повреждён или не поддерживается",
            "Обновите подписку или импортируйте ключ заново.",
        )
        "DNS-100", "DNS-101", "DNS-105" -> VpnErrorExplanation(
            "Телефон не смог найти адрес сервера",
            "Проверьте, открываются ли сайты без VPN, и подключитесь ещё раз.",
        )
        "DNS-102", "DNS-104", "DNS-106" -> VpnErrorExplanation(
            "Адрес сервера не найден",
            "Возможно, сервер переехал или ключ устарел. Обновите подписку. " +
                otherServer(context),
        )
        "DNS-103" -> VpnErrorExplanation(
            "DNS отклонил запрос адреса сервера",
            "Часто так мешает «Частный DNS». В настройках Android откройте " +
                "Сеть → Частный DNS и выберите «Автоматически» или «Отключено».",
        )
        "DNS-110" -> VpnErrorExplanation(
            "Мешает «Частный DNS» Android",
            "В настройках Android откройте Сеть → Частный DNS и выберите " +
                "«Автоматически» или «Отключено», затем подключитесь снова.",
        )
        "DNS-200" -> VpnErrorExplanation(
            "VPN подключился, но DNS через него не отвечает",
            "Смените режим DNS в настройках приложения или сервер. " + otherServer(context),
        )
        "SRV-100" -> VpnErrorExplanation(
            "Сервер не отвечает",
            "Сервер выключен или недоступен из вашей сети. " + otherServer(context),
        )
        "AUTH-100" -> VpnErrorExplanation(
            "Сервер не принял ключ",
            "Ключ устарел или подписка закончилась. Обновите подписку или получите новый ключ.",
        )
        "NET-100", "NET-101" -> VpnErrorExplanation(
            "Нет подключения к интернету",
            "Включите мобильный интернет или Wi-Fi и подключитесь снова.",
        )
        "NET-102" -> VpnErrorExplanation(
            "Сеть сменилась во время подключения",
            "Дождитесь стабильной сети и подключитесь ещё раз.",
        )
        "NET-110" -> VpnErrorExplanation(
            "Wi-Fi требует входа",
            "Откройте браузер и авторизуйтесь в этой сети Wi-Fi, затем подключитесь снова.",
        )
        "CORE-100" -> VpnErrorExplanation(
            "Сбой VPN внутри приложения",
            "Подключитесь ещё раз. Если ошибка повторится, отправьте диагностику.",
        )
        "VPN-120" -> VpnErrorExplanation(
            "Проверка подключения не уложилась в 20 секунд",
            "Сеть очень медленная или сервер перегружен. Подключитесь ещё раз. " +
                otherServer(context),
        )
        "VPN-200" -> vpnTraffic(HttpsProbeVerdict.parse(technicalDetail), context)
        "VPN-201" -> VpnErrorExplanation(
            "Сервер несовместим с проверкой подключения",
            "Сервер работает только с одним типом адресов (IPv4 или IPv6). " +
                otherServer(context),
        )
        else -> null
    }

    private fun vpnTraffic(
        verdict: HttpsProbeVerdict?,
        context: VpnErrorContext,
    ): VpnErrorExplanation = when (verdict) {
        HttpsProbeVerdict.Tls -> VpnErrorExplanation(
            "Сайты открываются с чужим сертификатом",
            "Похоже, сеть или сервер перехватывает защищённые соединения. " +
                otherServer(context),
        )
        HttpsProbeVerdict.Reset -> VpnErrorExplanation(
            "Соединения через сервер обрываются",
            "Сервер или сеть сбрасывает соединения. " + otherServer(context),
        )
        HttpsProbeVerdict.Dns -> VpnErrorExplanation(
            "VPN подключился, но адреса сайтов не находятся",
            "Смените режим DNS в настройках приложения или сервер. " + otherServer(context),
        )
        HttpsProbeVerdict.HttpStatus -> VpnErrorExplanation(
            "Проверочные сайты отвечают с ошибкой",
            "Сервер пропускает трафик не полностью. " + otherServer(context),
        )
        HttpsProbeVerdict.Timeout, HttpsProbeVerdict.Mixed, null -> VpnErrorExplanation(
            "Сервер подключился, но интернет через него не идёт",
            buildString {
                append("Проверочные сайты не ответили. ")
                if (context.protocol?.lowercase() in UDP_PROTOCOLS) {
                    append(
                        "Протокол ${protocolName(context.protocol)} работает через UDP, " +
                            "а некоторые операторы его ограничивают. ",
                    )
                }
                append(otherServer(context))
                append(" Можно также попробовать другую сеть: Wi-Fi вместо мобильного интернета или наоборот.")
            },
        )
    }

    private fun hysteria(code: String, context: VpnErrorContext): VpnErrorExplanation? {
        val failure = HysteriaFailureCode.entries.firstOrNull { it.name == code } ?: return null
        return when (failure) {
            HysteriaFailureCode.TARGET_AUTH_REJECTED -> VpnErrorExplanation(
                "Сервер не принял ключ",
                "Ключ устарел или подписка закончилась. Обновите подписку или получите новый ключ.",
            )
            HysteriaFailureCode.TARGET_TLS_REJECTED,
            HysteriaFailureCode.TARGET_TLS_INTERNAL,
            HysteriaFailureCode.TARGET_TLS_UNKNOWN_AUTHORITY,
            HysteriaFailureCode.TARGET_PIN_MISMATCH,
            -> VpnErrorExplanation(
                "Сертификат сервера не прошёл проверку",
                "Настройки сервера изменились или соединение перехватывают. Обновите подписку. " +
                    otherServer(context),
            )
            HysteriaFailureCode.TARGET_OBFS_REJECTED -> VpnErrorExplanation(
                "Настройки маскировки не совпали с сервером",
                "Обновите подписку, чтобы получить актуальные параметры сервера.",
            )
            HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
            HysteriaFailureCode.TARGET_CONNECTION_CLOSED,
            HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
            -> VpnErrorExplanation(
                "Сервер недоступен из этой сети",
                buildString {
                    if (context.protocol?.lowercase() in UDP_PROTOCOLS) {
                        append(
                            "Протокол ${protocolName(context.protocol)} работает через UDP, " +
                                "а некоторые операторы его ограничивают. ",
                        )
                    }
                    append(otherServer(context))
                    append(" Можно также попробовать другую сеть.")
                },
            )
            HysteriaFailureCode.NO_COMPATIBLE_FALLBACK -> VpnErrorExplanation(
                "Сервер перестал работать, а замены нет",
                "Выберите другой сервер вручную или обновите подписку.",
            )
            HysteriaFailureCode.TARGET_NOT_IN_ACTIVE_POOL,
            HysteriaFailureCode.TARGET_RUNTIME_INCOMPATIBLE,
            HysteriaFailureCode.TRANSITION_STALE_GENERATION,
            HysteriaFailureCode.TRANSITION_DEADLINE_EXCEEDED,
            HysteriaFailureCode.TRANSITION_ROLLBACK_FAILED,
            -> VpnErrorExplanation(
                "Не удалось переключить сервер",
                "Подключитесь ещё раз. Если ошибка повторится, отправьте диагностику.",
            )
            HysteriaFailureCode.LOCAL_CONFIG_INVALID -> VpnErrorExplanation(
                "Профиль сервера повреждён или не поддерживается",
                "Обновите подписку или импортируйте ключ заново.",
            )
            HysteriaFailureCode.CORE_UNCLASSIFIED,
            HysteriaFailureCode.LOCAL_RELAY_AUTH_REJECTED,
            HysteriaFailureCode.LOCAL_SOCKET_PROTECTION_FAILED,
            HysteriaFailureCode.LOCAL_RUNTIME_UNSUPPORTED,
            HysteriaFailureCode.LOCAL_BIND_COLLISION,
            HysteriaFailureCode.LOCAL_PROCESS_START_FAILED,
            HysteriaFailureCode.LOCAL_PROCESS_EXITED,
            HysteriaFailureCode.LOCAL_RELAY_NOT_READY,
            HysteriaFailureCode.LOCAL_RELAY_DIED,
            HysteriaFailureCode.LOCAL_FRONT_NOT_READY,
            HysteriaFailureCode.LOCAL_CONTROL_PLANE_UNAVAILABLE,
            -> VpnErrorExplanation(
                "Сбой VPN внутри приложения",
                "Подключитесь ещё раз. Если ошибка повторится, отправьте диагностику.",
            )
        }
    }

    private fun unknown() = VpnErrorExplanation(
        "Не удалось подключиться",
        "Подключитесь ещё раз. Если ошибка повторится, отправьте диагностику.",
    )

    private fun otherServer(context: VpnErrorContext): String = if (context.hasOtherServers) {
        "Выберите другой сервер в списке."
    } else {
        "В профиле один сервер: обновите подписку или добавьте другой ключ."
    }

    private fun protocolName(protocol: String?): String = when (protocol?.lowercase()) {
        "hysteria", "hysteria2" -> "Hysteria"
        "tuic" -> "TUIC"
        "wireguard" -> "WireGuard"
        else -> protocol.orEmpty()
    }
}
