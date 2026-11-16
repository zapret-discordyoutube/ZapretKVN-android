package io.github.zapretkvn.android.masking

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zapretkvn.masking.codec.MaskingCodec
import io.github.zapretkvn.masking.merge.MaskingSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.maskingDataStore: DataStore<Preferences> by preferencesDataStore(name = "masking")

/**
 * Masking settings ("Маскировка"): one JSON document in the shared format of the `:masking`
 * module (the same as Windows). Keys written by a newer build are carried over on every write,
 * so a rollback by the updater keeps them. Read once per session start, like the other
 * session settings.
 */
class MaskingSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.maskingDataStore

    val settings: Flow<MaskingSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> MaskingCodec.decodeString(preferences[SETTINGS_JSON]) }

    suspend fun update(transform: (MaskingSettings) -> MaskingSettings) {
        dataStore.edit { preferences ->
            val previous = preferences[SETTINGS_JSON]
            val next = transform(MaskingCodec.decodeString(previous))
            preferences[SETTINGS_JSON] = MaskingCodec.encodeString(next, previous)
        }
    }

    private companion object {
        val SETTINGS_JSON = stringPreferencesKey("settings_json")
    }
}
