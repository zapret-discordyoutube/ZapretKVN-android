package io.github.zapretkvn.android.apps

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class AppSelection(
    val allowedPackages: Set<String> = emptySet(),
    val blockedPackages: Set<String> = emptySet(),
    val mode: AppScopeMode = AppScopeMode.Include,
    val initialized: Boolean = false,
)

internal fun AppSelection.selectedPackagesForTunBoundary(): Set<String> = when (mode) {
    AppScopeMode.Include -> allowedPackages + blockedPackages
    AppScopeMode.Exclude -> allowedPackages
}

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
            val blockedPackages = normalizePackageNames(
                preferences[BLOCKED_PACKAGES].orEmpty(),
                appContext.packageName,
            )
            AppSelection(
                allowedPackages = normalizePackageNames(
                    preferences[ALLOWED_PACKAGES].orEmpty(),
                    appContext.packageName,
                ) - blockedPackages,
                blockedPackages = blockedPackages,
                mode = preferences[SCOPE_MODE]
                    ?.let { stored -> AppScopeMode.entries.firstOrNull { it.name == stored } }
                    ?: AppScopeMode.Include,
                initialized = preferences[INITIALIZED] ?: false,
            )
        }

    suspend fun initializeIfNeeded(suggestedPackages: Set<String>) {
        dataStore.edit { preferences ->
            if (preferences[INITIALIZED] != true) {
                val suggested = normalizePackageNames(suggestedPackages, appContext.packageName)
                preferences[ALLOWED_PACKAGES] = suggested
                preferences[OFFERED_SUGGESTIONS] = suggested
                preferences[INITIALIZED] = true
            }
        }
    }

    /**
     * Первый запуск выбирает установленные рекомендуемые приложения; каждый следующий
     * добавляет только те, что появились позже (второй Telegram, ZaStoGram), — по правилу
     * [adoptSuggestions]. Возвращает добавленные сейчас пакеты, чтобы их назвать пользователю.
     */
    suspend fun adoptSuggestions(installedSuggested: Set<String>): Set<String> {
        var adopted = emptySet<String>()
        dataStore.edit { preferences ->
            val suggested = normalizePackageNames(installedSuggested, appContext.packageName)
            if (preferences[INITIALIZED] != true) {
                preferences[ALLOWED_PACKAGES] = suggested
                preferences[OFFERED_SUGGESTIONS] = suggested
                preferences[INITIALIZED] = true
                return@edit
            }
            val result = adoptSuggestions(
                installedSuggested = suggested,
                allowed = normalizePackageNames(preferences[ALLOWED_PACKAGES].orEmpty(), appContext.packageName),
                blocked = normalizePackageNames(preferences[BLOCKED_PACKAGES].orEmpty(), appContext.packageName),
                offered = preferences[OFFERED_SUGGESTIONS],
                mode = preferences[SCOPE_MODE]
                    ?.let { stored -> AppScopeMode.entries.firstOrNull { it.name == stored } }
                    ?: AppScopeMode.Include,
            )
            preferences[ALLOWED_PACKAGES] = result.allowed
            preferences[OFFERED_SUGGESTIONS] = result.offered
            adopted = result.adopted
        }
        return adopted
    }

    /**
     * The Gemini assistant lives inside the Google app. Users who already routed Gemini
     * through the VPN get the Google app added once; a later manual choice is kept.
     */
    suspend fun migrateGeminiCompanionIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[GEMINI_COMPANION_MIGRATED] == true) return@edit
            if (preferences[INITIALIZED] == true) {
                preferences[ALLOWED_PACKAGES] = withGeminiCompanion(
                    allowed = preferences[ALLOWED_PACKAGES].orEmpty(),
                    blocked = preferences[BLOCKED_PACKAGES].orEmpty(),
                )
            }
            preferences[GEMINI_COMPANION_MIGRATED] = true
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
                if (allowed) {
                    packages += normalized
                    val blocked = normalizePackageNames(
                        preferences[BLOCKED_PACKAGES].orEmpty(),
                        appContext.packageName,
                    ).toMutableSet()
                    blocked -= normalized
                    preferences[BLOCKED_PACKAGES] = blocked
                } else {
                    packages -= normalized
                }
            }
            preferences[ALLOWED_PACKAGES] = packages
            preferences[INITIALIZED] = true
        }
    }

    suspend fun setBlocked(packageName: String, blocked: Boolean) {
        dataStore.edit { preferences ->
            val packages = normalizePackageNames(
                preferences[BLOCKED_PACKAGES].orEmpty(),
                appContext.packageName,
            ).toMutableSet()
            val normalized = packageName.trim()
            if (normalized.isNotEmpty() && normalized != appContext.packageName) {
                if (blocked) {
                    packages += normalized
                    val allowed = normalizePackageNames(
                        preferences[ALLOWED_PACKAGES].orEmpty(),
                        appContext.packageName,
                    ).toMutableSet()
                    allowed -= normalized
                    preferences[ALLOWED_PACKAGES] = allowed
                } else {
                    packages -= normalized
                }
            }
            preferences[BLOCKED_PACKAGES] = packages
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
            val normalized = normalizePackageNames(
                packages,
                appContext.packageName,
            )
            preferences[ALLOWED_PACKAGES] = normalized
            preferences[BLOCKED_PACKAGES] = normalizePackageNames(
                preferences[BLOCKED_PACKAGES].orEmpty(),
                appContext.packageName,
            ) - normalized
            preferences[INITIALIZED] = initialized
        }
    }

    suspend fun replaceBlocklist(packages: Set<String>) {
        dataStore.edit { preferences ->
            val normalized = normalizePackageNames(
                packages,
                appContext.packageName,
            )
            preferences[BLOCKED_PACKAGES] = normalized
            preferences[ALLOWED_PACKAGES] = normalizePackageNames(
                preferences[ALLOWED_PACKAGES].orEmpty(),
                appContext.packageName,
            ) - normalized
            preferences[INITIALIZED] = true
        }
    }

    private companion object {
        val ALLOWED_PACKAGES = stringSetPreferencesKey("allowed_packages")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        val INITIALIZED = booleanPreferencesKey("initialized")
        val SCOPE_MODE = stringPreferencesKey("scope_mode")
        val GEMINI_COMPANION_MIGRATED = booleanPreferencesKey("gemini_companion_migrated")
        val OFFERED_SUGGESTIONS = stringSetPreferencesKey("offered_suggestions")
    }
}

