package io.github.zapretkvn.clientreports

/**
 * Где именно и чем закрыт сервер (решение владельца 19.11.2026). После неудачной попытки приложение
 * проверяет путь по ступеням и на каждой подменяет одно: тот же IP на порту 443, то же имя на
 * другом IP, другое имя на том же IP. Здесь только вывод из результатов этих проверок; сеть — у
 * исполнителя. Ступени и коды совпадают с Windows (`xray_fluent/client_reports/contract.py`).
 *
 * «Ответил» для TLS — пришла хоть одна TLS-запись (ServerHello или даже alert): сервер, которому не
 * понравилось подменённое имя, отвечает alert'ом и закрытым не считается. Закрыто — тишина или сброс
 * после ClientHello.
 */
enum class ProbeResult {
    /** Ответ есть (TCP: соединение установлено; TLS: пришла TLS-запись). */
    Answered,

    /** Тишина до срока. */
    Timeout,

    /** Соединение сброшено или закрыто без ответа. */
    Reset,

    /** Проверить не вышло по местной причине (нет сети, нет адреса): ничего не доказывает. */
    Unknown,
}

data class DiagnosisFacts(
    /**
     * Контроль: TCP до заведомо рабочего адреса (наш фронт пула в России). Не ответил — не работает
     * сама сеть телефона (портал входа, нет интернета), и молчание сервера ничего не доказывает.
     */
    val control: ProbeResult = ProbeResult.Unknown,
    /** Адреса имени сервера от системного DNS; null — не спрашивали (в ссылке IP), пусто — ответа нет. */
    val systemDns: Set<String>? = null,
    /** Адреса того же имени от нашего DoH; null — не спрашивали или DoH не ответил. */
    val dohDns: Set<String>? = null,
    /** Все доверенные DoH ответили, что имени нет: ошибка в имени сервера, а не блокировка. */
    val dohNameMissing: Boolean = false,
    /** TCP до IP:порт сервера. */
    val tcpTarget: ProbeResult = ProbeResult.Unknown,
    /** TCP до того же IP на 443 (только если порт сервера не 443 и [tcpTarget] не ответил). */
    val tcpOtherPort: ProbeResult = ProbeResult.Unknown,
    /** Транспорт сервера с TLS (tls или reality): ступень TLS имеет смысл. */
    val usesTls: Boolean = false,
    /** ClientHello с настоящим именем (SNI) на IP сервера. */
    val tlsRealName: ProbeResult = ProbeResult.Unknown,
    /** ClientHello с нейтральным (нашим незакрытым) именем на тот же IP. */
    val tlsNeutralName: ProbeResult = ProbeResult.Unknown,
    /** ClientHello с настоящим именем на заведомо рабочий IP (наш фронт пула). */
    val tlsRealNameElsewhere: ProbeResult = ProbeResult.Unknown,
    /** Сервер по UDP (Hysteria, WireGuard/AWG): TLS-ступени нет, ответ на UDP судит ядро. */
    val udpTransport: Boolean = false,
    /**
     * Через поднятый туннель запрошен файл больше 16 КБ: сколько байт пришло до остановки; null — не
     * мерили или пришёл целиком.
     */
    val stalledAfterBytes: Long? = null,
)

data class Diagnosis(val failedStep: String, val detail: String)

object BlockDiagnosis {
    /** ТСПУ «замораживает» соединение после ~25 пакетов (≈16 КБ, у мобильных меньше): окно остановки. Проба читает до 64 КБ, поэтому прошедшая дальше окна в него не попадает. */
    const val FREEZE_MIN_BYTES = 1_000L
    const val FREEZE_MAX_BYTES = 24_000L

    /** Повтор проверки одного сервера не чаще (решение владельца: только после неудачи, редко). */
    const val MIN_INTERVAL_MILLIS = 10L * 60 * 1000

    /**
     * Закрытая ступень пути до сервера. TCP, TLS и заморозка идут первыми: подменённый системный DNS
     * не помеха, если приложение взяло адрес у нашего DoH, поэтому DNS называется, только когда ниже
     * ничего не закрыто. null — проверки ничего не доказали: тогда остаётся вывод ядра ([AttemptClassifier]).
     */
    fun diagnose(facts: DiagnosisFacts): Diagnosis? {
        if (facts.control != ProbeResult.Answered) return null
        tcp(facts)?.let { return it }
        if (facts.usesTls && !facts.udpTransport) tls(facts)?.let { return it }
        facts.stalledAfterBytes?.let { bytes ->
            if (bytes in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES) return Diagnosis("request", "freeze_16k")
        }
        return dns(facts)
    }

