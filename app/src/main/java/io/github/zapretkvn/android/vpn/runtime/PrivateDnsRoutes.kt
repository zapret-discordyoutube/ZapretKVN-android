package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.PrivateDnsRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Прямой путь к резолверу strict Private DNS ([PrivateDnsRoute]).
 *
 * Адреса берутся у самой нижней сети, где Android этот резолвер уже проверил, —
 * тот же запрос, что Android делает сам перед DoT. Он не должен задерживать
 * подключение: блокирующий системный резолв не отменяется, поэтому идёт в
 * отдельной области, а по тайм-ауту правило остаётся только с распознаванием по
 * SNI. Ошибка резолва не мешает запуску по той же причине.
 */
internal object PrivateDnsRoutes {
    const val LOOKUP_TIMEOUT_MILLIS = 2_000L

    suspend fun forStrict(
        hostname: String?,
        timeoutMillis: Long = LOOKUP_TIMEOUT_MILLIS,
        lookup: suspend (String) -> List<String>,
    ): PrivateDnsRoute? {
        val name = hostname?.trim()?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        val resolving = CoroutineScope(Dispatchers.IO).async {
            runCatching { lookup(name) }.getOrDefault(emptyList())
        }
        val addresses = withTimeoutOrNull(timeoutMillis) { resolving.await() }
            ?: emptyList<String>().also { resolving.cancel() }
        return PrivateDnsRoute(name, addresses.filter(String::isNotBlank).distinct())
    }
}
