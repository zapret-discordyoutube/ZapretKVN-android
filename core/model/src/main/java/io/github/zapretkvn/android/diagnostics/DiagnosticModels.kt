package io.github.zapretkvn.android.diagnostics

enum class DiagnosticErrorType(
    val code: String,
    val title: String,
    val supportCode: String,
) {
    Permission("permission", "Разрешение VPN", "VPN-101"),
    Profile("profile", "Профиль или JSON", "CFG-101"),
    SystemDns("system_dns", "Системный DNS", "DNS-100"),
    PrivateDns("private_dns", "Private DNS", "DNS-110"),
    VpnServer("vpn_server", "VPN-сервер", "SRV-100"),
    VpnDns("vpn_dns", "DNS через VPN", "DNS-200"),
    VpnTraffic("vpn_traffic", "Трафик через VPN", "VPN-200"),
    VpnAccess("vpn_access", "Ключ или доступ VPN", "AUTH-100"),
    CaptivePortal("captive_portal", "Авторизация Wi-Fi", "NET-110"),
    Core("core", "Ядро sing-box", "CORE-100"),
    AndroidNetwork("android_network", "Сеть Android", "NET-100"),
    Unknown("unknown", "Неизвестная ошибка", "VPN-000"),
}

object DiagnosticFailureClassifier {
    fun classify(message: String): DiagnosticErrorType {
        val value = message.lowercase()
        return when {
            "always-on" in value || "lockdown" in value -> DiagnosticErrorType.Permission
            "private dns" in value || "частный dns" in value -> DiagnosticErrorType.PrivateDns
            "vpn-сервер явно отклонил ключ" in value -> DiagnosticErrorType.VpnAccess
            "авторизац" in value || "captive" in value -> DiagnosticErrorType.CaptivePortal
            "разрешение" in value && "vpn" in value -> DiagnosticErrorType.Permission
            "https-провер" in value ||
                "https probe" in value ||
                ("wireguard" in value &&
                    ("tcp" in value || "data-plane" in value || "данные" in value)) ||
                "health-check" in value -> DiagnosticErrorType.VpnTraffic
            ("dns" in value && "vpn" in value) ||
                "внутренний dns" in value ||
                "внутреннего dns" in value ||
                "doh" in value -> DiagnosticErrorType.VpnDns
            "системный dns" in value ||
                "системного dns" in value ||
                "bootstrap" in value ||
                "разрешить адрес" in value -> DiagnosticErrorType.SystemDns
            "vpn-сервер" in value ||
                "proxy socket" in value ||
                "сервер не отвечает" in value -> DiagnosticErrorType.VpnServer
            "профил" in value ||
                "json" in value ||
                "конфигурац" in value ||
                "checkconfig" in value -> DiagnosticErrorType.Profile
            "sing-box" in value || "libbox" in value || "ядр" in value -> DiagnosticErrorType.Core
            "сеть android" in value ||
                "underlying" in value ||
                "интернет" in value -> DiagnosticErrorType.AndroidNetwork
            else -> DiagnosticErrorType.Unknown
        }
    }
}

data class DiagnosticFailure(
    val type: DiagnosticErrorType,
    val supportCode: String,
    val message: String,
    val technicalDetail: String? = null,
    val occurredAtEpochMillis: Long,
    val attempt: Long? = null,
    val stage: String? = null,
    val target: DiagnosticTargetContext? = null,
)

data class DiagnosticLogLine(
    val level: Int,
    val message: String,
    val receivedAtEpochMillis: Long,
    val source: DiagnosticLogSource = DiagnosticLogSource.Application,
    val category: DiagnosticLogCategory = DiagnosticLogCategory.Other,
    val priority: Boolean = false,
    /** Строка на каждое соединение (пакет, адрес, вход в TUN): вытесняется первой. */
    val routine: Boolean = false,
    val repeatCount: Int = 1,
    val lastReceivedAtEpochMillis: Long = receivedAtEpochMillis,
    val attempt: Long? = null,
    val stage: String? = null,
    val target: DiagnosticTargetContext? = null,
) {
    val levelName: String
        get() = when (level) {
            0 -> "PANIC"
            1 -> "FATAL"
            2 -> "ERROR"
            3 -> "WARN"
            4 -> "NOTICE"
            5 -> "INFO"
            6 -> "DEBUG"
            7 -> "TRACE"
            else -> "LOG"
        }
}

