package io.github.zapretkvn.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.vpn.AppScopeMode
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnRuntimeMetrics
import io.github.zapretkvn.android.vpn.VpnTestHooks
import io.nekohasekai.libbox.Libbox
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Debug-only measurement control plane driven by scripts/verify-gate8-performance.sh. */
class Gate8PerformanceProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.DEBUG)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val response = runCatching {
                val container = (context.applicationContext as ZapretApplication).container
                when (intent.action) {
                    ACTION_PREPARE -> prepare(container, intent)
                    ACTION_CONNECT -> connect(container)
                    ACTION_STOP -> stop(container)
                    ACTION_TRAFFIC -> traffic(intent)
                    ACTION_DNS -> dns(intent)
                    ACTION_VISIBILITY -> visibility(container, intent)
                    ACTION_STATUS -> status(container)
                    ACTION_CLEANUP -> cleanup(container)
                    else -> error("unknown-action")
                }
            }.fold(
                onSuccess = { it },
                onFailure = { "error=${it.javaClass.simpleName}:${safe(it.message)}" },
            )
            pending.resultData = response
            pending.finish()
        }
    }

    private suspend fun prepare(container: AppContainer, intent: Intent): String {
        cleanup(container)
        val routeMode = intent.getStringExtra(EXTRA_ROUTE_MODE).orEmpty().ifBlank { ROUTE_DIRECT }
        val stack = intent.getStringExtra(EXTRA_STACK).orEmpty().let { if (it == "default") "" else it }
        val mtu = intent.getIntExtra(EXTRA_MTU, 0)
        val dnsStrategy = intent.getStringExtra(EXTRA_DNS_STRATEGY).orEmpty().ifBlank { DNS_NONE }
        val proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 0)
        val memoryLimit = intent.getBooleanExtra(EXTRA_MEMORY_LIMIT, false)
        require(routeMode in setOf(ROUTE_DIRECT, ROUTE_PROXY)) { "invalid-route-mode" }
        require(stack in setOf("", "mixed", "system")) { "invalid-stack" }
        require(mtu == 0 || mtu in 1280..9000) { "invalid-mtu" }
        require(dnsStrategy in setOf(DNS_NONE, DNS_SEQUENTIAL, DNS_PARALLEL)) { "invalid-dns" }
        if (routeMode == ROUTE_PROXY) require(proxyPort in 1..65535) { "invalid-proxy-port" }

        container.libboxRuntime.initialize().getOrThrow()
        Libbox.setMemoryLimit(memoryLimit)
        currentMemoryLimit = memoryLimit
        val base = baseConfig(routeMode, stack, mtu, proxyPort)
        val profileJson = if (dnsStrategy == DNS_NONE) {
            base
        } else {
            val managed = when (
                val result = RuntimeConfigBuilder.build(
                    base,
                    options = RuntimeConfigOptions(dnsMode = DnsMode.Secure),
                )
            ) {
                is RuntimeConfigResult.Ready -> result.json
                is RuntimeConfigResult.Invalid -> error(result.message)
            }
            withFallbackStrategy(managed, dnsStrategy)
        }
        Libbox.checkConfig(profileJson)
        container.profileStore.initialize()
        val profile = container.profileStore.create(PROFILE_NAME, profileJson, ProfileSource.RawJson)
        container.uiSettingsStore.setActiveProfile(profile.id)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.appSelectionStore.setMode(AppScopeMode.Include)
        container.appSelectionStore.replaceAllowlist(setOf(SETTINGS_PACKAGE))
        return status(container) + ";route=$routeMode;stack=${stack.ifBlank { "default" }};mtu=$mtu;dns=$dnsStrategy;gc10=$memoryLimit"
    }

    private suspend fun connect(container: AppContainer): String {
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

    private suspend fun stop(container: AppContainer): String {
        container.vpnController.setHomeVisible(false)
        container.vpnController.setDiagnosticsVisible(false)
        if (container.vpnController.state.value !is VpnConnectionState.Stopped) {
            container.vpnController.stop()
            withTimeout(STOP_TIMEOUT_MILLIS) {
                container.vpnController.state.first { it is VpnConnectionState.Stopped }
            }
        }
        awaitResources(active = false)
        return status(container)
    }

    private suspend fun cleanup(container: AppContainer): String {
        stop(container)
        container.profileStore.initialize()
        container.profileStore.profiles.value
            .filter { it.name == PROFILE_NAME }
            .forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.appSelectionStore.replaceAllowlist(emptySet())
        container.appSelectionStore.setMode(AppScopeMode.Include)
        VpnTestHooks.reset()
        if (currentMemoryLimit) Libbox.setMemoryLimit(false)
        currentMemoryLimit = false
        return status(container)
    }

    private suspend fun visibility(container: AppContainer, intent: Intent): String {
        val home = intent.getBooleanExtra(EXTRA_HOME_VISIBLE, false)
        val diagnostics = intent.getBooleanExtra(EXTRA_DIAGNOSTICS_VISIBLE, false)
        require(!(home && diagnostics)) { "only-one-stream-may-be-visible" }
        container.vpnController.setHomeVisible(home)
        container.vpnController.setDiagnosticsVisible(diagnostics)
        withTimeout(STREAM_TIMEOUT_MILLIS) {
            while (true) {
                val snapshot = VpnRuntimeMetrics.snapshot()
                if (snapshot.activeStatusClients == (if (home) 1 else 0) &&
                    snapshot.activeLogClients == (if (diagnostics) 1 else 0)
                ) {
                    return@withTimeout
                }
                delay(20)
            }
        }
        return status(container)
    }

    private fun traffic(intent: Intent): String {
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val totalBytes = intent.getIntExtra(EXTRA_BYTES, 0)
        val chunkBytes = intent.getIntExtra(EXTRA_CHUNK_BYTES, DEFAULT_CHUNK_BYTES)
        require(address.isNotBlank() && port in 1..65535) { "invalid-target" }
        require(totalBytes in 1..MAX_TRAFFIC_BYTES) { "invalid-byte-count" }
        require(chunkBytes in 1024..MAX_CHUNK_BYTES) { "invalid-chunk-size" }
        val payload = ByteArray(chunkBytes) { (it * 31).toByte() }
        val received = ByteArray(chunkBytes)
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(address, port), SOCKET_TIMEOUT_MILLIS)
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            var remaining = totalBytes
            while (remaining > 0) {
                val count = min(remaining, chunkBytes)
                output.write(payload, 0, count)
                output.flush()
                input.readFully(received, 0, count)
                check((0 until count).all { payload[it] == received[it] }) { "echo-mismatch" }
                remaining -= count
            }
        }
        return "bytes=$totalBytes;elapsedNanos=${System.nanoTime() - started}"
    }

    private fun dns(intent: Intent): String {
        val count = intent.getIntExtra(EXTRA_QUERY_COUNT, 0)
        val prefix = intent.getStringExtra(EXTRA_QUERY_PREFIX).orEmpty().ifBlank { "gate8" }
        require(count in 1..MAX_DNS_QUERIES) { "invalid-query-count" }
        require(prefix.matches(Regex("[a-zA-Z0-9-]{1,40}"))) { "invalid-query-prefix" }
        var answered = 0
        val started = System.nanoTime()
        repeat(count) { index ->
            runCatching {
                InetAddress.getAllByName("$prefix-$index-${System.nanoTime()}.example.com")
            }.onSuccess { answered++ }
        }
        return "queries=$count;answered=$answered;elapsedNanos=${System.nanoTime() - started}"
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

    private fun status(container: AppContainer): String {
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
            "uidRxBytes=${TrafficStats.getUidRxBytes(Process.myUid())}",
            "uidTxBytes=${TrafficStats.getUidTxBytes(Process.myUid())}",
            "fds=${File("/proc/self/fd").list().orEmpty().size}",
            "threads=${File("/proc/self/task").list().orEmpty().size}",
            "gc10=$currentMemoryLimit",
        ).joinToString(";")
    }

    private fun baseConfig(routeMode: String, stack: String, mtu: Int, proxyPort: Int): String {
        val tun = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "type" to JsonPrimitive("tun"),
            "tag" to JsonPrimitive("tun-in"),
            "address" to JsonArray(
                listOf(JsonPrimitive("172.19.0.1/30"), JsonPrimitive("fdfe:dcba:9876::1/126")),
            ),
            "auto_route" to JsonPrimitive(true),
        )
        if (stack.isNotBlank()) tun["stack"] = JsonPrimitive(stack)
        if (mtu > 0) tun["mtu"] = JsonPrimitive(mtu)
        val server = if (routeMode == ROUTE_PROXY) {
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive("socks"),
                    "tag" to JsonPrimitive("server-a"),
                    "server" to JsonPrimitive(HOST_ALIAS),
                    "server_port" to JsonPrimitive(proxyPort),
                    "version" to JsonPrimitive("5"),
                ),
            )
        } else {
            JsonObject(mapOf("type" to JsonPrimitive("direct"), "tag" to JsonPrimitive("server-a")))
        }
        return JsonConfig.format(
            JsonObject(
                linkedMapOf(
                    "inbounds" to JsonArray(listOf(JsonObject(tun))),
                    "outbounds" to JsonArray(
                        listOf(
                            server,
                            JsonObject(
                                linkedMapOf(
                                    "type" to JsonPrimitive("selector"),
                                    "tag" to JsonPrimitive("zapret-proxy"),
                                    "outbounds" to JsonArray(listOf(JsonPrimitive("server-a"))),
                                    "default" to JsonPrimitive("server-a"),
                                    "interrupt_exist_connections" to JsonPrimitive(true),
                                ),
                            ),
                            JsonObject(mapOf("type" to JsonPrimitive("direct"), "tag" to JsonPrimitive("direct"))),
                        ),
                    ),
                    "route" to JsonObject(
                        mapOf(
                            "auto_detect_interface" to JsonPrimitive(true),
                            "final" to JsonPrimitive("zapret-proxy"),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun withFallbackStrategy(raw: String, strategy: String): String {
        val root = JsonConfig.parse(raw) as JsonObject
        val dns = root.getValue("dns") as JsonObject
        val servers = dns.getValue("servers") as JsonArray
        val updatedServers = JsonArray(
            servers.map { element ->
                val server = element as JsonObject
                if ((server["tag"] as? JsonPrimitive)?.content == "zapret-secure-dns") {
                    JsonObject(server.toMutableMap().apply { put("strategy", JsonPrimitive(strategy)) })
                } else {
                    server
                }
            },
        )
        val updatedDns = JsonObject(dns.toMutableMap().apply { put("servers", updatedServers) })
        return JsonConfig.format(JsonObject(root.toMutableMap().apply { put("dns", updatedDns) }))
    }

    private fun safe(value: String?): String = value.orEmpty().replace(';', ',').replace('\n', ' ').take(300)

    companion object {
        const val ACTION_PREPARE = "io.github.zapretkvn.android.debug.GATE8_PERF_PREPARE"
        const val ACTION_CONNECT = "io.github.zapretkvn.android.debug.GATE8_PERF_CONNECT"
        const val ACTION_STOP = "io.github.zapretkvn.android.debug.GATE8_PERF_STOP"
        const val ACTION_TRAFFIC = "io.github.zapretkvn.android.debug.GATE8_PERF_TRAFFIC"
        const val ACTION_DNS = "io.github.zapretkvn.android.debug.GATE8_PERF_DNS"
        const val ACTION_VISIBILITY = "io.github.zapretkvn.android.debug.GATE8_PERF_VISIBILITY"
        const val ACTION_STATUS = "io.github.zapretkvn.android.debug.GATE8_PERF_STATUS"
        const val ACTION_CLEANUP = "io.github.zapretkvn.android.debug.GATE8_PERF_CLEANUP"

        private const val EXTRA_ROUTE_MODE = "route_mode"
        private const val EXTRA_STACK = "stack"
        private const val EXTRA_MTU = "mtu"
        private const val EXTRA_DNS_STRATEGY = "dns_strategy"
        private const val EXTRA_PROXY_PORT = "proxy_port"
        private const val EXTRA_MEMORY_LIMIT = "memory_limit"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_BYTES = "bytes"
        private const val EXTRA_CHUNK_BYTES = "chunk_bytes"
        private const val EXTRA_QUERY_COUNT = "query_count"
        private const val EXTRA_QUERY_PREFIX = "query_prefix"
        private const val EXTRA_HOME_VISIBLE = "home_visible"
        private const val EXTRA_DIAGNOSTICS_VISIBLE = "diagnostics_visible"

        private const val PROFILE_NAME = "__gate8_performance_probe__"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val HOST_ALIAS = "10.0.2.2"
        private const val ROUTE_DIRECT = "direct"
        private const val ROUTE_PROXY = "proxy"
        private const val DNS_NONE = "none"
        private const val DNS_SEQUENTIAL = "sequential"
        private const val DNS_PARALLEL = "parallel"
        private const val DEFAULT_CHUNK_BYTES = 32 * 1024
        private const val MAX_CHUNK_BYTES = 64 * 1024
        private const val MAX_TRAFFIC_BYTES = 64 * 1024 * 1024
        private const val MAX_DNS_QUERIES = 100
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val CONNECTION_TIMEOUT_MILLIS = 30_000L
        private const val STOP_TIMEOUT_MILLIS = 20_000L
        private const val STREAM_TIMEOUT_MILLIS = 5_000L

        @Volatile
        private var currentMemoryLimit = false
    }
}