    private fun dns(facts: DiagnosisFacts): Diagnosis? {
        if (facts.dohNameMissing) return Diagnosis("resolve", "dns_nxdomain")
        val system = facts.systemDns ?: return null
        val doh = facts.dohDns
        if (system.isEmpty()) return Diagnosis("resolve", "dns_no_answer")
        // Системный ответ не пересекается с ответом нашего DoH: имя подменено по пути.
        if (!doh.isNullOrEmpty() && system.intersect(doh).isEmpty()) return Diagnosis("resolve", "dns_poisoned")
        return null
    }

    private fun tcp(facts: DiagnosisFacts): Diagnosis? = when (facts.tcpTarget) {
        // UDP-сервер или порт ответил: TCP не закрытая ступень.
        ProbeResult.Answered, ProbeResult.Unknown -> null
        // Сброс — кто-то ответил: адрес жив, сервер или фильтр рвёт соединение, различить нельзя.
        ProbeResult.Reset -> Diagnosis("connect", "tcp_reset")
        ProbeResult.Timeout -> when (facts.tcpOtherPort) {
            // 443 того же адреса отвечает (хоть сбросом): закрыт только порт сервера.
            ProbeResult.Answered, ProbeResult.Reset -> Diagnosis("connect", "port_blocked")
            ProbeResult.Timeout -> Diagnosis("connect", "ip_blocked")
            ProbeResult.Unknown -> Diagnosis("connect", "tcp_timeout")
        }
    }

    private fun tls(facts: DiagnosisFacts): Diagnosis? {
        val real = facts.tlsRealName
        if (real == ProbeResult.Answered || real == ProbeResult.Unknown) return null
        // На подменённых пробах фильтр — только тишина: сервер (и наш фронт) закрывает соединение без
        // ответа на чужое имя, поэтому сброс там ничего не доказывает.
        return when (facts.tlsNeutralName) {
            ProbeResult.Answered ->
                if (facts.tlsRealNameElsewhere == ProbeResult.Timeout) {
                    Diagnosis("handshake", "sni_blocked_everywhere")
                } else {
                    Diagnosis("handshake", "sni_blocked")
                }
            ProbeResult.Timeout -> Diagnosis("handshake", "tls_blocked")
            ProbeResult.Reset, ProbeResult.Unknown -> null
        }
    }

    /**
     * Проверки IP-уровня для UDP-сервера: его порт по TCP не проверить, но если 443 того же IP молчит,
     * закрыт весь IP. Вызывается исполнителем вместо [diagnose] для Hysteria/WireGuard.
     */
    fun diagnoseUdp(facts: DiagnosisFacts): Diagnosis? {
        if (facts.control != ProbeResult.Answered) return null
        // Только тишина на 443: сброс значит, что адрес жив (Hysteria может не слушать TCP).
        return if (facts.tcpOtherPort == ProbeResult.Timeout) Diagnosis("connect", "ip_blocked") else dns(facts)
    }

    /** Ступень заморозки после удачного подключения: только она меняет «подключено» на отказ. */
    fun freeze(stalledAfterBytes: Long?): Diagnosis? =
        stalledAfterBytes?.takeIf { it in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES }?.let { Diagnosis("request", "freeze_16k") }

    /**
     * Строка для экрана ошибки. [diagnosis] null после проверок: путь открыт или проверка ничего не
     * доказала ([facts] различает).
     */
    fun describe(diagnosis: Diagnosis?, facts: DiagnosisFacts?): String = when (diagnosis?.detail) {
        "ip_blocked" -> "Адрес сервера закрыт целиком: не отвечают ни его порт, ни порт 443."
        "port_blocked" -> "Закрыт порт сервера: тот же адрес на порту 443 отвечает."
        "tcp_timeout" -> "Сервер молчит в ответ на соединение."
        "tcp_reset" -> "Соединение с сервером сбрасывается."
        "sni_blocked" -> "Закрыто имя сервера (SNI): с другим именем тот же адрес отвечает."
        "sni_blocked_everywhere" -> "Имя сервера (SNI) закрыто на любом адресе."
        "tls_blocked" -> "TLS-рукопожатие с адресом сервера обрывается при любом имени."
        "freeze_16k" -> "Соединение с сервером замирает после ~16 КБ."
        "dns_poisoned" -> "Системный DNS подменяет адрес сервера."
        "dns_no_answer" -> "Системный DNS не находит имя сервера."
        "dns_nxdomain" -> "Имени сервера нет в DNS."
        null -> when {
            facts == null -> "Проверка пути до сервера не проводилась."
            facts.control != ProbeResult.Answered -> "Проверка пути не удалась: сеть телефона не открывает даже рабочий адрес."
            facts.tcpTarget == ProbeResult.Answered &&
                (!facts.usesTls || facts.udpTransport || facts.tlsRealName == ProbeResult.Answered) ->
                "Путь до сервера открыт: адрес, порт и имя отвечают."
            else -> "Проверка пути до сервера ничего не показала."
        }
        else -> "Найдена блокировка: ${diagnosis.detail}."
    }
}
