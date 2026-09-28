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
}
