package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import java.io.IOException
import kotlinx.coroutines.sync.Mutex

/** Итог одного фонового прохода: сколько профилей обновлено и сколько пропущено. */
internal data class SubscriptionAutoRefreshReport(val updated: Int, val skipped: Int)

/**
 * Фоновое обновление подписок (задание `UpdateCheckJobService`, период — настройка «Автообновление подписок»): тот же путь, что у ручного «Обновить (без кэша)» →
 * «Сохранить без перезапуска» — [ImportService.refreshPreview] и [ProfileStore.update].
 *
 * До 0.5.5 подписки обновлялись только вручную, и новые серверы не доходили до людей,
 * которые не знали про меню профиля (15.10.2026). Работающий VPN не перезапускается:
 * новый список применится при следующем подключении, как и при ручном сохранении без перезапуска.
 * Раздельные группы подписок требуют подтверждения удаления профилей и в фоне не трогаются.
 * Ошибка сети или ответ, который нельзя применить, пропускают профиль до следующего прохода.
 */
internal class SubscriptionAutoRefresher(
    private val service: ImportService,
    private val store: ProfileStore,
    private val subscriptionSourceStore: SubscriptionSourceStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()

    suspend fun refreshAll(): SubscriptionAutoRefreshReport = refreshDue(intervalHours = 0, force = true)

    /**
     * Обновляет подписки, чей список старше [intervalHours] (настройка «Автообновление подписок»).
     * 0 — только вручную, ничего не делается. Задание будится чуть раньше срока ([EARLY_MILLIS]),
     * поэтому «раз в 6 ч» не превращается в «раз в 12 ч» из-за минутного опоздания.
     */
    suspend fun refreshDue(intervalHours: Int, force: Boolean = false): SubscriptionAutoRefreshReport {
        if (!force && intervalHours <= 0) return SubscriptionAutoRefreshReport(0, 0)
        // Задание могут разбудить дважды подряд: два прохода не тянут одну подписку одновременно.
        if (!running.tryLock()) return SubscriptionAutoRefreshReport(0, 0)
        try {
            return refresh(if (force) null else intervalHours * HOUR_MILLIS - EARLY_MILLIS)
        } finally {
            running.unlock()
        }
    }

    private suspend fun refresh(minAgeMillis: Long?): SubscriptionAutoRefreshReport {
        var updated = 0
        var skipped = 0
        val updatedAt = store.profiles.value.associate { it.id to it.updatedAtEpochMillis }
        for (profileId in subscriptionSourceStore.ids().sorted()) {
            val binding = subscriptionSourceStore.binding(profileId)
            if (binding == null || binding.isSplit) {
                skipped++
                continue
            }
            val age = now() - (updatedAt[profileId] ?: 0L)
            if (minAgeMillis != null && age in 0 until minAgeMillis) continue
            // Граница с сетью, сервером подписки и хранилищем: профиль могли удалить,
            // сервер — не ответить или прислать то, что ядро не примет. Профиль ждёт
            // следующего прохода, остальные обновляются.
            try {
                val preview = service.refreshPreview(profileId, store.profiles.value)
                // Предпросмотр собран из профиля, прочитанного до скачивания подписки:
                // выбранный за это время сервер (вручную или автоматикой) не затирается
                // прежним — выбор берётся из профиля на момент записи.
                val current = store.read(profileId).json
                store.update(profileId, ManagedProfileEditor.preserveSelectorDefaults(current, preview.preparedJson).json)
                updated++
            } catch (error: ImportException) {
                skipped++
            } catch (error: ProfileStoreException) {
                skipped++
            } catch (error: IOException) {
                skipped++
            }
        }
        return SubscriptionAutoRefreshReport(updated, skipped)
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
        const val EARLY_MILLIS = 30L * 60 * 1000
    }
}
