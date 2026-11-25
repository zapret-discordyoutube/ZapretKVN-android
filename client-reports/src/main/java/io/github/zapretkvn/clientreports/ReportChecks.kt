package io.github.zapretkvn.clientreports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Schema 3 facts of an attempt (vpnbot «schema 3 live» 2026-11-19): the probes the app ran, raw.
 *
 * The app keeps no rule and no text (owner 2026-11-19: analytics live in vpnbot, the apps are open source). It names
 * each probe by its wire name, says what came back ([ReportCheck.result]), how long it took, for a flow probe how many
 * bytes it read, and for the system DNS whether its answer overlaps our DoH. vpnbot judges. Same names and limits as
 * Windows `client_reports/checks.py` and vpnbot `client_reports/contract.py`.
 */
data class ReportCheck(
    val result: String,
    val millis: Long,
    val bytes: Long? = null,
    val same: Boolean? = null,
)

object ReportChecks {
    /**
     * The app's own health check through the tunnel (vpnbot 2026-11-20): sent on a failure with no core code where the
     * check got no data. Off until vpnbot's «health live»: before that a report with it is refused as an unknown probe.
     */
    const val HEALTH = "health"
    const val HEALTH_LIVE = true

    /**
     * The WARP entry hop's start (owner 2026-11-25: «make more logs and put them in the database»): the app's own
     * account registration and whether the hop's tunnel came up. Agreed with the WARP chat; vpnbot must admit the two
     * names (and settings.warp_entry, details warp_retries) first: until its «live» message everything WARP stays out
     * of the report, because a report with an unknown probe is refused whole. Same names and flag as Windows
     * `client_reports/checks.py`.
     */
    const val WARP_REGISTER = "warp_register"
    const val WARP_TUNNEL = "warp_tunnel"
    val WARP_PROBES = listOf(WARP_REGISTER, WARP_TUNNEL)
    /** False until vpnbot's «live» (then the default becomes true in code, with a release); a test flips it and restores it. */
    @Volatile
    var warpLive = false

    /** Every probe the contract admits, in its order. */
    val PROBES: List<String>
        get() = BASE_PROBES + listOfNotNull(HEALTH.takeIf { HEALTH_LIVE }) + (if (warpLive) WARP_PROBES else emptyList())

    private val BASE_PROBES = listOf(
        "control_front", "control_neutral", "dns_system", "dns_doh", "tcp_preflight", "tcp_target", "tcp_443",
        "tls_real", "tls_neutral", "tls_front", "quic_neutral", "quic_target", "tunnel_a", "tunnel_b", "direct_a",
        "direct_b", "flow",
    )

    /** Probes that move data: only they may carry bytes. */
    val FLOW_PROBES = setOf("tunnel_a", "tunnel_b", "direct_a", "direct_b", "flow")
    val DNS_PROBES = setOf("dns_system", "dns_doh")

    const val ANSWERED = "answered"
    const val TIMEOUT = "timeout"

    /** RST or EOF after the connection was up. */
    const val RESET = "reset"

    /** RST to SYN: something at the address answered. */
    const val REFUSED = "refused"

    /** The probe could not run for a local reason: proves nothing. */
    const val UNKNOWN = "unknown"

    /** DNS only: the name does not exist (DoH: every trusted resolver that answered said so). */
    const val NXDOMAIN = "nxdomain"
    val RESULTS = listOf(ANSWERED, TIMEOUT, RESET, REFUSED, UNKNOWN)

    const val MAX_CHECK_MS = 60_000L
    const val MAX_CHECK_BYTES = 1_000_000_000L

    val CARRIERS = setOf("tcp", "udp")
    const val NETWORK_LOST = "NETWORK_LOST"
    private val CORE_CODE = Regex("^(TARGET|LOCAL)_[A-Z0-9_]{1,48}$")

    fun isValid(probe: String, check: ReportCheck): Boolean {
        if (probe !in PROBES) return false
        val allowed = if (probe in DNS_PROBES) RESULTS + NXDOMAIN else RESULTS
        if (check.result !in allowed || check.millis !in 0..MAX_CHECK_MS) return false
        if (check.bytes != null && (probe !in FLOW_PROBES || check.bytes !in 0..MAX_CHECK_BYTES)) return false
        if (check.same != null && (probe != "dns_system" || check.result != ANSWERED)) return false
        return true
    }

