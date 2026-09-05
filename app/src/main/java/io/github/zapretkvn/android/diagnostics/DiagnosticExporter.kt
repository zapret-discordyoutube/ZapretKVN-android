package io.github.zapretkvn.android.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.DefaultNetworkMonitor
import io.github.zapretkvn.android.vpn.PrivateDnsMode
import io.github.zapretkvn.android.vpn.UnderlyingNetworkState
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import io.github.zapretkvn.android.vpn.VpnRuntimeMetrics
import io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DiagnosticExporter(
    context: Context,
    private val settingsStore: UiSettingsStore,
    private val vpnController: VpnController,
    private val crashStore: AppCrashStore,
) {
    private val appContext = context.applicationContext
    private val exportDirectory = File(appContext.cacheDir, DIRECTORY_NAME)
    private val exportMutex = Mutex()

    fun cleanupStaleFiles() {
        exportDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
        exportDirectory.delete()
    }

    suspend fun createShareIntent(): Intent = exportMutex.withLock {
        val report = createReport()
        val file = withContext(Dispatchers.IO) {
            if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                error("Не удалось создать временный каталог диагностики.")
            }
            val target = File(exportDirectory, FILE_NAME)
            try {
                target.writeText(report, Charsets.UTF_8)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            target
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Zapret KVN — диагностика")
            clipData = ClipData.newRawUri("Zapret KVN diagnostic", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun createReport(): String {
        val settings = settingsStore.settings.first()
        val diagnostics = vpnController.diagnostics.value
        val network = readCurrentNetwork(diagnostics.network)
        val now = System.currentTimeMillis()
        val root = buildJsonObject {
            put("report_version", 7)
            put("runtime_errors", JsonArray(vpnController.runtimeErrors.entries.value.map { record ->
                buildJsonObject {
                    val failure = record.failure
                    put("component", failure.component)
                    put("stage", failure.stage)
                    put("message", DiagnosticReportRedactor.redact(failure.message))
                    put("code", failure.code)
                    put("session_generation", failure.sessionGeneration)
                    put("target_generation", failure.targetGeneration)
                    put("target", DiagnosticReportRedactor.redact(failure.targetId))
                    put("first_seen_epoch_ms", record.firstSeenEpochMillis)
                    put("last_seen_epoch_ms", record.lastSeenEpochMillis)
                    put("occurrences", record.occurrences)
                }
            }))
            put("created_at", isoTimestamp(now))
            put("created_at_epoch_ms", now)
            put(
                "app",
                buildJsonObject {
                    put("version_name", BuildConfig.VERSION_NAME)
                    put("version_code", BuildConfig.VERSION_CODE)
                    put("debug", BuildConfig.DEBUG)
                },
            )
            put(
                "core",
                buildJsonObject {
                    put("tag", BuildConfig.CORE_TAG)
                    put("revision", BuildConfig.CORE_COMMIT)
                    put("patch_sha256", BuildConfig.CORE_PATCH_SHA256)
                    put(
                        "hysteria2",
                        buildJsonObject {
                            put("tag", BuildConfig.HYSTERIA_CORE_TAG)
                            put("revision", BuildConfig.HYSTERIA_CORE_COMMIT)
                        },
                    )
                },
            )
            put(
                "android",
                buildJsonObject {
                    put("release", Build.VERSION.RELEASE.orEmpty())
                    put("api", Build.VERSION.SDK_INT)
                    put("manufacturer", Build.MANUFACTURER.orEmpty().take(80))
                    put("model", Build.MODEL.orEmpty().take(80))
                    put("primary_abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                },
            )
            put("vpn", vpnStateJson(vpnController.state.value))
            put("runtime_resources", runtimeResourcesJson(diagnostics.effectiveOverlay))
            put(
                "vpn_system_policy",
                buildJsonObject {
                    val policy = diagnostics.vpnPolicy
                    put("status_available", policy?.statusAvailable == true)
                    put("always_on", policy?.alwaysOn == true)
                    put("lockdown", policy?.lockdown == true)
                    put("supported_by_app", false)
                },
            )
            put("network", networkJson(network))
            put(
                "network_automation",
                buildJsonObject {
                    val automation = settings.networkAutomation
                    put("enabled", automation.enabled)
                    put("vpn_on_wifi", automation.useVpnOnWifi)
                    put("vpn_on_cellular", automation.useVpnOnCellular)
                    put("vpn_on_ethernet", automation.useVpnOnEthernet)
                    put("vpn_on_other", automation.useVpnOnOther)
                    put("pause_on_trusted_wifi", automation.pauseOnTrustedWifi)
                    put("trusted_wifi_count", automation.trustedWifiSsids.size)
                },
            )
            put(
                "dns",
                buildJsonObject {
                    put("mode", settings.dnsMode.name)
                    put("proxy_ipv4_only", settings.proxyIpv4Only)
                    put(
                        "override_active",
                        settings.dnsOverride.enabled && settings.dnsMode != DnsMode.FromJson,
                    )
                    put("private_dns_mode", network.privateDnsMode)
                    put("private_dns_active", network.privateDnsActive)
                },
            )
            put("last_error", failureJson(diagnostics.lastFailure))
            put(
                "log_stats",
                buildJsonObject {
                    put("application", logStatsJson(diagnostics.applicationLogStats, diagnostics.applicationLogs.size))
                    put("core", logStatsJson(diagnostics.coreLogStats, diagnostics.coreLogs.size))
                },
            )
            put(
                "connection_attempt",
                diagnostics.connectionAttempt?.let(::connectionAttemptJson) ?: JsonNull,
            )
            put(
                "connection_attempts",
                buildJsonArray {
                    diagnostics.recentConnectionAttempts.forEach { add(connectionAttemptJson(it)) }
                },
            )
            put(
                "stop_attempt",
                diagnostics.stopAttempt?.let(::stopAttemptJson) ?: JsonNull,
            )
            put("previous_crash", crashJson(crashStore.read()))
            put("previous_process_exit", processExitJson(diagnostics.previousProcessExit))
            put(
                "effective_overlay",
                diagnostics.effectiveOverlay
                    ?.let { runCatching { JsonConfig.parse(it) }.getOrNull() }
                    ?: JsonNull,
            )
            put(
                "logs",
                buildJsonArray {
                    diagnostics.logs.forEach { line -> add(logLineJson(line)) }
                },
            )
            put(
                "privacy",
                buildJsonObject {
                    put("raw_profile_included", false)
                    put("credentials_included", false)
                    put("installed_packages_included", false)
                    put("external_ip_included", false)
                    put("runtime_log_persisted", false)
                    put("anr_trace_included", false)
                },
            )
        }
        return DiagnosticReportRedactor.redact(JsonConfig.format(root))
    }

    private suspend fun readCurrentNetwork(fallback: DiagnosticNetworkState?): DiagnosticNetworkState {
        val monitor = DefaultNetworkMonitor(appContext)
        return try {
            monitor.start()
            monitor.awaitUnderlying(NETWORK_TIMEOUT_MILLIS).toDiagnosticState()
        } catch (_: TimeoutCancellationException) {
            fallback ?: DiagnosticNetworkState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fallback ?: DiagnosticNetworkState()
        } finally {
            monitor.close()
        }
    }

    private fun vpnStateJson(state: VpnConnectionState): JsonObject = buildJsonObject {
        put(
            "state",
            when (state) {
                VpnConnectionState.Stopped -> "stopped"
                is VpnConnectionState.Starting -> "starting"
                is VpnConnectionState.Connected -> "connected"
                is VpnConnectionState.Paused -> "paused"
                is VpnConnectionState.Stopping -> "stopping"
                is VpnConnectionState.Reconnecting -> "reconnecting"
                is VpnConnectionState.Error -> "error"
            },
        )
        if (state is VpnConnectionState.Connected) {
            put("connected_at_epoch_ms", state.connectedAtEpochMillis)
        }
    }

    private fun runtimeResourcesJson(effectiveOverlay: String?): JsonObject {
        val resources = VpnRuntimeMetrics.snapshot()
        val innerWireGuardMtu = effectiveOverlay
            ?.let { runCatching { JsonConfig.parse(it) }.getOrNull() }
            ?.let { it as? JsonObject }
            ?.get("wireguard_mtu_values") as? JsonArray
        return buildJsonObject {
            put("sessions", resources.activeSessions)
            put("libbox_instances", resources.activeLibboxInstances)
            put("platform_adapters", resources.activePlatformAdapters)
            put("tun_descriptors", resources.activeTunDescriptors)
            put(
                "outer_tun_mtu",
                resources.outerTunMtu?.let(::JsonPrimitive) ?: JsonPrimitive("unknown"),
            )
            put(
                "inner_wireguard_mtu",
                innerWireGuardMtu?.takeIf { it.isNotEmpty() }
                    ?: JsonPrimitive("unknown"),
            )
            put("network_callbacks", resources.activeNetworkCallbacks)
            put("status_clients", resources.activeStatusClients)
            put("log_clients", resources.activeLogClients)
        }
    }

    private fun networkJson(network: DiagnosticNetworkState): JsonObject = buildJsonObject {
        put("available", network.available)
        put("transport", network.transport)
        network.interfaceName?.let { put("interface", it) }
        put("metered", network.metered)
        put("validated", network.validated)
        put("captive_portal", network.captivePortal)
    }

    private fun failureJson(failure: DiagnosticFailure?): JsonObject = buildJsonObject {
        put("present", failure != null)
        putDiagnosticContext(
            attempt = failure?.attempt,
            stage = failure?.stage,
            target = failure?.target,
        )
        failure?.let {
            put("type", it.type.code)
            put("title", it.type.title)
            put("support_code", it.supportCode)
            put("message", it.message)
            it.technicalDetail?.let { detail -> put("technical_detail", detail) }
            put("occurred_at_epoch_ms", it.occurredAtEpochMillis)
        }
    }

    private fun connectionAttemptJson(attempt: DiagnosticConnectionAttempt): JsonObject =
        buildJsonObject {
            put("generation", attempt.generation)
            putDiagnosticContext(
                attempt = attempt.generation,
                stage = attempt.stages.lastOrNull { it.status == DiagnosticStageStatus.Running }?.key
                    ?: attempt.failure?.stage,
                target = attempt.target ?: attempt.failure?.target,
            )
            put("trigger", attempt.trigger)
            put("candidate_attempt_id", attempt.candidateAttemptId)
            put("started_at_epoch_ms", attempt.startedAtEpochMillis)
            put("outcome", attempt.outcome.code)
            attempt.cancellationReason?.let { put("cancellation_reason", it) }
            attempt.vpnNetworkIdentity?.let { put("vpn_network_identity", it) }
            put("vpn_network_lost", attempt.vpnNetworkLost)
            attempt.totalDurationMillis?.let { put("total_duration_ms", it) }
            val elapsed = attempt.totalDurationMillis
                ?: (SystemClock.elapsedRealtime() - attempt.startedAtElapsedRealtimeMillis).coerceAtLeast(0L)
            put("elapsed_ms", elapsed)
            put("remaining_startup_budget_ms", (45_000L - elapsed).coerceAtLeast(0L))
            attempt.stages.lastOrNull { it.status == io.github.zapretkvn.android.diagnostics.DiagnosticStageStatus.Running }
                ?.let { put("current_stage", it.key) }
            put("dns_probe_socket_path", "vpn_uid_tun")
            put("failure", failureJson(attempt.failure))
            attempt.slowestCompletedStage?.let { slowest ->
                put(
                    "slowest_stage",
                    buildJsonObject {
                        put("key", slowest.key)
                        put("label", slowest.label)
                        put("duration_ms", checkNotNull(slowest.durationMillis))
                    },
                )
            }
            put(
                "stages",
                buildJsonArray {
                    attempt.stages.forEach { stage ->
                        add(
                            buildJsonObject {
                                putDiagnosticContext(
                                    attempt = stage.attempt ?: attempt.generation,
                                    stage = stage.key,
                                    target = stage.target ?: attempt.target,
                                )
                                put("key", stage.key)
                                put("label", stage.label)
                                put("started_at_epoch_ms", stage.startedAtEpochMillis)
                                stage.durationMillis?.let { put("duration_ms", it) }
                                put("status", stage.status.code)
                                stage.detail?.let { put("detail", it) }
                            },
                        )
                    }
                },
            )
            put(
                "startup_core_logs",
                buildJsonArray {
                    attempt.startupCoreLogs.forEach { add(logLineJson(it)) }
                },
            )
            put(
                "startup_core_log_stats",
                logStatsJson(attempt.startupCoreLogStats, attempt.startupCoreLogs.size),
            )
        }

    private fun stopAttemptJson(attempt: DiagnosticStopAttempt): JsonObject =
        buildJsonObject {
            val elapsed = SystemClock.elapsedRealtime()
            put("generation", attempt.generation)
            putDiagnosticContext(
                attempt = attempt.generation,
                stage = attempt.stages.lastOrNull { it.status == DiagnosticStageStatus.Running }?.key,
                target = attempt.target,
            )
            put("trigger", attempt.trigger)
            put("started_at_epoch_ms", attempt.startedAtEpochMillis)
            put("outcome", attempt.outcome.code)
            put(
                "total_duration_ms",
                attempt.totalDurationMillis
                    ?: (elapsed - attempt.startedAtElapsedRealtimeMillis).coerceAtLeast(0L),
            )
            attempt.slowestCompletedStage?.let { slowest ->
                put(
                    "slowest_stage",
                    buildJsonObject {
                        put("key", slowest.key)
                        put("label", slowest.label)
                        put("duration_ms", checkNotNull(slowest.durationMillis))
                    },
                )
            }
            put(
                "stages",
                buildJsonArray {
                    attempt.stages.forEach { stage ->
                        add(
                            buildJsonObject {
                                putDiagnosticContext(
                                    attempt = stage.attempt ?: attempt.generation,
                                    stage = stage.key,
                                    target = stage.target ?: attempt.target,
                                )
                                put("key", stage.key)
                                put("label", stage.label)
                                put("started_at_epoch_ms", stage.startedAtEpochMillis)
                                put(
                                    "duration_ms",
                                    stage.durationMillis
                                        ?: (elapsed - stage.startedAtElapsedRealtimeMillis)
                                            .coerceAtLeast(0L),
                                )
                                put("status", stage.status.code)
                                stage.detail?.let { put("detail", it) }
                            },
                        )
                    }
                },
            )
        }

    private fun logLineJson(line: DiagnosticLogLine): JsonObject = buildJsonObject {
        putDiagnosticContext(line.attempt, line.stage, line.target)
        put("received_at_epoch_ms", line.receivedAtEpochMillis)
        put("last_received_at_epoch_ms", line.lastReceivedAtEpochMillis)
        put("level", line.levelName)
        put("source", line.source.code)
        put("category", line.category.code)
        put("repeat_count", line.repeatCount)
        put("message", line.message)
    }

    private fun logStatsJson(stats: DiagnosticLogStats, retainedEntries: Int): JsonObject =
        buildJsonObject {
            put("received_lines", stats.receivedLines)
            put("retained_entries", retainedEntries)
            put("coalesced_lines", stats.coalescedLines)
            put("dropped_lines", stats.droppedLines)
        }

    private fun JsonObjectBuilder.putDiagnosticContext(
        attempt: Long?,
        stage: String?,
        target: DiagnosticTargetContext?,
    ) {
        put("attempt", attempt?.let(::JsonPrimitive) ?: JsonNull)
        put("stage", stage?.let(::JsonPrimitive) ?: JsonNull)
        put("profile_ref", target?.profileRef?.let(::JsonPrimitive) ?: JsonNull)
        put("profile_name", target?.profileName?.let(::JsonPrimitive) ?: JsonNull)
        put("outbound_tag", target?.outboundTag?.let(::JsonPrimitive) ?: JsonNull)
        put("protocol", target?.protocol?.let(::JsonPrimitive) ?: JsonNull)
        put("endpoint", target?.endpoint?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun crashJson(crash: AppCrashRecord?): JsonObject = buildJsonObject {
        put("present", crash != null)
        crash?.let { record ->
            put("occurred_at_epoch_ms", record.occurredAtEpochMillis)
            put("thread", record.threadName)
            put("exception", record.exceptionType)
            record.message?.let { put("message", it) }
            put("causes", buildJsonArray { record.causes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put(
                "stack",
                buildJsonArray {
                    record.stack.forEach { frame ->
                        add(
                            buildJsonObject {
                                put("class", frame.className)
                                put("method", frame.methodName)
                                put("line", frame.lineNumber)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun processExitJson(exit: AppProcessExitRecord?): JsonObject = buildJsonObject {
        put("supported", Build.VERSION.SDK_INT >= 30)
        put("present", exit != null)
        exit?.let { record ->
            put("occurred_at_epoch_ms", record.occurredAtEpochMillis)
            put("reason_code", record.reasonCode)
            put("reason", record.reason)
            put("status", record.status)
            put("importance", record.importance)
            put("pss_kb", record.pssKilobytes)
            put("rss_kb", record.rssKilobytes)
            record.description?.let { put("description", it) }
            put("trace_included", false)
        }
    }

    private fun isoTimestamp(epochMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMillis))

    private fun UnderlyingNetworkState.toDiagnosticState() = DiagnosticNetworkState(
        available = network != null,
        transport = transport,
        interfaceName = interfaceName,
        metered = metered,
        validated = validated,
        captivePortal = captivePortal,
        privateDnsMode = when (privateDnsMode) {
            PrivateDnsMode.Off -> "off"
            PrivateDnsMode.Automatic -> "automatic"
            PrivateDnsMode.Strict -> "strict"
        },
        privateDnsActive = privateDnsActive,
    )

    companion object {
        const val DIRECTORY_NAME = "diagnostics"
        const val FILE_NAME = "zapret-kvn-diagnostic.json"
        private const val NETWORK_TIMEOUT_MILLIS = 2_000L
    }
}
