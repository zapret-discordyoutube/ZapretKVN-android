package io.github.zapretkvn.android.vpn

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class HysteriaExecutionKind(val wireValue: String) {
    Native("native"),
    OfficialHysteriaSidecar("official_hysteria_sidecar"),
    Unsupported("unsupported"),
}

internal enum class HysteriaSwitchKind(val wireValue: String) {
    NativeHotSwitch("native_hot_switch"),
    FullSidecarTransition("full_sidecar_transition"),
    Unsupported("unsupported"),
}

internal enum class HysteriaRuntimeState {
    IDLE,
    PLANNING,
    STARTING_FRONT,
    STARTING_SIDECAR,
    WAITING_RELAY,
    READY,
    SWITCH_REQUESTED,
    PREPARING_REPLACEMENT,
    REPLACEMENT_READY,
    COMMITTING_SWITCH,
    STOPPING_OLD,
    DEGRADED,
    FAILED,
    STOPPING,
}

internal enum class HysteriaFailureCode {
    TARGET_NETWORK_TIMEOUT,
    TARGET_CONNECTION_REFUSED,
    TARGET_TLS_INTERNAL,
    TARGET_TLS_UNKNOWN_AUTHORITY,
    TARGET_PIN_MISMATCH,
    TARGET_AUTH_REJECTED,
    TARGET_OBFS_REJECTED,
    LOCAL_CONFIG_INVALID,
    LOCAL_RUNTIME_UNSUPPORTED,
    LOCAL_BIND_COLLISION,
    LOCAL_PROCESS_START_FAILED,
    LOCAL_PROCESS_EXITED,
    LOCAL_RELAY_NOT_READY,
    LOCAL_RELAY_DIED,
    LOCAL_FRONT_NOT_READY,
    LOCAL_CONTROL_PLANE_UNAVAILABLE,
    TARGET_NOT_IN_ACTIVE_POOL,
    TARGET_RUNTIME_INCOMPATIBLE,
    NO_COMPATIBLE_FALLBACK,
    TRANSITION_STALE_GENERATION,
    TRANSITION_DEADLINE_EXCEEDED,
    TRANSITION_ROLLBACK_FAILED,
}

internal val AUTOMATIC_HYSTERIA_SWITCH_FAILURES = setOf(
    HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
    HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
    HysteriaFailureCode.LOCAL_PROCESS_EXITED,
    HysteriaFailureCode.LOCAL_RELAY_DIED,
    HysteriaFailureCode.LOCAL_RELAY_NOT_READY,
    HysteriaFailureCode.LOCAL_CONTROL_PLANE_UNAVAILABLE,
)

internal val HYSTERIA_SECURITY_FAILURES = setOf(
    HysteriaFailureCode.TARGET_TLS_INTERNAL,
    HysteriaFailureCode.TARGET_TLS_UNKNOWN_AUTHORITY,
    HysteriaFailureCode.TARGET_PIN_MISMATCH,
    HysteriaFailureCode.TARGET_AUTH_REJECTED,
    HysteriaFailureCode.TARGET_OBFS_REJECTED,
)

internal data class HysteriaCapability(
    val protocol: String = "hysteria2",
    val executionKind: HysteriaExecutionKind,
    val obfsKind: String,
    val tlsKind: String,
    val endpointKind: String,
    val switchKind: HysteriaSwitchKind,
    val runtimeRequirements: Set<String>,
    val valid: Boolean,
    val failureCode: HysteriaFailureCode? = null,
    val validationMessage: String = "",
)

internal object HysteriaCapabilityClassifier {
    private val pinPattern = Regex("^[0-9a-f]{64}$")
    private val ipv4Pattern = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val trueValues = setOf("1", "true", "yes", "on", "t")
    private val falseValues = setOf("0", "false", "no", "off", "f", "")

