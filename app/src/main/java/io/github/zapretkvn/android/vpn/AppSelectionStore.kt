package io.github.zapretkvn.android.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class AppScopeMode {
    Include,
    Exclude,
}

data class AppSelection(
    val allowedPackages: Set<String> = emptySet(),
    val mode: AppScopeMode = AppScopeMode.Include,
    val initialized: Boolean = false,
)

private val Context.vpnScopeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vpn_scope",
)

class AppSelectionStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.vpnScopeDataStore

    val selection: Flow<AppSelection> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSelection(
                allowedPackages = normalizePackageNames(
                    preferences[ALLOWED_PACKAGES].orEmpty(),
                    appContext.packageName,
                ),
                mode = preferences[SCOPE_MODE]
                    ?.let { stored -> AppScopeMode.entries.firstOrNull { it.name == stored } }
                    ?: AppScopeMode.Include,
                initialized = preferences[INITIALIZED] ?: false,
            )
        }

    suspend fun initializeIfNeeded(
        suggestedPackages: Set<String>,
        suggestedPackageMigrations: Map<Int, Set<String>> = emptyMap(),
        suggestionRevision: Int = 0,
    ) {
        dataStore.edit { preferences ->
            val initialized = preferences[INITIALIZED] == true
            val storedSuggestionRevision = preferences[SUGGESTION_REVISION] ?: 0
            val mode = preferences[SCOPE_MODE]
                ?.let { stored -> AppScopeMode.entries.firstOrNull { it.name == stored } }
                ?: AppScopeMode.Include
            preferences[ALLOWED_PACKAGES] = mergeSuggestedPackages(
                currentPackages = preferences[ALLOWED_PACKAGES].orEmpty(),
                suggestedPackages = suggestedPackages,
                newlySuggestedPackages = pendingSuggestedPackages(
                    suggestedPackageMigrations = suggestedPackageMigrations,
                    storedSuggestionRevision = storedSuggestionRevision,
                    suggestionRevision = suggestionRevision,
                ),
                initialized = initialized,
                mode = mode,
                storedSuggestionRevision = storedSuggestionRevision,
                suggestionRevision = suggestionRevision,
                ownPackageName = appContext.packageName,
            )
            preferences[INITIALIZED] = true
            preferences[SUGGESTION_REVISION] = maxOf(
                preferences[SUGGESTION_REVISION] ?: 0,
                suggestionRevision,
            )
        }
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        dataStore.edit { preferences ->
            val packages = normalizePackageNames(
                preferences[ALLOWED_PACKAGES].orEmpty(),
                appContext.packageName,
            ).toMutableSet()
            val normalized = packageName.trim()
            if (normalized.isNotEmpty() && normalized != appContext.packageName) {
                if (allowed) packages += normalized else packages -= normalized
            }
            preferences[ALLOWED_PACKAGES] = packages
            preferences[INITIALIZED] = true
        }
    }

    suspend fun setMode(mode: AppScopeMode) {
        dataStore.edit { preferences ->
            preferences[SCOPE_MODE] = mode.name
            preferences[INITIALIZED] = true
        }
    }

    suspend fun replaceAllowlist(
        packages: Set<String>,
        initialized: Boolean = true,
    ) {
        dataStore.edit { preferences ->
            preferences[ALLOWED_PACKAGES] = normalizePackageNames(
                packages,
                appContext.packageName,
            )
            preferences[INITIALIZED] = initialized
        }
    }

    private companion object {
        val ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        val INITIALIZED = booleanPreferencesKey("initialized")
        val SCOPE_MODE = stringPreferencesKey("scope_mode")
        val SUGGESTION_REVISION = intPreferencesKey("suggestion_revision")
    }
}

internal fun pendingSuggestedPackages(
    suggestedPackageMigrations: Map<Int, Set<String>>,
    storedSuggestionRevision: Int,
    suggestionRevision: Int,
): Set<String> = suggestedPackageMigrations
    .asSequence()
    .filter { (revision, _) ->
        revision > storedSuggestionRevision && revision <= suggestionRevision
    }
    .flatMap { (_, packages) -> packages.asSequence() }
    .toSet()

internal fun mergeSuggestedPackages(
    currentPackages: Set<String>,
    suggestedPackages: Set<String>,
    newlySuggestedPackages: Set<String>,
    initialized: Boolean,
    mode: AppScopeMode,
    storedSuggestionRevision: Int,
    suggestionRevision: Int,
    ownPackageName: String? = null,
): Set<String> {
    val packages = when {
        !initialized -> suggestedPackages
        mode == AppScopeMode.Include && storedSuggestionRevision < suggestionRevision ->
            currentPackages + newlySuggestedPackages
        else -> currentPackages
    }
    return normalizePackageNames(packages, ownPackageName)
}

internal fun normalizePackageNames(
    packages: Iterable<String>,
    ownPackageName: String? = null,
): Set<String> = packages
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .filterNot { it == ownPackageName }
    .toSortedSet()
