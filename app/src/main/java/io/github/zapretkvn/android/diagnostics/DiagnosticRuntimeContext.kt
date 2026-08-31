package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.OutboundDescription
import java.security.MessageDigest

/**
 * The only connection identity which is allowed to cross the diagnostics
 * boundary.  Endpoint is deliberately an opaque reference: a support report
 * can distinguish two connections without containing a host, URI or port
 * credentials.
 */
data class DiagnosticTargetContext(
    val profileRef: String? = null,
    val profileName: String? = null,
    val outboundTag: String? = null,
    val protocol: String? = null,
    val endpoint: String? = null,
)

/**
 * Maps libbox's raw outbound markers to a safe, structured target.  The raw
 * map is process-local and is never serialized or put into a DiagnosticLogLine.
 */
internal class DiagnosticRuntimeMap private constructor(
    val profile: DiagnosticTargetContext,
    private val targetsByRawTag: Map<String, DiagnosticTargetContext>,
    private val selectedRawTag: String?,
) {
    fun resolve(rawMessage: String): DiagnosticTargetContext {
        val matched = targetsByRawTag.entries.firstOrNull { (rawTag, _) ->
            rawTag.isNotBlank() && (
                rawMessage.contains("[$rawTag]") ||
                    rawMessage.contains("outbound/$rawTag") ||
                    rawMessage.contains("outbound $rawTag")
                )
        }?.value
        return matched
            ?: selectedRawTag?.let(targetsByRawTag::get)
            ?: profile
    }

    companion object {
        fun create(
            profileId: String,
            profileName: String?,
            descriptions: Map<String, OutboundDescription>,
            selectedRawTag: String?,
        ): DiagnosticRuntimeMap {
            val profile = DiagnosticTargetContext(
                profileRef = profileReference(profileId),
                profileName = safeName(profileName),
            )
            val targets = descriptions.mapNotNull { (rawTag, description) ->
                val safeProtocol = safeProtocol(description.type)
                rawTag to profile.copy(
                    outboundTag = safeTag(rawTag, "tag"),
                    protocol = safeProtocol,
                    endpoint = safeEndpoint(safeProtocol, description),
                )
            }.toMap()
            return DiagnosticRuntimeMap(
                profile = profile,
                targetsByRawTag = targets,
                selectedRawTag = selectedRawTag,
            )
        }

        fun profileOnly(profileId: String?): DiagnosticRuntimeMap? = profileId
            ?.takeIf(String::isNotBlank)
            ?.let {
                DiagnosticRuntimeMap(
                    profile = DiagnosticTargetContext(profileRef = profileReference(it)),
                    targetsByRawTag = emptyMap(),
                    selectedRawTag = null,
                )
            }

        private fun profileReference(value: String): String =
            "profile-${digest(value).take(12)}"

        private fun safeName(value: String?): String? {
            val candidate = value
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.trim()
                ?.take(80)
                ?.takeIf(String::isNotBlank)
                ?: return null
            if (SecretRedactor.redactInline(candidate) != candidate) return null
            if (URI_SHAPE.containsMatchIn(candidate)) return null
            return candidate
        }

        private fun safeTag(value: String, prefix: String): String {
            val candidate = value.trim().takeIf(String::isNotBlank)
            if (candidate != null && SAFE_TAG.matches(candidate) &&
                SecretRedactor.redactInline(candidate) == candidate
            ) {
                return candidate.take(80)
            }
            return "$prefix-${digest(value).take(12)}"
        }

        private fun safeProtocol(value: String): String = value
            .trim()
            .lowercase()
            .takeIf(SAFE_PROTOCOL::matches)
            ?: "unknown"

        private fun safeEndpoint(
            protocol: String,
            description: OutboundDescription,
        ): String? {
            val raw = description.endpoint ?: description.serverHost ?: return null
            val port = PORT.find(raw)?.groupValues?.getOrNull(1)
            return "ep-${digest("$protocol|$raw").take(12)}" +
                port?.let { ":$it" }.orEmpty()
        }

        private fun digest(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private val SAFE_TAG = Regex("[A-Za-z0-9_.:-]{1,80}")
        private val SAFE_PROTOCOL = Regex("[a-z0-9_-]{1,24}")
        private val PORT = Regex(":([0-9]{1,5})$")
        private val URI_SHAPE = Regex("(?i)\\b[a-z][a-z0-9+.-]*://")
    }
}