    fun classify(rawUri: String): HysteriaCapability {
        if (rawUri.isBlank() || rawUri.any { it.isWhitespace() || it.isISOControl() }) {
            return invalid("Hysteria2 URI contains whitespace or a control character")
        }
        val scheme = rawUri.substringBefore(':').lowercase(Locale.ROOT)
        if (scheme !in setOf("hy2", "hysteria2") || !rawUri.startsWith("$scheme://", true)) {
            return invalid(
                "unsupported Hysteria2 URI scheme",
                HysteriaFailureCode.LOCAL_RUNTIME_UNSUPPORTED,
            )
        }
        val body = rawUri.substringAfter("://").substringBefore('#')
        val fullAuthority = body.substringBefore('?').substringBefore('/')
        val rawAuthentication = fullAuthority.substringBeforeLast('@', "")
        val authority = fullAuthority.substringAfterLast('@')
        val host: String
        val portUnion: String
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            if (closing <= 1) return invalid("Hysteria2 URI has invalid IPv6 server")
            host = decode(authority.substring(1, closing))
            val suffix = authority.substring(closing + 1)
            portUnion = suffix.removePrefix(":").ifBlank { "443" }
        } else {
            val colon = authority.lastIndexOf(':')
            host = decode(if (colon < 0) authority else authority.substring(0, colon))
            portUnion = if (colon < 0) "443" else authority.substring(colon + 1).ifBlank { "443" }
        }
        if (host.isBlank() || host.any(Char::isISOControl)) {
            return invalid("Hysteria2 URI has invalid server")
        }
        val portHopping = ',' in portUnion || '-' in portUnion
        for (part in portUnion.split(',')) {
            val bounds = part.split('-', limit = 2).map(String::toIntOrNull)
            if (bounds.any { it == null || it !in 1..65535 } ||
                (bounds.size == 2 && checkNotNull(bounds[0]) > checkNotNull(bounds[1]))
            ) {
                return invalid("Hysteria2 URI has invalid port union")
            }
        }

        val query = linkedMapOf<String, String>()
        body.substringAfter('?', "")
            .split('&')
            .filter(String::isNotBlank)
            .forEach { item ->
                val key = canonicalQueryKey(decode(item.substringBefore('=')))
                val value = decode(item.substringAfter('=', ""))
                if ((key + value).any(Char::isISOControl)) {
                    return invalid("Hysteria2 URI query contains a control character")
                }
                query[key] = value
            }

        val authentication = decode(rawAuthentication).ifBlank { query["auth"].orEmpty() }
        if (authentication.isBlank()) return invalid("Hysteria2 URI is missing authentication")
        if (authentication.any(Char::isISOControl)) {
            return invalid("Hysteria2 authentication contains a control character")
        }

        val insecureText = query["insecure"].orEmpty().trim().lowercase(Locale.ROOT)
        if (insecureText !in trueValues && insecureText !in falseValues) {
            return invalid("Hysteria2 URI has invalid insecure value")
        }
        val insecure = insecureText in trueValues
        val pin = query["pinsha256"].orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(":", "")
            .replace("-", "")
        if (pin.isNotEmpty() && !pinPattern.matches(pin)) {
            return invalid("Hysteria2 pinSHA256 must contain exactly 32 SHA-256 bytes")
        }
        if (insecure && pin.isEmpty()) {
            return invalid("Hysteria2 insecure requires certificate pin")
        }
        val obfs = query["obfs"].orEmpty().trim().lowercase(Locale.ROOT)
        val obfsKind = if (obfs in setOf("", "none", "plain")) "none" else obfs
        if (obfsKind !in setOf("none", "salamander", "gecko")) {
            return invalid(
                "Hysteria2 obfs '$obfsKind' is unsupported",
                HysteriaFailureCode.LOCAL_RUNTIME_UNSUPPORTED,
            )
        }
        if (obfsKind != "none" && query["obfspassword"].isNullOrEmpty()) {
            return invalid("invalid hysteria2 link: $obfsKind obfs requires obfs-password")
        }
        val endpointKind = when {
            ':' in host -> "ipv6"
            ipv4Pattern.matches(host) && host.split('.').all { it.toIntOrNull() in 0..255 } -> "ipv4"
            else -> "dns"
        }
        val requirements = buildSet {
            add("raw_uri_required")
            if (pin.isNotEmpty()) add("pin_required")
            if (portHopping) add("port_hopping_required")
        }
        return HysteriaCapability(
            executionKind = HysteriaExecutionKind.Native,
            obfsKind = obfsKind,
            tlsKind = if (pin.isEmpty()) "ca" else "pinned",
            endpointKind = endpointKind,
            switchKind = HysteriaSwitchKind.NativeHotSwitch,
            runtimeRequirements = requirements,
            valid = true,
        )
    }

    private fun invalid(
        message: String,
        code: HysteriaFailureCode = HysteriaFailureCode.LOCAL_CONFIG_INVALID,
    ) = HysteriaCapability(
        executionKind = HysteriaExecutionKind.Unsupported,
        obfsKind = "none",
        tlsKind = "ca",
        endpointKind = "dns",
        switchKind = HysteriaSwitchKind.Unsupported,
        runtimeRequirements = setOf("raw_uri_required"),
        valid = false,
        failureCode = code,
        validationMessage = message,
    )

    private fun canonicalQueryKey(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.ROOT).filterNot { it == '-' || it == '_' }
        return when (normalized) {
            "peer" -> "sni"
            "allowinsecure", "skipcertverify" -> "insecure"
            else -> normalized
        }
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}

