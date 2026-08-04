package io.github.zapretkvn.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnRuntimeMetrics
import io.github.zapretkvn.android.vpn.VpnTestHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Used only by scripts/verify-process-recreation.sh; absent from release builds. */
class Gate6ProcessProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.DEBUG)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val response = runCatching {
                val container = (context.applicationContext as ZapretApplication).container
                when (intent.action) {
                    ACTION_SETUP -> setup(container)
                    ACTION_STATUS -> status(container)
                    ACTION_CLEANUP -> cleanup(container)
                    else -> error("unknown-action")
                }
            }.fold(
                onSuccess = { it },
                onFailure = { "error=${it.javaClass.simpleName}:${it.message.orEmpty().replace(';', ',')}" },
            )
            pending.resultData = response
            pending.finish()
        }
    }

    private suspend fun setup(container: io.github.zapretkvn.android.AppContainer): String {
        cleanup(container)
        container.profileStore.initialize()
        val profile = container.profileStore.create(
            name = PROFILE_NAME,
            rawJson = DIRECT_CONFIG,
            source = ProfileSource.RawJson,
        )
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(SETTINGS_PACKAGE))
        VpnTestHooks.succeedNextHealthCheck()
        container.vpnController.start(profile.id)
        val terminal = withTimeout(30_000) {
            container.vpnController.state.first {
                it is VpnConnectionState.Connected || it is VpnConnectionState.Error
            }
        }
        check(terminal is VpnConnectionState.Connected) { "connect-failed:$terminal" }
        return status(container)
    }

    private suspend fun cleanup(container: io.github.zapretkvn.android.AppContainer): String {
        container.vpnController.setHomeVisible(false)
        container.vpnController.setDiagnosticsVisible(false)
        if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
            container.vpnController.stop()
            withTimeout(20_000) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
        }
        container.profileStore.initialize()
        container.profileStore.profiles.value
            .filter { it.name == PROFILE_NAME }
            .forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
        VpnTestHooks.reset()
        return status(container)
    }

    private fun status(container: io.github.zapretkvn.android.AppContainer): String {
        val snapshot = VpnRuntimeMetrics.snapshot()
        val state = when (container.vpnController.state.value) {
            VpnConnectionState.Stopped -> "stopped"
            is VpnConnectionState.Starting -> "starting"
            is VpnConnectionState.Connected -> "connected"
            is VpnConnectionState.Paused -> "paused"
            is VpnConnectionState.Stopping -> "stopping"
            is VpnConnectionState.Reconnecting -> "reconnecting"
            is VpnConnectionState.Error -> "error"
        }
        return listOf(
            "state=$state",
            "sessions=${snapshot.activeSessions}",
            "core=${snapshot.activeLibboxInstances}",
            "tun=${snapshot.activeTunDescriptors}",
            "callbacks=${snapshot.activeNetworkCallbacks}",
            "statusClients=${snapshot.activeStatusClients}",
            "logClients=${snapshot.activeLogClients}",
            "created=${VpnRuntimeMetrics.libboxCreationCount()}",
        ).joinToString(";")
    }

    companion object {
        const val ACTION_SETUP = "io.github.zapretkvn.android.debug.GATE6_SETUP"
        const val ACTION_STATUS = "io.github.zapretkvn.android.debug.GATE6_STATUS"
        const val ACTION_CLEANUP = "io.github.zapretkvn.android.debug.GATE6_CLEANUP"
        private const val PROFILE_NAME = "__gate6_process_probe__"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val DIRECT_CONFIG = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a","interrupt_exist_connections":true},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """
    }
}
