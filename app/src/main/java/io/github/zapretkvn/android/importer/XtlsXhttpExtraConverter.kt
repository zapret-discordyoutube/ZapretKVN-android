package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts the XTLS VLESS share-link XHTTP `extra` object to the JSON schema used
 * by the pinned sing-box-extended core.
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

        rejectUnknownFields(source, EXTRA_FIELDS.keys + "xmux", "XHTTP extra")
        val options = linkedMapOf<String, JsonElement>()
        EXTRA_FIELDS.forEach { (sourceKey, targetKey) ->
            source[sourceKey]?.let { options[targetKey] = it }
        }
        (source["xmux"] as? JsonObject)?.let { xmuxSource ->
            rejectUnknownFields(
                xmuxSource,
                XMUX_FIELDS.keys + LEGACY_C_MAX_LIFETIME_MS,
                "XHTTP extra.xmux",
            )
            if (
                LEGACY_C_MAX_LIFETIME_MS in xmuxSource &&
                "hMaxReusableSecs" in xmuxSource
            ) {
                throw ImportException(
                    "XHTTP extra.xmux не может одновременно содержать " +
                        "cMaxLifetimeMs и hMaxReusableSecs.",
                )
            }
            val xmux = linkedMapOf<String, JsonElement>()
            XMUX_FIELDS.forEach { (sourceKey, targetKey) ->
                xmuxSource[sourceKey]?.let { xmux[targetKey] = it }
            }
            xmuxSource[LEGACY_C_MAX_LIFETIME_MS]?.let {
                xmux["h_max_reusable_secs"] = convertLegacyLifetime(it)
            }
            if (xmux.isNotEmpty()) options["xmux"] = JsonObject(xmux)
        } ?: source["xmux"]?.let {
            throw ImportException("XHTTP extra.xmux должен быть JSON-объектом.")
        }
        return options.takeIf(Map<*, *>::isNotEmpty)?.let(::JsonObject)
    }

    private fun rejectUnknownFields(
        source: JsonObject,
        supported: Set<String>,
        label: String,
    ) {
        val unknown = (source.keys - supported).sorted()
        if (unknown.isNotEmpty()) {
            throw ImportException(
                "$label содержит неподдерживаемые поля: ${unknown.joinToString()}.",
            )
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

    private val EXTRA_FIELDS = linkedMapOf(
        "headers" to "headers",
        "xPaddingBytes" to "x_padding_bytes",
        "xPaddingObfsMode" to "x_padding_obfs_mode",
        "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement",
        "xPaddingMethod" to "x_padding_method",
        "uplinkHTTPMethod" to "uplink_http_method",
        "sessionPlacement" to "session_placement",
        "sessionKey" to "session_key",
        "seqPlacement" to "seq_placement",
        "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "serverMaxHeaderBytes" to "server_max_header_bytes",
    )

    private val XMUX_FIELDS = linkedMapOf(
        "cMaxReuseTimes" to "c_max_reuse_times",
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    private const val LEGACY_C_MAX_LIFETIME_MS = "cMaxLifetimeMs"
    private const val MILLIS_PER_SECOND = 1_000L
    private val LEGACY_LIFETIME_PATTERN = Regex("""^(\d+)(?:-(\d+))?$""")
}
