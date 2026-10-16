package io.github.zapretkvn.android.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.zapretkvn.android.ui.AppPickerMode
import io.github.zapretkvn.core.designsystem.ZapretIcons

enum class AppTab(val title: String, val icon: ImageVector) {
    Home("Главная", ZapretIcons.Power),
    Servers("Серверы", ZapretIcons.Servers),
    Routing("Маршруты", ZapretIcons.Route),
    Settings("Настройки", ZapretIcons.Tune),
}

/** Экраны поверх вкладок. Вкладка — корень стека, остальное кладётся сверху. */
@Immutable
sealed interface Route {
    data class Tab(val tab: AppTab) : Route
    data object Profiles : Route
    data class AppPicker(val mode: AppPickerMode) : Route
    data class SettingsPage(val page: String) : Route
}

/**
 * Маленький стек навигации вместо navigation-compose: экранов немного,
 * ViewModel живут на уровне Activity, а лишние зависимости пришлось бы
 * заносить в verification-metadata.
 *
 * «Назад»: со вложенного экрана — на вкладку, с любой вкладки кроме Главной —
 * на Главную, с Главной — выход (системный обработчик).
 */
class Navigator internal constructor(private val stack: SnapshotStateList<Route>) {
    val top: Route get() = stack.last()

    val tab: AppTab
        get() = (stack.first() as? Route.Tab)?.tab ?: AppTab.Home

    val canGoBack: Boolean
        get() = stack.size > 1 || tab != AppTab.Home

    fun switchTab(target: AppTab) {
        stack.clear()
        stack.add(Route.Tab(target))
    }

    /** Нажатие в нижней панели: повтор на открытой вкладке — «наверх», как в системных приложениях. */
    fun onTabClick(target: AppTab) {
        if (stack.size == 1 && tab == target) {
            reselections[target] = (reselections[target] ?: 0) + 1
        } else {
            switchTab(target)
        }
    }

    private val reselections = mutableStateMapOf<AppTab, Int>()

    /** Сколько раз нажали на уже открытую вкладку [tab]: вкладка прокручивается наверх при росте. */
    fun reselections(tab: AppTab): Int = reselections[tab] ?: 0

    fun push(route: Route) {
        if (stack.last() != route) stack.add(route)
    }

    /** Возвращает false, если уходить некуда и «Назад» должен закрыть приложение. */
    fun back(): Boolean = when {
        stack.size > 1 -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        tab != AppTab.Home -> {
            switchTab(AppTab.Home)
            true
        }
        else -> false
    }

    fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        internal fun encode(route: Route): String = when (route) {
            is Route.Tab -> "tab:${route.tab.name}"
            Route.Profiles -> "profiles"
            is Route.AppPicker -> "apps:${route.mode.name}"
            is Route.SettingsPage -> "settings:${route.page}"
        }

        internal fun decode(value: String): Route? {
            val (kind, arg) = value.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            return when (kind) {
                "tab" -> AppTab.entries.firstOrNull { it.name == arg }?.let(Route::Tab)
                "profiles" -> Route.Profiles
                "apps" -> AppPickerMode.entries.firstOrNull { it.name == arg }?.let(Route::AppPicker)
                "settings" -> arg?.let(Route::SettingsPage)
                else -> null
            }
        }

        internal fun restore(values: List<String>): Navigator {
            val routes = values.mapNotNull(::decode)
            val stack = mutableStateListOf<Route>()
            if (routes.firstOrNull() is Route.Tab) stack.addAll(routes) else stack.add(Route.Tab(AppTab.Home))
            return Navigator(stack)
        }

        val Saver: Saver<Navigator, Any> = listSaver(
            save = { navigator -> navigator.stack.map(::encode) },
            restore = { restore(it) },
        )
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.Saver) {
    Navigator(mutableStateListOf(Route.Tab(AppTab.Home)))
}
