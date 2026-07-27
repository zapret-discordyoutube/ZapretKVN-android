package io.github.zapretkvn.android.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.FileProvider
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class AndroidUpdateInstallIntentFactory(context: Context) : UpdateInstallIntentFactory {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    override fun create(file: java.io.File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("Zapret KVN update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/** Integrates the isolated updater module with a short-lived, package-scoped VPN route. */
class AppUpdateVpnFallback(
    context: Context,
    private val settingsStore: UiSettingsStore,
    private val vpnController: VpnController,
) : UpdateVpnFallback {
    private val appContext = context.applicationContext

    override suspend fun connect(): UpdateVpnSession {
        if (VpnService.prepare(appContext) != null) {
            throw UpdateException("сначала разрешите VPN и подключите профиль вручную")
        }
        val before = stableState()
        val profileId = (before as? VpnConnectionState.Connected)?.profileId
            ?: settingsStore.settings.first().activeProfileId
            ?: throw UpdateException("не выбран VPN-профиль")
        if (before is VpnConnectionState.Connected && before.updaterRouting) {
            return UpdateVpnSession { }
        }

        val restoreConnectedVpn = before is VpnConnectionState.Connected
        try {
            if (restoreConnectedVpn) {
                vpnController.restartUpdaterRouting(profileId, enabled = true)
            } else {
                vpnController.startForUpdater(profileId)
            }
        } catch (error: RuntimeException) {
            throw UpdateException("Android не разрешил запустить временный VPN-маршрут", error)
        }

        val connected = try {
            withTimeoutOrNull(VPN_TRANSITION_TIMEOUT_MILLIS) {
                vpnController.state.first { state ->
                    state is VpnConnectionState.Error && state !== before ||
                        state is VpnConnectionState.Connected &&
                        state.profileId == profileId &&
                        state.updaterRouting
                }
            }
        } catch (cancelled: CancellationException) {
            restoreAfterFailedConnect(profileId, restoreConnectedVpn)
            throw cancelled
        }
        if (connected !is VpnConnectionState.Connected) {
            restoreAfterFailedConnect(profileId, restoreConnectedVpn)
            val detail = (connected as? VpnConnectionState.Error)?.message
                ?: "VPN не подключился за отведённое время"
            throw UpdateException(detail)
        }

        return RestoringSession(
            vpnController = vpnController,
            profileId = profileId,
            restoreConnectedVpn = restoreConnectedVpn,
        )
    }

    private fun restoreAfterFailedConnect(profileId: String, restoreConnectedVpn: Boolean) {
        if (restoreConnectedVpn) {
            runCatching { vpnController.restartUpdaterRouting(profileId, enabled = false) }
        } else {
            runCatching(vpnController::stop)
        }
    }

    private suspend fun stableState(): VpnConnectionState {
        val current = vpnController.state.value
        if (
            current !is VpnConnectionState.Starting &&
            current !is VpnConnectionState.Stopping &&
            current !is VpnConnectionState.Reconnecting
        ) {
            return current
        }
        return withTimeoutOrNull(VPN_TRANSITION_TIMEOUT_MILLIS) {
            vpnController.state.first { state ->
                state is VpnConnectionState.Connected ||
                    state is VpnConnectionState.Stopped ||
                    state is VpnConnectionState.Error
            }
        } ?: current
    }

    private class RestoringSession(
        private val vpnController: VpnController,
        private val profileId: String,
        private val restoreConnectedVpn: Boolean,
    ) : UpdateVpnSession {
        private val closed = AtomicBoolean(false)

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            val current = vpnController.state.value
            val ownsCurrentRoute = when (current) {
                is VpnConnectionState.Connected ->
                    current.profileId == profileId && current.updaterRouting
                is VpnConnectionState.Starting ->
                    current.profileId == profileId && current.updaterRouting
                else -> false
            }
            if (!ownsCurrentRoute) {
                return
            }
            if (restoreConnectedVpn) {
                runCatching { vpnController.restartUpdaterRouting(profileId, enabled = false) }
                withTimeoutOrNull(VPN_TRANSITION_TIMEOUT_MILLIS) {
                    vpnController.state.first { state ->
                        state is VpnConnectionState.Error ||
                            state is VpnConnectionState.Connected &&
                            state.profileId == profileId &&
                            !state.updaterRouting
                    }
                }
            } else {
                runCatching(vpnController::stop)
                withTimeoutOrNull(VPN_STOP_TIMEOUT_MILLIS) {
                    vpnController.state.first { state ->
                        state is VpnConnectionState.Stopped || state is VpnConnectionState.Error
                    }
                }
            }
        }
    }

    private companion object {
        const val VPN_TRANSITION_TIMEOUT_MILLIS = 35_000L
        const val VPN_STOP_TIMEOUT_MILLIS = 10_000L
    }
}
