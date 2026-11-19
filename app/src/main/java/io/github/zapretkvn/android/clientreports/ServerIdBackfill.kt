package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.importer.SubscriptionSource

/**
 * Once per process: a subscription of the service whose catalogue ids are unknown is fetched once, only for the
 * `zapret-server-ids` header. The body is dropped, so the server list does not change; the fetcher's own hook
 * stores the ids ([ServerIdStore.update]).
 *
 * Why (2026-11-19, the owner's Windows archive; Android has the same shape): subscriptions refreshed before the
 * header existed keep no ids, and with manual refresh (the Android default) not one connection report can name
 * a server of the service until the user refreshes by hand.
 */
internal class ServerIdBackfill(
    private val serviceSources: suspend () -> List<SubscriptionSource>,
    private val knows: (url: String) -> Boolean,
    private val fetch: (SubscriptionSource) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var done = false

    suspend fun runOnce() {
        if (done) return
        done = true
        serviceSources().filterNot { knows(it.url) }.forEach { source ->
            log("client-report: подписка сервиса без id серверов — одна загрузка ради заголовка")
            runCatching { fetch(source) }
                .onFailure { log("client-report: загрузка ради id не удалась: ${it.javaClass.simpleName}") }
        }
    }
}
