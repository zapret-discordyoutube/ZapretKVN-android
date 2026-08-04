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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Debug-only lifecycle probe driven by scripts/verify-gate8-stress.sh. */
class Gate8StressProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.DEBUG)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val response = runCatching {
                val container = (context.applicationContext as ZapretApplication).container
                when (intent.action) {
                    ACTION_PREPARE -> prepare(container)
                    ACTION_CONNECT -> connect(container)
                    ACTION_ARM_RESTART -> armRestart(container)
                    ACTION_STOP -> stop(container)
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

    private suspend fun prepare(container: io.github.zapretkvn.android.AppContainer): String {
        cleanup(container)
        container.profileStore.initialize()
        val profile = container.profileStore.create(PROFILE_NAME, DIRECT_CONFIG, ProfileSource.RawJson)
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(SETTINGS_PACKAGE))
        return status(container)
    }

    private suspend fun connect(container: io.github.zapretkvn.android.AppContainer): String {
        check(container.vpnController.state.value is VpnConnectionState.Stopped) {
            "connect-requires-stopped:${container.vpnController.state.value}"
        }
        container.profileStore.initialize()
        val profile = container.profileStore.profiles.value.single { it.name == PROFILE_NAME }
        VpnTestHooks.succeedNextHealthCheck()
        container.vpnController.start(profile.id)
        val terminal = withTimeout(CONNECTION_TIMEOUT_MILLIS) {
            container.vpnController.state.first {
                it is VpnConnectionState.Connected || it is VpnConnectionState.Error
            }
        }
        check(terminal is VpnConnectionState.Connected) { "connect-failed:$terminal" }
        awaitResources(active = true)
        return status(container)
    }

    private fun armRestart(container: io.github.zapretkvn.android.AppContainer): String {
        check(container.vpnController.state.value is VpnConnectionState.Connected) {
            "restart-requires-connected:${container.vpnController.state.value}"
        }
        VpnTestHooks.succeedNextHealthCheck()
        return status(container)
    }

    private suspend fun stop(container: io.github.zapretkvn.android.AppContainer): String {
        if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
            container.vpnController.stop()
            withTimeout(STOP_TIMEOUT_MILLIS) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
        }
        awaitResources(active = false)
        return status(container)
    }

    private suspend fun cleanup(container: io.github.zapretkvn.android.AppContainer): String {
        stop(container)
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

    private suspend fun awaitResources(active: Boolean) = withTimeout(STOP_TIMEOUT_MILLIS) {
        while (true) {
            val snapshot = VpnRuntimeMetrics.snapshot()
            if ((active && snapshot.activeSessions == 1 &&
                    snapshot.activeLibboxInstances == 1 &&
                    snapshot.activePlatformAdapters == 1 &&
                    snapshot.activeTunDescriptors == 1 &&
                    snapshot.activeNetworkCallbacks == 1
                ) || (!active && snapshot.isIdle)
            ) {
                return@withTimeout
            }
            delay(20)
        }
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
            "transport=${container.vpnController.diagnostics.value.network?.transport ?: "none"}",
            "sessions=${snapshot.activeSessions}",
            "core=${snapshot.activeLibboxInstances}",
            "tun=${snapshot.activeTunDescriptors}",
            "callbacks=${snapshot.activeNetworkCallbacks}",
            "created=${VpnRuntimeMetrics.libboxCreationCount()}",
            "callbackRegistrations=${VpnRuntimeMetrics.callbackRegistrationCount()}",
            "fds=${File("/proc/self/fd").list().orEmpty().size}",
            "threads=${File("/proc/self/task").list().orEmpty().size}",
        ).joinToString(";")
    }

    companion object {
        const val ACTION_PREPARE = "io.github.zapretkvn.android.debug.GATE8_PREPARE"
        const val ACTION_CONNECT = "io.github.zapretkvn.android.debug.GATE8_CONNECT"
        const val ACTION_ARM_RESTART = "io.github.zapretkvn.android.debug.GATE8_ARM_RESTART"
        const val ACTION_STOP = "io.github.zapretkvn.android.debug.GATE8_STOP"
        const val ACTION_STATUS = "io.github.zapretkvn.android.debug.GATE8_STATUS"
        const val ACTION_CLEANUP = "io.github.zapretkvn.android.debug.GATE8_CLEANUP"
        private const val CONNECTION_TIMEOUT_MILLIS = 30_000L
        private const val STOP_TIMEOUT_MILLIS = 20_000L
        private const val PROFILE_NAME = "__gate8_stress_probe__"
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
