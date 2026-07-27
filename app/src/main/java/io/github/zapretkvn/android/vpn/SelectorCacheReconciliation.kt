package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.config.SelectorGroup

/** Группа selector и сервер, который обязан быть активным после старта ядра. */
internal data class SelectorSelection(
    val groupTag: String,
    val outboundTag: String,
)

/**
 * Приводит выбор selector в ядре к сохранённому профилю.
 *
 * `Selector.outboundSelect` в sing-box сначала читает `cache.db`, и только при
 * промахе смотрит `default` из конфигурации. Кэш общий для всего приложения и
 * ключуется тегом группы, а все управляемые профили используют один и тот же
 * тег, поэтому застрявшая запись переживала и выбор сервера в GUI при
 * остановленном ядре, и переключение между профилями: пользователь видел один
 * сервер, а трафик шёл через другой, зачастую нерабочий.
 *
 * Приложение хранит выбор в самом профиле (`selector.default`) на обоих путях —
 * и при горячем переключении через libbox, и при выборе с остановленным ядром, —
 * поэтому источником истины остаётся JSON, а кэш ядра приводится к нему.
 */
internal object SelectorCacheReconciliation {
    /**
     * Группа без `default` тоже попадает в результат: без явного выбора ядро
     * взяло бы `outbounds[0]`, и ровно этот сервер нужно навязать вместо
     * значения из кэша.
     */
    fun selections(groups: List<SelectorGroup>): List<SelectorSelection> =
        groups.mapNotNull { group ->
            if (group.tag.isBlank()) return@mapNotNull null
            val target = group.default?.takeIf { it in group.outbounds }
                ?: group.outbounds.firstOrNull()
                ?: return@mapNotNull null
            SelectorSelection(groupTag = group.tag, outboundTag = target)
        }
}
