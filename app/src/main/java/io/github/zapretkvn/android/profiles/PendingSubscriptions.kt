package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionSource
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import java.security.MessageDigest

/**
 * Подписки, которые не загрузились только потому, что VPN не было: адрес запоминается и
 * повторяется, когда VPN появится ([ImportController.retryPendingSubscriptions]).
 *
 * Хранение то же, что у источников профилей ([SubscriptionSourceStore], отдельный каталог): адрес
 * с токеном лежит вне индекса профилей. Ключ записи считается от адреса, поэтому повторное
 * добавление той же ссылки не плодит записи. Записей не больше [limit]: очередь не растёт без
 * предела, если человек вставляет ссылки одну за другой.
 */
internal class PendingSubscriptions(
    private val store: SubscriptionSourceStore,
    private val limit: Int = DEFAULT_LIMIT,
) {
    /** `false` — запомнить нельзя: очередь заполнена другими ссылками. Та же ссылка заменяет прежнюю запись. */
    suspend fun remember(source: SubscriptionSource): Boolean {
        val id = idFor(source.url)
        if (id !in store.ids() && store.ids().size >= limit) return false
        store.put(id, source)
        return true
    }

    /** Запомненные подписки в стабильном порядке: `(ключ записи, источник)`. */
    suspend fun all(): List<Pair<String, SubscriptionSource>> =
        store.ids().sorted().mapNotNull { id -> store.get(id)?.let { id to it } }

    suspend fun forget(id: String) = store.remove(id)

    private fun idFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
        return PREFIX + digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        private const val PREFIX = "pending-"
        private const val ID_BYTES = 8
    }
}