    /**
     * The WARP hop's probes from the app's own facts. Empty until vpnbot admits them ([warpLive]), when the hop was
     * off, and for a step that did not run (null: the account already existed, nothing was asked).
     */
    fun warpChecks(enabled: Boolean, register: ReportCheck?, tunnel: ReportCheck?): Map<String, ReportCheck> {
        if (!warpLive || !enabled) return emptyMap()
        return listOfNotNull(
            register?.let { WARP_REGISTER to it },
            tunnel?.let { WARP_TUNNEL to it },
        ).filter { (probe, check) -> isValid(probe, check) }.toMap()
    }

    fun areValid(checks: Map<String, ReportCheck>): Boolean = checks.all { (probe, check) -> isValid(probe, check) }

    /** The checks in the contract's order, invalid ones left out (a bad probe never loses the report). */
    fun ordered(checks: Map<String, ReportCheck>): Map<String, ReportCheck> =
        PROBES.mapNotNull { probe -> checks[probe]?.takeIf { isValid(probe, it) }?.let { probe to it } }.toMap()

    fun json(check: ReportCheck): JsonObject = buildJsonObject {
        put("r", check.result)
        put("ms", check.millis)
        check.bytes?.let { put("b", it) }
        check.same?.let { put("same", it) }
    }

    fun parse(json: JsonObject): ReportCheck? = runCatching {
        ReportCheck(
            result = (json["r"] as JsonPrimitive).content,
            millis = (json["ms"] as JsonPrimitive).longOrNull ?: return null,
            bytes = (json["b"] as? JsonPrimitive)?.longOrNull,
            same = (json["same"] as? JsonPrimitive)?.booleanOrNull,
        )
    }.getOrNull()

    fun clampMillis(value: Long): Long = value.coerceIn(0, MAX_CHECK_MS)

    /**
     * The core's own code as the contract admits it, or "" (CORE_UNCLASSIFIED, TRANSITION_* … are not codes of the
     * server path: they are left out rather than refusing the report).
     */
    fun coreCodeOf(value: String?): String {
        val text = value.orEmpty().trim()
        return if (text == NETWORK_LOST || CORE_CODE.matches(text)) text else ""
    }

    private val UDP_TYPES = setOf("hysteria2", "hy2", "hysteria", "tuic", "wireguard", "wg", "awg", "amneziawg")
    private val UDP_TRANSPORTS = setOf("quic", "kcp", "mkcp", "udp")

    /**
     * What the core's first hop to the server runs over: UDP for QUIC-based and WireGuard lines, mKCP and QUIC
     * transports, XHTTP over HTTP/3; TCP for everything else.
     */
    fun carrierOf(type: String?, transport: String? = null, alpn: List<String> = emptyList()): String {
        if (type.orEmpty().lowercase() in UDP_TYPES) return "udp"
        val kind = transport.orEmpty().lowercase()
        if (kind in UDP_TRANSPORTS) return "udp"
        if (kind == "xhttp" && alpn.map(String::lowercase) == listOf("h3")) return "udp"
        return "tcp"
    }

    /** Core codes that prove the server's address answered: a path probe would only repeat what is known. */
    val ANSWERED_CORE_CODES = setOf(
        "TARGET_AUTH_REJECTED", "TARGET_CONNECTION_REFUSED", "TARGET_CONNECTION_CLOSED", "TARGET_OBFS_REJECTED",
        "TARGET_TLS_REJECTED", "TARGET_TLS_INTERNAL", "TARGET_TLS_UNKNOWN_AUTHORITY", "TARGET_PIN_MISMATCH",
        "TARGET_DESTINATION_UNREACHABLE",
    )

    /** No probe when the core already proved the address answered, or the failure is the device's own. */
    fun needsPathCheck(coreCode: String): Boolean =
        coreCode !in ANSWERED_CORE_CODES && !coreCode.startsWith("LOCAL_") && coreCode != NETWORK_LOST
}
