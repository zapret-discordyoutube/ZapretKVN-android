package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a complete Xray XHTTP object (or its standard `extra` subset) to the
 * JSON schema used by the pinned sing-box-extended core.
 *
 * The query parser has already applied percent-decoding. XTLS specifies `extra`
 * as encodeURIComponent(JSON), not Base64.
 */
internal object XtlsXhttpExtraConverter {
    fun convert(decodedExtra: String?): JsonObject? {
        if (decodedExtra.isNullOrBlank()) return null
        val source = try {
            JsonConfig.parse(decodedExtra) as? JsonObject
                ?: throw ImportException(
                    "XHTTP extra должен быть URL-кодированным JSON-объектом.",
                )
        } catch (error: ImportException) {
            throw error
        } catch (error: Exception) {
            throw ImportException(
                "Не удалось разобрать XHTTP extra: ожидается URL-кодированный JSON.",
                error,
            )
        }

        return convertObject(source, "XHTTP extra")
            .takeIf(Map<*, *>::isNotEmpty)
    }

    private fun convertObject(
        source: JsonObject,
        label: String,
    ): JsonObject {
        val options = linkedMapOf<String, JsonElement>()
        source.forEach { (sourceKey, value) ->
            when (sourceKey) {
                "type", "network" -> requireXhttpMarker(value, "$label.$sourceKey")
                "extra", "xhttpSettings", "splithttpSettings" -> {
                    val nested = value as? JsonObject
                        ?: throw ImportException("$label.$sourceKey должен быть JSON-объектом.")
                    merge(options, convertObject(nested, "$label.$sourceKey"), label)
                }
                "xmux" -> {
                    val xmuxSource = value as? JsonObject
                        ?: throw ImportException("$label.xmux должен быть JSON-объектом.")
                    putUnique(options, "xmux", convertXmux(xmuxSource, "$label.xmux"), label)
                }
                else -> putUnique(options, sourceKey.toSnakeCase(), value, label)
            }
        }
        return JsonObject(options)
    }

    private fun convertXmux(source: JsonObject, label: String): JsonObject {
        if (
            LEGACY_C_MAX_LIFETIME_MS in source &&
            source.keys.any { it != LEGACY_C_MAX_LIFETIME_MS && it.toSnakeCase() == "h_max_reusable_secs" }
        ) {
            throw ImportException(
                "$label не может одновременно содержать " +
                    "cMaxLifetimeMs и hMaxReusableSecs.",
            )
        }
        val xmux = linkedMapOf<String, JsonElement>()
        source.forEach { (sourceKey, value) ->
            if (sourceKey == LEGACY_C_MAX_LIFETIME_MS) {
                putUnique(
                    xmux,
                    "h_max_reusable_secs",
                    convertLegacyLifetime(value),
                    label,
                )
            } else {
                putUnique(xmux, sourceKey.toSnakeCase(), value, label)
            }
        }
        return JsonObject(xmux)
    }

    private fun merge(
        target: MutableMap<String, JsonElement>,
        source: JsonObject,
        label: String,
    ) {
        source.forEach { (key, value) -> putUnique(target, key, value, label) }
    }

    private fun putUnique(
        target: MutableMap<String, JsonElement>,
        key: String,
        value: JsonElement,
        label: String,
    ) {
        val previous = target[key]
        if (previous != null && previous != value) {
            throw ImportException("$label содержит конфликтующие значения поля $key.")
        }
        target[key] = value
    }

    private fun requireXhttpMarker(value: JsonElement, label: String) {
        val marker = (value as? JsonPrimitive)?.content
        if (!marker.equals("xhttp", ignoreCase = true) && !marker.equals("splithttp", ignoreCase = true)) {
            throw ImportException("$label должен иметь значение xhttp.")
        }
    }

    private fun String.toSnakeCase(): String = buildString(length + 8) {
        this@toSnakeCase.forEachIndexed { index, character ->
            val previous = this@toSnakeCase.getOrNull(index - 1)
            val next = this@toSnakeCase.getOrNull(index + 1)
            if (
                character.isUpperCase() &&
                index > 0 &&
                (
                    previous?.isLowerCase() == true ||
                        previous?.isDigit() == true ||
                        (previous?.isUpperCase() == true && next?.isLowerCase() == true)
                    )
            ) {
                append('_')
            }
            append(character.lowercaseChar())
        }
    }

    private fun convertLegacyLifetime(value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive
            ?: throw invalidLegacyLifetime()
        val match = LEGACY_LIFETIME_PATTERN.matchEntire(primitive.content)
            ?: throw invalidLegacyLifetime()
        val milliseconds = match.groupValues
            .drop(1)
            .filter(String::isNotEmpty)
            .map {
                it.toLongOrNull()
                    ?.takeIf { milliseconds -> milliseconds % MILLIS_PER_SECOND == 0L }
                    ?: throw invalidLegacyLifetime()
            }
        val seconds = milliseconds.map { it / MILLIS_PER_SECOND }
        return if (seconds.size == 2) {
            JsonPrimitive("${seconds[0]}-${seconds[1]}")
        } else if (primitive.isString) {
            JsonPrimitive(seconds.single().toString())
        } else {
            JsonPrimitive(seconds.single())
        }
    }

    private fun invalidLegacyLifetime() = ImportException(
        "XHTTP extra.xmux.cMaxLifetimeMs должен быть целым числом миллисекунд " +
            "или диапазоном с границами, кратными 1000.",
    )

    private const val LEGACY_C_MAX_LIFETIME_MS = "cMaxLifetimeMs"
    private const val MILLIS_PER_SECOND = 1_000L
    private val LEGACY_LIFETIME_PATTERN = Regex("""^(\d+)(?:-(\d+))?$""")
}
