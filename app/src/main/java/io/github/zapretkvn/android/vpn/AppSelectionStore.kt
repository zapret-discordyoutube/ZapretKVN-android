package io.github.zapretkvn.android.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

    suspend fun initializeIfNeeded(suggestedPackages: Set<String>) {
        dataStore.edit { preferences ->
            if (preferences[INITIALIZED] != true) {
                preferences[ALLOWED_PACKAGES] = normalizePackageNames(
                    suggestedPackages,
                    appContext.packageName,
                )
                preferences[INITIALIZED] = true
            }
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
    }
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
