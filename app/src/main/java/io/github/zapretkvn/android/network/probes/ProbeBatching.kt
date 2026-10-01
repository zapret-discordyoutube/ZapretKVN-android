package io.github.zapretkvn.android.network.probes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Собирает результаты проб в пачки по времени и отдаёт каждую в [publish].
 *
 * Первая пачка уходит не позже чем через [windowMillis] после первого
 * результата, а не когда накопится N штук: счётчик в UI идёт ровно, и хвост
 * раунда не ждёт самого медленного сервера. Каждая публикация — одно обновление
 * списка серверов, поэтому слать результаты по одному было бы слишком часто.
 *
 * Ожидание с дедлайном — через `select`, а не `withTimeout` вокруг `receive`:
 * таймаут, сработавший одновременно с получением, отменил бы корутину уже после
 * того, как результат вынут из канала, и сервер навсегда остался бы
 * «Проверяется». Часы — монотонные: перевод системного времени окно не ломает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <K, V> ReceiveChannel<Map<K, V>>.collectBatched(
    windowMillis: Long,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
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
            val next: ChannelResult<Map<K, V>> = select<ChannelResult<Map<K, V>>?> {
                onReceiveCatching { it }
                onTimeout(left) { null }
            } ?: break
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
