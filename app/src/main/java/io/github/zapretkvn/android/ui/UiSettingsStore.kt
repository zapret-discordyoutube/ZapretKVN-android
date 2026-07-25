package io.github.zapretkvn.android.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.DnsOverride
import io.github.zapretkvn.android.hardening.TunMtuMode
import io.github.zapretkvn.android.hardening.VpnHidingOptions
import io.github.zapretkvn.android.updates.UpdateChannel
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    System,
    Light,
    Dark,
    Amoled,
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val activeProfileId: String? = null,
    val rawEditorLineWrap: Boolean = false,
    val dnsMode: DnsMode = DnsMode.FromJson,
    val proxyIpv4Only: Boolean = true,
    val dnsOverride: DnsOverride = DnsOverride(),
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
    val vpnHiding: VpnHidingOptions = VpnHidingOptions(),
)

private val Context.uiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ui_settings",
)

class UiSettingsStore(
    context: Context,
    private val buildDefaultUpdateChannel: UpdateChannel =
        UpdateChannel.valueOf(BuildConfig.DEFAULT_UPDATE_CHANNEL),
    private val updateChannelBuildId: String =
        "${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}",
) {
    private val dataStore = context.applicationContext.uiSettingsDataStore

    val settings: Flow<UiSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            UiSettings(
                themeMode = preferences[THEME_MODE]
                    ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                    ?: ThemeMode.System,
                activeProfileId = preferences[ACTIVE_PROFILE_ID],
                rawEditorLineWrap = preferences[RAW_EDITOR_LINE_WRAP] ?: false,
                dnsMode = preferences[DNS_MODE]
                    ?.let { stored -> DnsMode.entries.firstOrNull { it.name == stored } }
                    ?: DnsMode.FromJson,
                proxyIpv4Only = preferences[PROXY_IPV4_ONLY] ?: true,
                dnsOverride = DnsOverride(
                    enabled = preferences[DNS_OVERRIDE_ENABLED] ?: true,
                    hostname = preferences[DNS_OVERRIDE_HOSTNAME] ?: DnsOverride.DEFAULT_HOSTNAME,
                    ipv4Address = preferences[DNS_OVERRIDE_IPV4] ?: DnsOverride.DEFAULT_IPV4_ADDRESS,
                ),
                updateChannel = resolveUpdateChannel(
                    storedChannel = preferences[UPDATE_CHANNEL],
                    selectedForBuildId = preferences[UPDATE_CHANNEL_BUILD_ID],
                    currentBuildId = updateChannelBuildId,
                    buildDefault = buildDefaultUpdateChannel,
                ),
                vpnHiding = VpnHidingOptions(
                    blockLocalEndpoints = preferences[VPN_HIDING_BLOCK_LOCAL_ENDPOINTS] ?: true,
                    neutralSessionName = preferences[VPN_HIDING_NEUTRAL_SESSION_NAME] ?: false,
                    tunMtuMode = preferences[VPN_HIDING_TUN_MTU_MODE]
                        ?.let { stored -> TunMtuMode.entries.firstOrNull { it.name == stored } }
                        ?: TunMtuMode.Normalize1500,
                ),
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setActiveProfile(id: String?) {
        dataStore.edit { preferences ->
            if (id == null) preferences.remove(ACTIVE_PROFILE_ID)
            else preferences[ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setRawEditorLineWrap(enabled: Boolean) {
        dataStore.edit { it[RAW_EDITOR_LINE_WRAP] = enabled }
    }

    suspend fun setDnsMode(mode: DnsMode) {
        dataStore.edit { it[DNS_MODE] = mode.name }
    }

    suspend fun setProxyIpv4Only(enabled: Boolean) {
        dataStore.edit { it[PROXY_IPV4_ONLY] = enabled }
    }

    suspend fun setDnsOverrideEnabled(enabled: Boolean) {
        dataStore.edit { it[DNS_OVERRIDE_ENABLED] = enabled }
    }

    suspend fun setDnsOverride(hostname: String, ipv4Address: String) {
        val normalized = requireNotNull(DnsOverride.normalizedOrNull(hostname, ipv4Address)) {
            "Invalid DNS override"
        }
        dataStore.edit {
            it[DNS_OVERRIDE_HOSTNAME] = normalized.hostname
            it[DNS_OVERRIDE_IPV4] = normalized.ipv4Address
        }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit {
            it[UPDATE_CHANNEL] = channel.name
            it[UPDATE_CHANNEL_BUILD_ID] = updateChannelBuildId
        }
    }

    suspend fun setVpnHidingBlockLocalEndpoints(enabled: Boolean) {
        dataStore.edit { it[VPN_HIDING_BLOCK_LOCAL_ENDPOINTS] = enabled }
    }

    suspend fun setVpnHidingNeutralSessionName(enabled: Boolean) {
        dataStore.edit { it[VPN_HIDING_NEUTRAL_SESSION_NAME] = enabled }
    }

    suspend fun setVpnHidingTunMtuMode(mode: TunMtuMode) {
        dataStore.edit { it[VPN_HIDING_TUN_MTU_MODE] = mode.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val RAW_EDITOR_LINE_WRAP = booleanPreferencesKey("raw_editor_line_wrap")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val PROXY_IPV4_ONLY = booleanPreferencesKey("proxy_ipv4_only")
        val DNS_OVERRIDE_ENABLED = booleanPreferencesKey("dns_override_enabled")
        val DNS_OVERRIDE_HOSTNAME = stringPreferencesKey("dns_override_hostname")
        val DNS_OVERRIDE_IPV4 = stringPreferencesKey("dns_override_ipv4")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val UPDATE_CHANNEL_BUILD_ID = stringPreferencesKey("update_channel_build_id")
        val VPN_HIDING_BLOCK_LOCAL_ENDPOINTS =
            booleanPreferencesKey("vpn_hiding_block_local_endpoints")
        val VPN_HIDING_NEUTRAL_SESSION_NAME =
            booleanPreferencesKey("vpn_hiding_neutral_session_name")
        val VPN_HIDING_TUN_MTU_MODE = stringPreferencesKey("vpn_hiding_tun_mtu_mode")
    }
}

internal fun resolveUpdateChannel(
    storedChannel: String?,
    selectedForBuildId: String?,
    currentBuildId: String,
    buildDefault: UpdateChannel,
): UpdateChannel {
    if (selectedForBuildId != currentBuildId) return buildDefault
    return storedChannel
        ?.let { stored -> UpdateChannel.entries.firstOrNull { it.name == stored } }
        ?: buildDefault
}
