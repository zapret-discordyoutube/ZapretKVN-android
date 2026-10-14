package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.ui.UiSettings
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import kotlinx.coroutines.flow.Flow

/** То, что контроллерам профилей нужно от VPN; позволяет проверять их без Android. */
internal interface ProfilesVpnPort {
    /** Профиль, к которому VPN подключён прямо сейчас, или null. */
    fun connectedProfileId(): String?

    fun switchProfileIfConnected(profileId: String): Boolean

    fun restartIfConnected(reason: String)

    fun stop()

    fun selectOutbound(profileId: String, groupTag: String, serverTag: String)
}

internal fun ProfilesVpnPort.isConnectedTo(profileId: String): Boolean =
    connectedProfileId() == profileId

internal class VpnControllerPort(private val controller: VpnController) : ProfilesVpnPort {
    override fun connectedProfileId(): String? =
        (controller.state.value as? VpnConnectionState.Connected)?.profileId

    override fun switchProfileIfConnected(profileId: String): Boolean =
        controller.switchProfileIfConnected(profileId)

    override fun restartIfConnected(reason: String) = controller.restartIfConnected(reason)

    override fun stop() = controller.stop()

    override fun selectOutbound(profileId: String, groupTag: String, serverTag: String) =
        controller.selectOutbound(profileId, groupTag, serverTag)
}

/** Часть настроек, которой пользуются сценарии профилей (активный профиль, DNS-режим, HWID). */
internal interface ProfilesSettingsPort {
    val settings: Flow<UiSettings>

    suspend fun setActiveProfile(id: String?)

    suspend fun setDnsMode(mode: DnsMode)

    suspend fun subscriptionDeviceId(): String
}

internal class UiSettingsStorePort(private val store: UiSettingsStore) : ProfilesSettingsPort {
    override val settings: Flow<UiSettings> get() = store.settings

    override suspend fun setActiveProfile(id: String?) = store.setActiveProfile(id)

    override suspend fun setDnsMode(mode: DnsMode) = store.setDnsMode(mode)

    override suspend fun subscriptionDeviceId(): String = store.subscriptionDeviceId()
}