internal data class SuggestionAdoption(
    val allowed: Set<String>,
    val offered: Set<String>,
    val adopted: Set<String>,
)

/**
 * Рекомендуемое приложение, которое пользователю ещё не предлагали, само попадает в VPN.
 *
 * До 0.5.5 выбор делался один раз, при первом запуске: Telegram-клиент, поставленный позже,
 * оставался вне VPN, и пользователь видел «один Telegram работает, второй нет» (15.10.2026).
 * [offered] — что уже предлагалось; снятую пользователем галочку правило не возвращает.
 * `null` значит, что версия с этим учётом запускается впервые: уже предложенным считается
 * только текущий список, поэтому пропущенные раньше рекомендуемые приложения добавятся один раз.
 * В режиме «Все кроме» новое приложение и так идёт через VPN — оно только запоминается.
 * Заблокированное пользователем приложение не добавляется.
 */
internal fun adoptSuggestions(
    installedSuggested: Set<String>,
    allowed: Set<String>,
    blocked: Set<String>,
    offered: Set<String>?,
    mode: AppScopeMode,
): SuggestionAdoption {
    val known = offered ?: allowed
    val fresh = installedSuggested - known - allowed
    val adopted = if (mode == AppScopeMode.Include) fresh - blocked else emptySet()
    return SuggestionAdoption(
        allowed = allowed + adopted,
        offered = known + installedSuggested,
        adopted = adopted,
    )
}

internal const val GEMINI_PACKAGE = "com.google.android.apps.bard"
internal const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

internal fun withGeminiCompanion(allowed: Set<String>, blocked: Set<String>): Set<String> =
    if (GEMINI_PACKAGE in allowed && GOOGLE_APP_PACKAGE !in blocked) {
        allowed + GOOGLE_APP_PACKAGE
    } else {
        allowed
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
