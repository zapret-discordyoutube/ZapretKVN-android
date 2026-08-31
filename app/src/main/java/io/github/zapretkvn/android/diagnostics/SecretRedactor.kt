package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SecretRedactor {
    const val MASK = "•••"

    fun redact(text: String): String {
        val jsonRedacted = runCatching {
            JsonConfig.format(redactElement(JsonConfig.parse(text)))
        }.getOrNull()
        return redactInline(jsonRedacted ?: text)
    }

    fun redactInline(text: String): String {
        var result = text
        // A diagnostic must not retain a partially redacted URI.  Keeping the
        // scheme/host/fragment made Hysteria2 links identifiable and allowed a
        // credential-like fragment to survive the old query-only pass.
        result = URI.replace(result, MASK)
        result = PEM_BLOCK.replace(result, MASK)
        result = HEADER_SECRET.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$MASK"
        }
        result = BEARER_SECRET.replace(result, "Bearer $MASK")
        result = KEY_VALUE_SECRET.replace(result) { match -> "${match.groupValues[1]}$MASK" }
        result = UUID.replace(result, MASK)
        result = JSON_SECRET.replace(result) { match ->
            "${match.groupValues[1]}$MASK${match.groupValues[3]}"
        }
        return result
    }

    private fun redactElement(element: JsonElement, key: String? = null): JsonElement {
        if (key != null && normalizeKey(key) in SECRET_KEYS) return JsonPrimitive(MASK)
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (childKey, child) ->
                redactElement(child, childKey)
            })
            is JsonArray -> JsonArray(element.map { redactElement(it) })
            else -> element
        }
    }

    private fun normalizeKey(value: String): String = value
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private val SECRET_KEYS = setOf(
        "uuid",
        "password",
        "token",
        "accesstoken",
        "authorization",
        "auth",
        "privatekey",
        "publickey",
        "secretkey",
        "clientsecret",
        "clientkey",
        "obfspassword",
        "presharedkey",
        "pinsha256",
        "pinsha",
        "certificatesha256",
        "certificatehash",
        "certificate",
        "certificatepath",
        "certificatechain",
        "cert",
        "clientcertificatepath",
        "clientkeypath",
        "ech",
        "echconfig",
        "echconfigpem",
        "echconfigpath",
        "shortid",
    )
    private val URI = Regex(
        // Commas and semicolons are valid inside Hysteria2 port unions/query
        // values. Keep consuming them so no recognizable URI suffix survives.
        "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\\\"'<>\\\\}]+",
    )
    private val PEM_BLOCK = Regex(
        "(?s)-----BEGIN [^-\\r\\n]+-----.*?-----END [^-\\r\\n]+-----",
    )
    private val HEADER_SECRET = Regex(
        "(?i)\\b(token|password|passwd|secret|authorization|auth|uuid|" +
            "obfs[-_]?password|pin[-_]?sha256|certificate(?:[-_]?(?:sha256|hash|path))?|" +
            "client[-_]?certificate[-_]?path|ech(?:[-_]?config)?(?:[-_]?path)?|" +
            "(?:private|public|secret|client|preshared)[-_]?key(?:[-_]?path)?|" +
            "short[-_]?id)(\\s*:\\s*)" +
            "(?:bearer\\s+)?[^\\s,;\\\"\\\\}]+",
    )
    private val BEARER_SECRET = Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]+=*")
    private val UUID = Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")
    private val KEY_VALUE_SECRET = Regex(
        "(?i)((?:token|password|passwd|secret|authorization|auth|uuid|" +
            "obfs[-_]?password|pin[-_]?sha256|certificate(?:[-_]?(?:sha256|hash|path))?|" +
            "client[-_]?certificate[-_]?path|ech(?:[-_]?config)?(?:[-_]?path)?|" +
            "(?:private|public|secret|client|preshared)[-_]?key(?:[-_]?path)?|" +
            "short[-_]?id)=)" +
            "[^&\\s#\\\"\\\\},;]+",
    )
    private val JSON_SECRET = Regex(
        "(?is)(\\\"(?:uuid|password|token|access[_-]?token|authorization|auth|" +
            "private[-_]?key|public[-_]?key|secret[-_]?key|client[-_]?secret|" +
            "client[-_]?key|obfs[-_]?password|preshared[-_]?key|pin[-_]?sha256|" +
            "certificate(?:[-_]?(?:sha256|hash|path))?|client[-_]?certificate[-_]?path|" +
            "cert|ech(?:[-_]?config)?(?:[-_]?path)?|short[-_]?id)" +
            "\\\"\\s*:\\s*\\\")(.*?)(\\\")",
    )
}
