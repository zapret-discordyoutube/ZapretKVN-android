package io.github.zapretkvn.android.network.probes

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Собирает результаты проб в пачки по времени и отдаёт каждую в [publish].
 *
 * Первая пачка уходит не позже чем через [windowMillis] после первого
 * результата, а не когда накопится N штук: счётчик в UI идёт ровно, и хвост
 * раунда не ждёт самого медленного сервера. Каждая публикация — одно обновление
 * списка серверов, поэтому слать результаты по одному было бы слишком часто.
 */
internal suspend fun <K, V> ReceiveChannel<Map<K, V>>.collectBatched(
    windowMillis: Long,
    nowMillis: () -> Long = System::currentTimeMillis,
    publish: suspend (Map<K, V>) -> Unit,
) {
    while (true) {
        val first = receiveCatching().getOrNull() ?: return
        val batch = LinkedHashMap(first)
        val deadline = nowMillis() + windowMillis
        var open = true
        while (open) {
            val left = deadline - nowMillis()
            if (left <= 0) break
            val next = withTimeoutOrNull(left) { receiveCatching() } ?: break
            val value = next.getOrNull()
            if (value == null) open = false else batch.putAll(value)
        }
        publish(batch)
        if (!open) return
    }
}

/**
 * Серверы с одним и тем же адресом — одна проба на всех: в подписках один хост
 * повторяется под разными портами и именами. Порядок групп случайный, чтобы
 * проверка не повторяла порядок списка.
 */
internal fun List<ServerPingTarget>.groupedByHost(
    shuffle: (List<List<ServerPingTarget>>) -> List<List<ServerPingTarget>> = { it.shuffled() },
): List<List<ServerPingTarget>> =
    shuffle(groupBy { it.hostname.trim().lowercase() }.values.toList())