enum class DiagnosticLogSource(val code: String) {
    Application("app"),
    Core("core"),
}

enum class DiagnosticLogCategory(val code: String) {
    Lifecycle("lifecycle"),
    AndroidNetwork("android_network"),
    Tun("tun"),
    WireGuard("wireguard"),
    Dns("dns"),
    Https("https"),
    Routing("routing"),
    Other("other"),
}

data class DiagnosticLogStats(
    val receivedLines: Long = 0,
    val coalescedLines: Long = 0,
    val droppedLines: Long = 0,
    /** Известный шум ядра ([CoreLogNoise]): не занимает места, только считается. */
    val noiseLines: Long = 0,
)

data class DiagnosticLogAppendResult(
    val lines: List<DiagnosticLogLine>,
    val stats: DiagnosticLogStats,
)

data class CoreDiagnosticBatch(
    val entries: List<Pair<Int, String>>,
    val droppedLines: Int,
    val noise: Map<CoreLogNoise, Int> = emptyMap(),
    val failures: Map<ConnectionFailureKey, Int> = emptyMap(),
)

/** Caps each libbox callback before it can trigger StateFlow allocations. */
class CoreDiagnosticBatchCollector(
    private val limit: Int = MAX_DIAGNOSTIC_CORE_BATCH_LINES,
) {
    private val retained = ArrayList<Pair<Int, String>>(limit.coerceAtLeast(0))
    private var dropped = 0
    private val noise = HashMap<CoreLogNoise, Int>()
    private val failures = HashMap<ConnectionFailureKey, Int>()

    fun add(level: Int, rawMessage: String) {
        // Шум не занимает мест в пачке: сотни одинаковых предупреждений при старте
        // Xray иначе вытесняли всё полезное ещё до приоритетного буфера.
        CoreLogNoise.of(rawMessage)?.let { kind ->
            noise[kind] = (noise[kind] ?: 0) + 1
            return
        }
        // Отказ считается до любого отбора: сводка не теряет его, даже если строку вытеснят.
        ConnectionFailureKey.parse(level, rawMessage)?.let { key -> failures[key] = (failures[key] ?: 0) + 1 }
        val message = rawMessage.take(MAX_DIAGNOSTIC_LOG_LINE_CHARS)
        val candidate = level to message
        if (retained.size < limit) {
            retained += candidate
            return
        }
        if (CoreDiagnosticClassifier.isPotentiallyImportant(level, message)) {
            val routineIndex = retained.indexOfFirst { (savedLevel, savedMessage) ->
                !CoreDiagnosticClassifier.isPotentiallyImportant(savedLevel, savedMessage)
            }
            if (routineIndex >= 0) {
                retained.removeAt(routineIndex)
                retained += candidate
            }
        }
        dropped++
    }

    fun result(): CoreDiagnosticBatch = CoreDiagnosticBatch(retained.toList(), dropped, noise.toMap(), failures.toMap())
}

object CoreDiagnosticClassifier {
    fun classify(level: Int, message: String): DiagnosticLogLine {
        val normalized = message.lowercase()
        val category = when {
            WIREGUARD_MARKERS.any(normalized::contains) -> DiagnosticLogCategory.WireGuard
            DNS_MARKERS.any(normalized::contains) -> DiagnosticLogCategory.Dns
            TUN_MARKERS.any(normalized::contains) -> DiagnosticLogCategory.Tun
            ROUTING_MARKERS.any(normalized::contains) -> DiagnosticLogCategory.Routing
            NETWORK_MARKERS.any(normalized::contains) -> DiagnosticLogCategory.AndroidNetwork
            else -> DiagnosticLogCategory.Other
        }
        val priority = level <= 3 || PRIORITY_MARKERS.any(normalized::contains)
        return DiagnosticLogLine(
            level = level,
            message = message,
            receivedAtEpochMillis = System.currentTimeMillis(),
            source = DiagnosticLogSource.Core,
            category = category,
            priority = priority,
            routine = !priority && ROUTINE_MARKERS.any(normalized::contains),
        )
    }

    fun isPotentiallyImportant(level: Int, message: String): Boolean =
        level <= 3 || PRIORITY_MARKERS.any(message.lowercase()::contains)