internal object HysteriaFailureClassifier {
    fun classifyRuntime(message: String): HysteriaFailureCode? {
        val code = classify(message) ?: return null
        if (code !in setOf(
                HysteriaFailureCode.TARGET_NETWORK_TIMEOUT,
                HysteriaFailureCode.TARGET_CONNECTION_REFUSED,
            )
        ) {
            return code
        }
        val value = message.lowercase(Locale.ROOT)
        return code.takeIf {
            listOf("hysteria", "hy2", "quic", "handshake", "server", "udp").any(value::contains) ||
                "no recent network activity" in value
        }
    }

    fun classify(message: String, processExited: Boolean = false): HysteriaFailureCode? {
        val value = message.lowercase(Locale.ROOT)
        return when {
            "pin" in value && listOf("mismatch", "does not match", "invalid").any(value::contains) ->
                HysteriaFailureCode.TARGET_PIN_MISMATCH
            "unknown authority" in value || "certificate signed by unknown" in value ->
                HysteriaFailureCode.TARGET_TLS_UNKNOWN_AUTHORITY
            "tls: internal error" in value || "crypto_error 0x150" in value ->
                HysteriaFailureCode.TARGET_TLS_INTERNAL
            listOf("authentication failed", "auth rejected", "access denied").any(value::contains) ->
                HysteriaFailureCode.TARGET_AUTH_REJECTED
            "obfs" in value && listOf("reject", "invalid", "failed").any(value::contains) ->
                HysteriaFailureCode.TARGET_OBFS_REJECTED
            listOf("no recent network activity", "i/o timeout", "network timeout", "deadline exceeded").any(value::contains) ->
                HysteriaFailureCode.TARGET_NETWORK_TIMEOUT
            listOf("connection refused", "actively refused", "forcibly closed").any(value::contains) ->
                HysteriaFailureCode.TARGET_CONNECTION_REFUSED
            "address already in use" in value || "only one usage of each socket" in value ->
                HysteriaFailureCode.LOCAL_BIND_COLLISION
            processExited -> HysteriaFailureCode.LOCAL_PROCESS_EXITED
            else -> null
        }
    }
}

internal data class HysteriaRuntimeSession(
    val sessionGeneration: Long = 0,
    val selectedNodeId: String? = null,
    val runtimeKind: String = "",
    val sidecarKind: String = "",
    val sidecarProcessGeneration: Long = 0,
    val relayHost: String = "127.0.0.1",
    val relayPort: Int = 0,
    val relayCredentialsGeneration: Long = 0,
    val frontProcessGeneration: Long = 0,
    val frontTargetGeneration: Long = 0,
    val outboundPoolTags: Set<String> = emptySet(),
    val startedAtMonotonic: Long = 0,
    val readyAtMonotonic: Long = 0,
    val failureEpisodeId: Long = 0,
    val lastFailureCode: HysteriaFailureCode? = null,
    val automaticSwitchAttempted: Boolean = false,
    val state: HysteriaRuntimeState = HysteriaRuntimeState.IDLE,
)

