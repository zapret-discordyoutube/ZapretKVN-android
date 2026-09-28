package io.github.zapretkvn.android.engines.hysteria

import io.github.zapretkvn.android.diagnostics.RuntimeErrors
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

internal enum class HysteriaFailureCode {
    CORE_UNCLASSIFIED,
    LOCAL_RELAY_AUTH_REJECTED,
    LOCAL_SOCKET_PROTECTION_FAILED,
    TARGET_TLS_REJECTED,
    TARGET_CONNECTION_CLOSED,
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
    private val malformedPercentPattern = Regex("%(?![0-9a-fA-F]{2})")
    private val knownQueryKeys = setOf(
        "auth",
        "sni",
        "insecure",
        "obfs",
        "obfspassword",
        "up",
        "down",
        "pinsha256",
        "ech",
        "hopinterval",
        "minpacketsize",
        "maxpacketsize",
    )

    fun classify(rawUri: String): HysteriaCapability {
        if (rawUri.isBlank() || rawUri.any { it.isWhitespace() || it.isISOControl() }) {
            return invalid("Hysteria2 URI contains whitespace or a control character")
        }
        if (malformedPercentPattern.containsMatchIn(rawUri)) {
            return invalid("Hysteria2 URI has invalid percent encoding")
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
            if (authority.count { it == ':' } > 1) {
                return invalid("Hysteria2 URI requires brackets around IPv6 server")
            }
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
                if (key in knownQueryKeys && key in query) {
                    return invalid("Hysteria2 URI repeats a known query parameter")
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
            "servername", "peer" -> "sni"
            "allowinsecure", "skipcertverify" -> "insecure"
            "upmbps", "upbps" -> "up"
            "downmbps", "downbps" -> "down"
            else -> normalized
        }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
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
        return RuntimeErrors.classifyForRecovery(message)?.let(HysteriaFailureCode::valueOf)
            ?: HysteriaFailureCode.LOCAL_PROCESS_EXITED.takeIf { processExited }
    }
}

/**
 * Hysteria folds its URI-capability rules into the protocol-neutral
 * [io.github.zapretkvn.android.engines.failover.FailoverTarget.valid] flag: a
 * target is eligible only when the capability parsed cleanly and runs on the
 * native libbox runtime.
 */
internal fun HysteriaCapability.isFailoverEligible(): Boolean =
    valid && executionKind == HysteriaExecutionKind.Native