    /** По строке на каждое соединение: ценны только рядом с отказом, а их сотни. */
    private val ROUTINE_MARKERS = listOf(
        "router: found package name",
        "router: found reserve mapped domain",
        "inbound connection from",
        "inbound connection to",
        "inbound packet connection",
        "inbound dns packet",
    )
    private val WIREGUARD_MARKERS = listOf("wireguard", "handshake", "peer(", "peer ")
    private val DNS_MARKERS = listOf("dns", "domain resolver", "rcode")
    private val TUN_MARKERS = listOf("tun", "protect(", "protect fd", "interface monitor")
    private val ROUTING_MARKERS = listOf("route", "rule-set", "rule_set", "outbound")
    private val NETWORK_MARKERS = listOf("network", "interface", "endpoint")
    private val PRIORITY_MARKERS = listOf(
        "uapi:",
        "received handshake",
        "sending handshake",
        "handshake did not",
        "invalid handshake",
        "endpoint changed",
        "endpoint updated",
        "update endpoint",
        "new endpoint",
        "protect(",
        "protect fd",
        "timeout",
        "timed out",
        "deadline exceeded",
        "connection refused",
        "failed",
        "error",
        "reconnect",
        "no recent network activity",
        "quic",
        "dial",
        "closed",
    )
}

data class DiagnosticNetworkState(
    val available: Boolean = false,
    val transport: String = "none",
    val interfaceName: String? = null,
    val metered: Boolean = false,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    val privateDnsMode: String = "off",
    val privateDnsActive: Boolean = false,
)

data class DiagnosticVpnPolicy(
    val statusAvailable: Boolean,
    val alwaysOn: Boolean,
    val lockdown: Boolean,
)

enum class DiagnosticStageStatus(val code: String) {
    Running("running"),
    Success("success"),
    Recovered("recovered"),
    Failed("failed"),
    Cancelled("cancelled"),
}

enum class DiagnosticAttemptOutcome(val code: String) {
    Running("running"),
    Connected("connected"),
    Failed("failed"),
    Cancelled("cancelled"),
}

enum class DiagnosticStopOutcome(val code: String) {
    Running("running"),
    Completed("completed"),
}

data class DiagnosticStageTiming(
    val key: String,
    val label: String,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val durationMillis: Long? = null,
    val status: DiagnosticStageStatus = DiagnosticStageStatus.Running,
    val detail: String? = null,
    val attempt: Long? = null,
    val target: DiagnosticTargetContext? = null,
)

data class DiagnosticConnectionAttempt(
    val generation: Long,
    val trigger: String,
    val candidateAttemptId: Int = 1,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val totalDurationMillis: Long? = null,
    val outcome: DiagnosticAttemptOutcome = DiagnosticAttemptOutcome.Running,
    val stages: List<DiagnosticStageTiming> = emptyList(),
    val startupCoreLogs: List<DiagnosticLogLine> = emptyList(),
    val startupCoreLogStats: DiagnosticLogStats = DiagnosticLogStats(),
    val failure: DiagnosticFailure? = null,
    val cancellationReason: String? = null,
    val vpnNetworkIdentity: String? = null,
    val vpnNetworkLost: Boolean = false,
    val target: DiagnosticTargetContext? = null,
) {
    val slowestCompletedStage: DiagnosticStageTiming?
        get() = stages
            .asSequence()
            .filter { it.durationMillis != null }
            .maxByOrNull { it.durationMillis ?: -1L }
}

data class DiagnosticStopAttempt(
    val generation: Long,
    val trigger: String,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val totalDurationMillis: Long? = null,
    val outcome: DiagnosticStopOutcome = DiagnosticStopOutcome.Running,
    val stages: List<DiagnosticStageTiming> = emptyList(),
    val target: DiagnosticTargetContext? = null,
) {
    val slowestCompletedStage: DiagnosticStageTiming?
        get() = stages
            .asSequence()
            .filter { it.durationMillis != null }
            .maxByOrNull { it.durationMillis ?: -1L }
}