internal class HysteriaStateReducer(
    private val monotonicMillis: () -> Long,
) {
    var session = HysteriaRuntimeSession()
        private set

    fun begin(
        generation: Long,
        nodeId: String?,
        outboundPoolTags: Set<String> = emptySet(),
        preserveFailureEpisode: Boolean = false,
    ) {
        val previous = session
        session = HysteriaRuntimeSession(
            sessionGeneration = generation,
            selectedNodeId = nodeId,
            runtimeKind = "native",
            outboundPoolTags = outboundPoolTags,
            startedAtMonotonic = monotonicMillis(),
            failureEpisodeId = if (preserveFailureEpisode) previous.failureEpisodeId else 0,
            lastFailureCode = if (preserveFailureEpisode) previous.lastFailureCode else null,
            automaticSwitchAttempted = preserveFailureEpisode && previous.automaticSwitchAttempted,
            state = HysteriaRuntimeState.PLANNING,
        )
    }

    fun reset() {
        session = HysteriaRuntimeSession()
    }

    fun advance(generation: Long, state: HysteriaRuntimeState): Boolean {
        if (generation != session.sessionGeneration) return false
        session = session.copy(
            state = state,
            readyAtMonotonic = if (state == HysteriaRuntimeState.READY) {
                monotonicMillis()
            } else {
                session.readyAtMonotonic
            },
        )
        return true
    }

    fun fail(
        generation: Long,
        code: HysteriaFailureCode,
        automaticSwitch: Boolean,
    ): Boolean {
        if (generation != session.sessionGeneration) return false
        session = session.copy(
            failureEpisodeId = session.failureEpisodeId + 1,
            lastFailureCode = code,
            automaticSwitchAttempted = automaticSwitch,
            state = if (automaticSwitch) {
                HysteriaRuntimeState.SWITCH_REQUESTED
            } else {
                HysteriaRuntimeState.FAILED
            },
        )
        return true
    }

    fun terminal(
        generation: Long,
        code: HysteriaFailureCode,
        degraded: Boolean = false,
    ): Boolean {
        if (generation != session.sessionGeneration) return false
        session = session.copy(
            lastFailureCode = code,
            state = if (degraded) HysteriaRuntimeState.DEGRADED else HysteriaRuntimeState.FAILED,
        )
        return true
    }

    fun commitTarget(generation: Long, nodeId: String): Boolean {
        if (generation != session.sessionGeneration) return false
        session = session.copy(
            selectedNodeId = nodeId,
            frontTargetGeneration = session.frontTargetGeneration + 1,
            state = HysteriaRuntimeState.READY,
            readyAtMonotonic = monotonicMillis(),
        )
        return true
    }
}

internal data class HysteriaFallbackTarget(
    val id: String,
    val capability: HysteriaCapability,
    val maintenance: Boolean = false,
)

internal class HysteriaTransitionCoordinator(
    private val monotonicMillis: () -> Long,
    private val cooldownMillis: Long = 300_000,
) {
    private val cooldownUntil = mutableMapOf<String, Long>()
    private var replacementInFlight = false
    private var replacementAttempted = false
    private var lastCommitAt = Long.MIN_VALUE
    var failureEpisodeId: Long = 0
        private set

    fun chooseReplacement(
        failedId: String,
        failure: HysteriaFailureCode,
        orderedTargets: List<HysteriaFallbackTarget>,
        ignoreStaleLogFence: Boolean = false,
    ): HysteriaFallbackTarget? {
        if (failure !in AUTOMATIC_HYSTERIA_SWITCH_FAILURES || replacementInFlight || replacementAttempted) {
            return null
        }
        val now = monotonicMillis()
        if (
            !ignoreStaleLogFence &&
            lastCommitAt != Long.MIN_VALUE &&
            now - lastCommitAt < STALE_LOG_FENCE_MILLIS
        ) {
            return null
        }
        val replacement = orderedTargets.firstOrNull { target ->
            target.id != failedId &&
                !target.maintenance &&
                target.capability.valid &&
                target.capability.executionKind == HysteriaExecutionKind.Native &&
                cooldownUntil.getOrDefault(target.id, 0) <= now
        } ?: return null
        failureEpisodeId++
        replacementAttempted = true
        replacementInFlight = true
        cooldownUntil[failedId] = now + cooldownMillis
        return replacement
    }

    fun commitReplacement() {
        replacementInFlight = false
        replacementAttempted = false
        lastCommitAt = monotonicMillis()
    }

    fun failReplacement() {
        replacementInFlight = false
        // replacementAttempted intentionally stays true: one episode gets no
        // automatic second target.
    }

    fun automaticAttempted(): Boolean = replacementAttempted

    fun replacementInFlight(): Boolean = replacementInFlight

    fun onSessionReady() {
        replacementInFlight = false
        replacementAttempted = false
    }

    fun reset() {
        replacementInFlight = false
        replacementAttempted = false
        lastCommitAt = Long.MIN_VALUE
    }

    private companion object {
        const val STALE_LOG_FENCE_MILLIS = 2_000L
    }
}
