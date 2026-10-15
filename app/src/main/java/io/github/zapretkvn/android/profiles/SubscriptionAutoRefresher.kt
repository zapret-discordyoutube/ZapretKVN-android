package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.ImportException
import io.github.zapretkvn.android.importer.SubscriptionSourceStore
import java.io.IOException

/** Итог одного фонового прохода: сколько профилей обновлено и сколько пропущено. */
internal data class SubscriptionAutoRefreshReport(val updated: Int, val skipped: Int)

/**
 * Фоновое обновление подписок (суточное задание `UpdateCheckJobService`): тот же путь, что у ручного «Обновить (без кэша)» →
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
) {
    suspend fun refreshAll(): SubscriptionAutoRefreshReport {
        var updated = 0
        var skipped = 0
        for (profileId in subscriptionSourceStore.ids().sorted()) {
            val binding = subscriptionSourceStore.binding(profileId)
            if (binding == null || binding.isSplit) {
                skipped++
                continue
            }
            // Граница с сетью, сервером подписки и хранилищем: профиль могли удалить,
            // сервер — не ответить или прислать то, что ядро не примет. Профиль ждёт
            // следующего прохода, остальные обновляются.
            try {
                val preview = service.refreshPreview(profileId, store.profiles.value)
                store.update(profileId, preview.preparedJson)
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
}
