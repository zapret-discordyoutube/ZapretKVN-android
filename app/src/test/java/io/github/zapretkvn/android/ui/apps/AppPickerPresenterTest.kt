package io.github.zapretkvn.android.ui.apps

import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.apps.AppsUiState
import io.github.zapretkvn.android.apps.InstalledApp
import io.github.zapretkvn.android.ui.AppPickerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerPresenterTest {
    private val telegram = InstalledApp("org.telegram.messenger", "Telegram", system = false, enabled = true, suggestion = "Telegram")
    private val notes = InstalledApp("com.example.notes", "Заметки", system = false, enabled = true)
    private val browser = InstalledApp("com.example.browser", "Browser", system = false, enabled = true)
    private val settings = InstalledApp("com.android.settings", "Settings", system = true, enabled = true)
    private val apps = listOf(telegram, notes, browser, settings)

    private fun model(
        selected: Set<String> = emptySet(),
        missing: Set<String> = emptySet(),
        mode: AppPickerMode = AppPickerMode.VpnScope,
        filter: AppFilter = AppFilter.All,
        showSystem: Boolean = false,
        list: List<InstalledApp> = apps,
    ) = buildAppPickerModel(list, selected, missing, mode, filter, showSystem)

    private fun List<InstalledApp>.pkgs() = map { it.packageName }

    @Test
    fun suggestedAppsGoToTheirOwnSectionInVpnMode() {
        val result = model()
        assertEquals(listOf(telegram.packageName), result.suggested.pkgs())
        assertEquals(listOf(notes.packageName, browser.packageName), result.installed.pkgs())
    }

    @Test
    fun systemAppsAreHiddenUntilShownOrSelected() {
        assertEquals(false, model().installed.any { it.system })
        assertTrue(model(showSystem = true).installed.any { it.system })
        assertTrue(model(selected = setOf(settings.packageName)).installed.any { it.system })
    }

    @Test
    fun installedTitleFollowsSystemToggle() {
        assertEquals("Установленные", model().installedTitle)
        assertEquals("Все приложения", model(showSystem = true).installedTitle)
    }

    @Test
    fun blockedModeHasNoSuggestionsSection() {
        val result = model(mode = AppPickerMode.Blocked)
        assertTrue(result.suggested.isEmpty())
        assertEquals(
            listOf(telegram.packageName, notes.packageName, browser.packageName),
            result.installed.pkgs(),
        )
    }

    @Test
    fun selectedFilterKeepsOnlyMarkedAndMissing() {
        val result = model(
            selected = setOf(notes.packageName, "gone.pkg"),
            missing = setOf("gone.pkg"),
            filter = AppFilter.Selected,
        )
        assertTrue(result.suggested.isEmpty())
        assertEquals(listOf(notes.packageName), result.installed.pkgs())
        assertEquals(listOf("gone.pkg"), result.missing)
    }

    @Test
    fun popularFilterShowsOnlySuggestions() {
        val result = model(filter = AppFilter.Popular, missing = setOf("gone.pkg"))
        assertEquals(listOf(telegram.packageName), result.suggested.pkgs())
        assertTrue(result.installed.isEmpty())
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun missingPackagesAreSorted() {
        assertEquals(listOf("a.pkg", "b.pkg"), model(missing = setOf("b.pkg", "a.pkg")).missing)
    }

    @Test
    fun emptyModelIsDetected() {
        assertTrue(model(list = emptyList()).isEmpty)
        assertEquals(false, model().isEmpty)
    }

    @Test
    fun queryMatchesLabelAndPackageIgnoringCase() {
        assertEquals(listOf(telegram), filterByQuery(apps, "TELEGRAM"))
        assertEquals(listOf(settings), filterByQuery(apps, "android.settings"))
        assertEquals(listOf(notes), filterByQuery(apps, "  заметки "))
        assertEquals(apps, filterByQuery(apps, "   "))
    }

    @Test
    fun selectedAndMissingSetsDependOnMode() {
        val state = AppsUiState(
            apps = listOf(notes),
            allowedPackages = setOf(notes.packageName, "gone.allowed"),
            blockedPackages = setOf("gone.blocked"),
            catalogLoaded = true,
        )
        assertEquals(state.allowedPackages, state.selectedFor(AppPickerMode.VpnScope))
        assertEquals(state.blockedPackages, state.selectedFor(AppPickerMode.Blocked))
        assertEquals(setOf("gone.allowed"), state.missingFor(AppPickerMode.VpnScope))
        assertEquals(setOf("gone.blocked"), state.missingFor(AppPickerMode.Blocked))
    }

    @Test
    fun titlesFollowModeAndScope() {
        assertEquals("Приложения для VPN", pickerTitle(AppPickerMode.VpnScope, AppScopeMode.Include))
        assertEquals("Приложения напрямую", pickerTitle(AppPickerMode.VpnScope, AppScopeMode.Exclude))
        assertEquals("Блокировать приложения", pickerTitle(AppPickerMode.Blocked, AppScopeMode.Include))
    }

    @Test
    fun filtersOfferPopularOnlyForVpnMode() {
        assertEquals(AppFilter.entries, availableFilters(AppPickerMode.VpnScope))
        assertEquals(listOf(AppFilter.All, AppFilter.Selected), availableFilters(AppPickerMode.Blocked))
    }

    @Test
    fun monogramIsFirstLetterOrQuestionMark() {
        assertEquals("T", appMonogram("telegram"))
        assertEquals("З", appMonogram("заметки"))
        assertEquals("1", appMonogram("  1Password"))
        assertEquals("?", appMonogram("···"))
        assertEquals("?", appMonogram(""))
    }

    @Test
    fun emptyMessagesExplainWhy() {
        assertEquals("По запросу ничего не найдено.", emptyMessage("abc", AppFilter.All))
        assertEquals("Пока ничего не выбрано.", emptyMessage("", AppFilter.Selected))
        assertEquals("Приложения не найдены.", emptyMessage("", AppFilter.All))
    }

    @Test
    fun noteListsSuggestionSystemAndDisabled() {
        val app = InstalledApp("p", "L", system = true, enabled = false, suggestion = "Telegram")
        assertEquals("Рекомендуем: Telegram · Системное · Отключено в Android", appNote(app, showSuggestion = true))
        assertEquals("Системное · Отключено в Android", appNote(app, showSuggestion = false))
    }
}