data class DiagnosticState(
    val runtimeErrors: List<io.github.zapretkvn.android.diagnostics.RecordedRuntimeFailure> = emptyList(),
    val generation: Long = 0,
    val lastFailure: DiagnosticFailure? = null,
    val applicationLogs: List<DiagnosticLogLine> = emptyList(),
    val applicationLogStats: DiagnosticLogStats = DiagnosticLogStats(),
    val coreLogs: List<DiagnosticLogLine> = emptyList(),
    val coreLogStats: DiagnosticLogStats = DiagnosticLogStats(),
    val coreNoise: Map<CoreLogNoise, Long> = emptyMap(),
    val connectionFailures: Map<ConnectionFailureKey, Long> = emptyMap(),
    val logStreamActive: Boolean = false,
    val network: DiagnosticNetworkState? = null,
    val vpnPolicy: DiagnosticVpnPolicy? = null,
    val effectiveOverlay: String? = null,
    val connectionAttempt: DiagnosticConnectionAttempt? = null,
    val previousConnectionAttempts: List<DiagnosticConnectionAttempt> = emptyList(),
    val stopAttempt: DiagnosticStopAttempt? = null,
    val previousCrash: AppCrashRecord? = null,
    val previousProcessExit: AppProcessExitRecord? = null,
) {
    val recentConnectionAttempts: List<DiagnosticConnectionAttempt>
        get() = (previousConnectionAttempts + listOfNotNull(connectionAttempt))
            .takeLast(MAX_DIAGNOSTIC_ATTEMPTS)

    val logs: List<DiagnosticLogLine>
        get() = (applicationLogs + coreLogs)
            .sortedBy(DiagnosticLogLine::receivedAtEpochMillis)
            .fold(emptyList()) { retained, line ->
                retained.appendPrioritizedBounded(
                    line = line,
                    limit = MAX_DIAGNOSTIC_LOG_LINES,
                ).lines
            }
}

fun List<DiagnosticLogLine>.appendBounded(
    line: DiagnosticLogLine,
    limit: Int = MAX_DIAGNOSTIC_LOG_LINES,
): List<DiagnosticLogLine> = (this + line).takeLast(limit)

/**
 * In-memory priority ring. Repeated adjacent messages are coalesced, adjacent
 * routine DNS lines collapse into one counter, and salient handshake/TUN/errors
 * survive routine packet noise. Under quota pressure DEBUG/TRACE lines go first,
 * then ordinary INFO lines, and priority lines are evicted last (oldest first).
 * Nothing is written to disk.
 */
fun List<DiagnosticLogLine>.appendPrioritizedBounded(
    line: DiagnosticLogLine,
    stats: DiagnosticLogStats = DiagnosticLogStats(),
    limit: Int = MAX_DIAGNOSTIC_STARTUP_LOG_LINES,
): DiagnosticLogAppendResult {
    val received = stats.copy(receivedLines = stats.receivedLines + 1)
    val last = lastOrNull()
    if (last != null && last.sameDiagnosticMessage(line)) {
        return DiagnosticLogAppendResult(
            lines = dropLast(1) + last.copy(
                repeatCount = last.repeatCount + 1,
                lastReceivedAtEpochMillis = line.lastReceivedAtEpochMillis,
            ),
            stats = received.copy(coalescedLines = received.coalescedLines + 1),
        )
    }
    // DNS noise (one line per resolved name) must not push real evidence out.
    if (last != null && last.isDnsNoiseLike(line)) {
        return DiagnosticLogAppendResult(
            lines = dropLast(1) + last.copy(
                repeatCount = last.repeatCount + line.repeatCount,
                lastReceivedAtEpochMillis = line.lastReceivedAtEpochMillis,
            ),
            stats = received.copy(coalescedLines = received.coalescedLines + line.repeatCount),
        )
    }
    if (limit <= 0) {
        return DiagnosticLogAppendResult(emptyList(), received.copy(droppedLines = received.droppedLines + 1))
    }
    if (size < limit) return DiagnosticLogAppendResult(this + line, received)

    // Oldest line of the least valuable rank is the eviction candidate.
    var victimIndex = 0
    var victimRank = Int.MAX_VALUE
    forEachIndexed { index, candidate ->
        val rank = candidate.evictionRank()
        if (rank < victimRank) {
            victimRank = rank
            victimIndex = index
        }
    }
    if (line.evictionRank() < victimRank) {
        // Everything retained is more valuable than the newcomer.
        return DiagnosticLogAppendResult(
            this,
            received.copy(droppedLines = received.droppedLines + line.repeatCount),
        )
    }
    val removed = this[victimIndex]
    val next = toMutableList().apply {
        removeAt(victimIndex)
        add(line)
    }
    return DiagnosticLogAppendResult(
        next,
        received.copy(droppedLines = received.droppedLines + removed.repeatCount),
    )
}

