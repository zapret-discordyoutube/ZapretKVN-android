package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.engines.failover.OutboundFailureLogParser
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureClassifier

/**
 * Строки лога ядра → подсказки об отказе конкретного outbound.
 *
 * Работает в потоке libbox, поэтому в очередь рантайма попадают только
 * классифицированные подсказки, без повторов внутри пачки: при массовых
 * отказах ядро пишет тысячи строк. Строки без `outbound/тип[тег]` (например,
 * внутренние сообщения xray) подсказками не бывают.
 */
internal object CoreLogHints {
    fun from(messages: List<String>): List<PathHint> =
        OutboundFailureLogParser.all(messages).mapNotNull { line ->
            // sing-box маршрутизирует все протоколы; у Hysteria строки шумнее и
            // свой фильтр ключевых слов, остальные идут по общему каталогу.
            val code = if (line.outboundType == PathSupervisor.HYSTERIA_TYPE || line.outboundType == "hy2") {
                HysteriaFailureClassifier.classifyRuntime(line.message)
            } else {
                HysteriaFailureClassifier.classify(line.message)
            } ?: return@mapNotNull null
            PathHint(line.outboundTag, line.outboundType, code)
        }.distinct()

    /**
     * Нетегированные отказы DNS по таймауту: `dns: exchange failed for имя. IN A: ... deadline exceeded`.
     * В них нет `outbound/тип[тег]`, поэтому на сервер они не указывают и сами
     * по себе проверку не запускают: это лишь вход [StallDetector], который
     * сверяет их с ростом входящего трафика. Отказы без таймаута (закрытый
     * канал, NXDOMAIN, отмена запроса приложением) не считаются.
     */
    fun dnsFailures(messages: List<String>): DnsFailureHint? {
        var names: MutableSet<String>? = null
        for (message in messages) {
            val match = DNS_FAILURE.find(message) ?: continue
            if (!DNS_TIMEOUT.containsMatchIn(message.substring(match.range.last + 1))) continue
            val set = names ?: mutableSetOf<String>().also { names = it }
            // Предел против массовых отказов: счёт нужен детектору лишь до его порога.
            if (set.size < MAX_DNS_NAMES_PER_BATCH) set += match.groupValues[1].ifBlank { "#${set.size}" }
        }
        return names?.let { DnsFailureHint(it) }
    }

    private val DNS_FAILURE = Regex("""dns: (?:exchange|lookup) failed(?: for (\S*))?""")
    private val DNS_TIMEOUT = Regex("""timeout|deadline|timed out""", RegexOption.IGNORE_CASE)
    private const val MAX_DNS_NAMES_PER_BATCH = 16
}

/**
 * Отказ DNS по таймауту в логе ядра (отдельный от [PathHint] тип подсказки).
 * [names] — имена запросов только для подсчёта различных; не пишутся в журнал.
 */
internal data class DnsFailureHint(val names: Set<String>) {
    val count: Int get() = names.size
}
