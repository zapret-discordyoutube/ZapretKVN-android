package io.github.zapretkvn.android.config

import io.github.zapretkvn.android.profiles.MANAGED_SELECTOR_TAG_NAME

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SelectorGroup(
    val tag: String,
    val outbounds: List<String>,
    val default: String?,
)

data class OutboundDescription(
    val tag: String,
    val type: String,
    val serverHost: String?,
    val endpoint: String?,
)

object ConfigAnalyzer {
    const val MANAGED_SELECTOR_TAG = MANAGED_SELECTOR_TAG_NAME

    fun hasProfileDns(raw: String): Boolean = runCatching { ParsedConfig.parse(raw) }
        .getOrNull()?.let(::hasProfileDns) ?: false

    fun hasProfileDns(config: ParsedConfig): Boolean = runCatching {
        val root = config.root ?: return@runCatching false
        val dns = root["dns"] as? JsonObject ?: return@runCatching false
        val servers = (dns["servers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("tag") }
            .orEmpty()
        if (servers.isEmpty()) return@runCatching false
        val final = dns.string("final")
        final == null || final in servers
    }.getOrDefault(false)

    /**
     * Reports DNS servers that are present but unreachable from final/rules.
     * This is diagnostic only: user-authored JSON is never normalized here.
     */
    fun dnsWarnings(raw: String): List<String> = runCatching { ParsedConfig.parse(raw) }
        .getOrNull()?.let(::dnsWarnings) ?: emptyList()

    fun dnsWarnings(config: ParsedConfig): List<String> = runCatching {
        val root = config.root ?: return@runCatching emptyList()
        val dns = root["dns"] as? JsonObject ?: return@runCatching emptyList()
        val servers = (dns["servers"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        if (servers.size < 2) return@runCatching emptyList()
        val byTag = servers.mapNotNull { server ->
            server.string("tag")?.let { tag -> tag to server }
        }.toMap()
        val referenced = mutableSetOf<String>()
        dns.string("final")?.let(referenced::add)
        (dns["rules"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.string("server") }
            .forEach(referenced::add)
        (root["route"] as? JsonObject)
            ?.string("default_domain_resolver")
            ?.let(referenced::add)
        (root["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.string("domain_resolver") }
            .forEach(referenced::add)
        (root["endpoints"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.string("domain_resolver") }
            .forEach(referenced::add)
        servers.mapNotNull { it.string("domain_resolver") }
            .forEach(referenced::add)

        val queue = ArrayDeque(referenced)
        while (queue.isNotEmpty()) {
            val server = byTag[queue.removeFirst()] ?: continue
            val children = (server["servers"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            children.forEach { child ->
                if (referenced.add(child)) queue.addLast(child)
            }
        }
        val unusedCount = byTag.keys.count { it !in referenced }
        if (unusedCount == 0) {
            emptyList()
        } else {
            listOf(
                "DNS профиля: $unusedCount сервер(а) не используются final/rules и " +
                    "не являются fallback; резервными автоматически они не станут.",
            )
        }
    }.getOrDefault(emptyList())

    fun selectorGroups(raw: String): List<SelectorGroup> = selectorGroups(JsonConfig.parse(raw))

    fun selectorGroups(config: ParsedConfig): List<SelectorGroup> = selectorGroups(config.element)

    fun selectorGroups(root: JsonElement): List<SelectorGroup> {
        val outbounds = (root as? JsonObject)?.get("outbounds") as? JsonArray ?: return emptyList()
        return outbounds.mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            if (outbound.string("type") != "selector") return@mapNotNull null
            val tag = outbound.string("tag") ?: return@mapNotNull null
            val members = (outbound["outbounds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            SelectorGroup(tag, members, outbound.string("default"))
        }
    }

    fun serverOutboundTags(raw: String): List<String> = serverOutboundTags(ParsedConfig.parse(raw))

    fun serverOutboundTags(config: ParsedConfig): List<String> {
        val root = config.root ?: return emptyList()
        val outbounds = root["outbounds"] as? JsonArray ?: return emptyList()
        return outbounds.mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            val type = outbound.string("type") ?: return@mapNotNull null
            if (type in NON_SERVER_TYPES) return@mapNotNull null
            outbound.string("tag")
        }
    }

    fun outboundDescriptions(raw: String): Map<String, OutboundDescription> =
        outboundDescriptions(ParsedConfig.parse(raw))

    fun outboundDescriptions(config: ParsedConfig): Map<String, OutboundDescription> {
        val root = config.root ?: return emptyMap()
        val outbounds = root["outbounds"] as? JsonArray ?: return emptyMap()
        return outbounds.mapNotNull { element ->
            val outbound = element as? JsonObject ?: return@mapNotNull null
            val tag = outbound.string("tag") ?: return@mapNotNull null
            val type = outbound.string("type") ?: "unknown"
            val server = outbound.string("server")
            val port = (outbound["server_port"] as? JsonPrimitive)?.contentOrNull
            val endpoint = server?.let { host ->
                val printableHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
                if (port.isNullOrBlank()) printableHost else "$printableHost:$port"
            }
            tag to OutboundDescription(tag, type, server, endpoint)
        }.toMap()
    }

    fun selectServer(raw: String, selectorTag: String, serverTag: String): String =
        selectServer(ParsedConfig.parse(raw), selectorTag, serverTag)

    fun selectServer(config: ParsedConfig, selectorTag: String, serverTag: String): String {
        val root = config.root
            ?: throw IllegalArgumentException("Корень конфигурации должен быть объектом.")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("В конфигурации нет outbounds.")
        val index = outbounds.indexOfFirst { element ->
            val item = element as? JsonObject
            item?.string("type") == "selector" && item.string("tag") == selectorTag
        }
        require(index >= 0) { "Selector '$selectorTag' не найден." }
        val selector = outbounds[index] as JsonObject
        val members = (selector["outbounds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        require(serverTag in members) { "Сервер '$serverTag' не входит в selector '$selectorTag'." }

        val updated = JsonTreeEditor.replace(
            root,
            listOf(
                JsonPathSegment.Key("outbounds"),
                JsonPathSegment.Index(index),
                JsonPathSegment.Key("default"),
            ),
            JsonPrimitive(serverTag),
        )
        return JsonConfig.format(updated)
    }

    fun addManagedSelector(raw: String, serverTags: List<String>): String {
        require(serverTags.isNotEmpty()) { "Нужен хотя бы один сервер." }
        return addManagedSelector(ParsedConfig.parse(raw), serverTags)
    }

    fun addManagedSelector(config: ParsedConfig, serverTags: List<String>): String {
        require(serverTags.isNotEmpty()) { "Нужен хотя бы один сервер." }
        val root = config.root
            ?: throw IllegalArgumentException("Корень конфигурации должен быть объектом.")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("В конфигурации нет outbounds.")
        val existingTags = outbounds.mapNotNull { (it as? JsonObject)?.string("tag") }.toSet()
        require(MANAGED_SELECTOR_TAG !in existingTags) { "Selector '$MANAGED_SELECTOR_TAG' уже существует." }
        require(serverTags.all(existingTags::contains)) { "Не все серверы существуют в outbounds." }

        val selector = buildJsonObject {
            put("type", "selector")
            put("tag", MANAGED_SELECTOR_TAG)
            put("outbounds", buildJsonArray { serverTags.forEach { add(JsonPrimitive(it)) } })
            put("default", serverTags.first())
            put("interrupt_exist_connections", true)
        }
        return JsonConfig.format(
            JsonTreeEditor.replace(
                root,
                listOf(JsonPathSegment.Key("outbounds")),
                JsonArray(outbounds + selector),
            ),
        )
    }

    private val NON_SERVER_TYPES = setOf(
        "selector",
        "urltest",
        "fallback",
        "bond",
        "failover",
        "direct",
        "block",
        "dns",
    )
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
