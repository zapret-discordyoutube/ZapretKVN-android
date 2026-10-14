package io.github.zapretkvn.android.ui.apps

import androidx.compose.runtime.Immutable
import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.InstalledApp
import io.github.zapretkvn.android.ui.AppPickerMode

internal enum class AppFilter(val title: String) {
    All("Все"),
    Selected("Выбранные"),
    Popular("Популярные"),
}

/** Фильтры, доступные в режиме: «Популярные» есть только у выбора приложений для VPN. */
internal fun availableFilters(mode: AppPickerMode): List<AppFilter> = when (mode) {
    AppPickerMode.VpnScope -> AppFilter.entries
    AppPickerMode.Blocked -> listOf(AppFilter.All, AppFilter.Selected)
}

/** Готовые к показу секции списка. Считаются один раз на смену входов, а не на каждую перекомпозицию. */
@Immutable
internal data class AppPickerModel(
    val missing: List<String>,
    val suggested: List<InstalledApp>,
    val installed: List<InstalledApp>,
    val installedTitle: String,
) {
    val isEmpty: Boolean get() = missing.isEmpty() && suggested.isEmpty() && installed.isEmpty()
}

internal fun AppsUiState.selectedFor(mode: AppPickerMode): Set<String> = when (mode) {
    AppPickerMode.VpnScope -> allowedPackages
    AppPickerMode.Blocked -> blockedPackages
}

internal fun AppsUiState.missingFor(mode: AppPickerMode): Set<String> = when (mode) {
    AppPickerMode.VpnScope -> missingAllowedPackages
    AppPickerMode.Blocked -> missingBlockedPackages
}

internal fun pickerTitle(mode: AppPickerMode, scopeMode: AppScopeMode): String = when (mode) {
    AppPickerMode.Blocked -> "Блокировать приложения"
    AppPickerMode.VpnScope -> when (scopeMode) {
        AppScopeMode.Include -> "Приложения для VPN"
        AppScopeMode.Exclude -> "Приложения напрямую"
    }
}

/** Поиск по названию и имени пакета без учёта регистра; пустой запрос оставляет всё. */
internal fun filterByQuery(apps: List<InstalledApp>, query: String): List<InstalledApp> {
    val needle = query.trim()
    if (needle.isEmpty()) return apps
    return apps.filter { app ->
        app.label.contains(needle, ignoreCase = true) ||
            app.packageName.contains(needle, ignoreCase = true)
    }
}

/**
 * Раскладывает найденные приложения по секциям.
 *
 * - «Популярные» — только в режиме VPN и независимо от переключателя системных приложений;
 *   в режиме блокировки рекомендации не нужны, такие приложения идут в общий список.
 * - Системные приложения скрыты, пока не включён показ, кроме уже выбранных.
 * - «Выбранные» оставляет только отмеченное и недоступные пакеты; «Популярные» — только секцию рекомендаций.
 */
internal fun buildAppPickerModel(
    matching: List<InstalledApp>,
    selected: Set<String>,
    missing: Set<String>,
    mode: AppPickerMode,
    filter: AppFilter,
    showSystem: Boolean,
): AppPickerModel {
    val onlySelected = filter == AppFilter.Selected
    val suggested = if (mode == AppPickerMode.VpnScope) {
        matching.filter { app ->
            app.suggestion != null && (!onlySelected || app.packageName in selected)
        }
    } else {
        emptyList()
    }
    val installed = if (filter == AppFilter.Popular) {
        emptyList()
    } else {
        matching.filter { app ->
            (mode == AppPickerMode.Blocked || app.suggestion == null) &&
                (!app.system || showSystem || app.packageName in selected) &&
                (!onlySelected || app.packageName in selected)
        }
    }
    return AppPickerModel(
        missing = if (filter == AppFilter.Popular) emptyList() else missing.sorted(),
        suggested = suggested,
        installed = installed,
        installedTitle = if (showSystem) "Все приложения" else "Установленные",
    )
}

/** Буква для круглой заглушки вместо иконки приложения. */
internal fun appMonogram(label: String): String =
    label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

internal fun emptyMessage(query: String, filter: AppFilter): String = when {
    query.isNotBlank() -> "По запросу ничего не найдено."
    filter == AppFilter.Selected -> "Пока ничего не выбрано."
    filter == AppFilter.Popular -> "Рекомендуемых приложений на устройстве нет."
    else -> "Приложения не найдены."
}