/** 0 - DEBUG/TRACE, 1 - ordinary INFO/NOTICE, 2 - priority evidence (evicted last). */
private fun DiagnosticLogLine.evictionRank(): Int = when {
    priority -> 2
    routine || level >= 6 -> 0
    else -> 1
}

private fun DiagnosticLogLine.isDnsNoiseLike(other: DiagnosticLogLine): Boolean =
    category == DiagnosticLogCategory.Dns && other.category == DiagnosticLogCategory.Dns &&
        !priority && !other.priority &&
        level == other.level &&
        source == other.source &&
        attempt == other.attempt &&
        stage == other.stage &&
        target == other.target

private fun DiagnosticLogLine.sameDiagnosticMessage(other: DiagnosticLogLine): Boolean =
    level == other.level &&
        source == other.source &&
        category == other.category &&
        message == other.message &&
        attempt == other.attempt &&
        stage == other.stage &&
        target == other.target

const val MAX_DIAGNOSTIC_LOG_LINES = 200
const val MAX_DIAGNOSTIC_STARTUP_LOG_LINES = 48
const val MAX_DIAGNOSTIC_CORE_BATCH_LINES = 48
const val MAX_DIAGNOSTIC_LOG_LINE_CHARS = 600
const val MAX_DIAGNOSTIC_ATTEMPTS = 6
const val MAX_DIAGNOSTIC_STAGES = 48

/**
 * Известный шум ядра. Встроенный Xray на каждый VLESS/VMess с gRPC/WS пишет при
 * старте «started» и предупреждения об устаревших транспортах — в большой
 * подписке сотни строк разом. Они ничего не говорят об отказе.
 */
enum class CoreLogNoise(val code: String) {
    XrayDeprecated("xray_deprecated"),
    XrayStarted("xray_started"),
    ;

    companion object {
        private val STARTED = Regex("""core: xray [0-9.]+ started""")

        fun of(message: String): CoreLogNoise? {
            val text = message.lowercase()
            if ("xray:" !in text) return null
            return when {
                "deprecated" in text -> XrayDeprecated
                STARTED.containsMatchIn(text) -> XrayStarted
                else -> null
            }
        }
    }
}

/**
 * Сводка отказов соединений без имён и адресов: сеть, порт, путь и вид ошибки.
 * По ней видно, что рвётся — UDP 443 через сервер, IPv6 напрямую, таймауты
 * прокси, — даже когда сами строки уже вытеснены.
 */
data class ConnectionFailureKey(
    val network: String,
    val port: Int?,
    val path: String,
    val error: String,
) {
    companion object {
        private val DIAL = Regex("""\b(tcp6|tcp4|tcp|udp6|udp4|udp)\s+\[?[^\s\]]*\]?:(\d{1,5})\b""")
        private val OUTBOUND = Regex("""outbound/([a-z0-9_-]+)""")

        fun parse(level: Int, message: String): ConnectionFailureKey? {
            if (level > 3) return null
            val text = message.lowercase()
            if ("connection" !in text && "dial" !in text) return null
            val error = errorKind(text) ?: return null
            val dial = DIAL.find(text)
            val network = dial?.groupValues?.get(1) ?: when {
                "udp" in text || "packet" in text -> "udp"
                else -> "tcp"
            }
            return ConnectionFailureKey(
                network = network,
                port = dial?.groupValues?.get(2)?.toIntOrNull(),
                path = OUTBOUND.find(text)?.groupValues?.get(1) ?: "unknown",
                error = error,
            )
        }

        private fun errorKind(text: String): String? = when {
            "network is unreachable" in text || "no route to host" in text -> "unreachable"
            "timeout" in text || "timed out" in text || "deadline exceeded" in text -> "timeout"
            "connection refused" in text -> "refused"
            "connection reset" in text || "reset by peer" in text -> "reset"
            "eof" in text -> "eof"
            "broken pipe" in text -> "broken_pipe"
            "tls" in text || "certificate" in text || "handshake" in text -> "tls"
            "rejected" in text || "blocked" in text -> "rejected"
            "error" in text || "failed" in text -> "other"
            else -> null
        }
    }
}

const val MAX_CONNECTION_FAILURE_KINDS = 32
