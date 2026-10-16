package io.github.zapretkvn.android.importer

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class ImportedActivityFlag(val label: String) {
    Ntp("сетевой NTP"),
    RemoteRuleSet("загрузка remote rule-set"),
    ClashController("внешний Clash controller/UI"),
    VerboseLog("подробный runtime-лог"),
    ExplicitKeepalive("явный keepalive/heartbeat"),
}

object ImportedConfigActivityScanner {
    fun scan(rawJson: String): Set<ImportedActivityFlag> {
        val root = JsonConfig.parse(rawJson) as? JsonObject ?: return emptySet()
        return buildSet {
            val ntp = root["ntp"] as? JsonObject
            if (ntp != null && ntp["enabled"].let { it == null || (it as? JsonPrimitive)?.booleanOrNull != false }) {
                add(ImportedActivityFlag.Ntp)
            }

            val route = root["route"] as? JsonObject
            val ruleSets = route?.get("rule_set") as? JsonArray
            if (ruleSets.orEmpty().any { element ->
                    element.objectString("type") == "remote" ||
                        (element as? JsonObject)?.get("url") != null
                }
            ) {
                add(ImportedActivityFlag.RemoteRuleSet)
            }

            val experimental = root["experimental"] as? JsonObject
            val clashApi = experimental?.get("clash_api") as? JsonObject
            if (clashApi?.any { (key, value) ->
                    key in CLASH_EXTERNAL_KEYS && value.nonBlankPrimitive()
                } == true
            ) {
                add(ImportedActivityFlag.ClashController)
            }

            val logLevel = (root["log"] as? JsonObject)
                ?.get("level")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.lowercase()
            if (logLevel in setOf("trace", "debug")) add(ImportedActivityFlag.VerboseLog)

            if (root.containsKeyRecursively { key ->
                    val normalized = key.lowercase().replace('-', '_')
                    normalized in KEEPALIVE_KEYS || "keepalive" in normalized || "keep_alive" in normalized
                }
            ) {
                add(ImportedActivityFlag.ExplicitKeepalive)
            }
        }
    }

    fun warning(flags: Set<ImportedActivityFlag>): String? = flags
        .takeIf(Set<ImportedActivityFlag>::isNotEmpty)
        ?.joinToString(
            prefix = "Конфигурация может выполнять внешнюю или фоновую активность: ",
            separator = ", ",
            postfix = ". Эти настройки оставлены как есть.",
        ) { it.label }

    private fun JsonElement.objectString(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.nonBlankPrimitive(): Boolean =
        (this as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

    private fun JsonElement.containsKeyRecursively(predicate: (String) -> Boolean): Boolean = when (this) {
        is JsonObject -> entries.any { (key, value) -> predicate(key) || value.containsKeyRecursively(predicate) }
        is JsonArray -> any { it.containsKeyRecursively(predicate) }
        else -> false
    }

    private val CLASH_EXTERNAL_KEYS = setOf(
        "external_controller",
        "external_ui",
        "external_ui_download_url",
    )
    private val KEEPALIVE_KEYS = setOf(
        "keepalive",
        "keep_alive",
        "keep_alive_interval",
        "tcp_keep_alive",
        "tcp_keep_alive_interval",
        "heartbeat",
    )
}
