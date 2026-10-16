package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ManagedProfileUpdate(
    val json: String,
    val selectedTag: String,
    val selectionChanged: Boolean,
)

object ManagedProfileEditor {
    fun appendServer(rawJson: String, server: ManagedServer): ManagedProfileUpdate {
        val root = JsonConfig.parse(rawJson) as? JsonObject
            ?: throw IllegalArgumentException("Профиль должен быть JSON-объектом.")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("В профиле отсутствует outbounds.")
        val selectorIndex = outbounds.indexOfFirst { element ->
            val outbound = element as? JsonObject
            outbound?.string("type") == "selector" &&
                outbound.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG
        }
        if (selectorIndex < 0) {
            throw IllegalArgumentException("Добавление доступно только для managed-группы zapret-proxy.")
        }
        val selector = outbounds[selectorIndex] as JsonObject
        val members = selector.stringArray("outbounds").toMutableList()
        val usedTags = outbounds.mapNotNull { (it as? JsonObject)?.string("tag") }.toSet()
        val baseTag = ManagedProfileFactory.taggedServers(listOf(server)).single().tag
        var tag = baseTag
        var suffix = 2
        while (tag in usedTags) tag = "$baseTag · ${suffix++}"
        val tagged = JsonObject(
            ManagedProfileFactory.taggedServers(listOf(server)).single().outbound
                .toMutableMap()
                .apply { this["tag"] = JsonPrimitive(tag) },
        )
        members += tag
        val updatedSelector = selector.withValues(
            mapOf("outbounds" to JsonArray(members.map(::JsonPrimitive))),
        )
        val updatedOutbounds = outbounds.toMutableList().apply {
            add(selectorIndex, tagged)
            this[selectorIndex + 1] = updatedSelector
        }
        return ManagedProfileUpdate(
            json = JsonConfig.format(root.withValues(mapOf("outbounds" to JsonArray(updatedOutbounds)))),
            selectedTag = selector.string("default") ?: members.first(),
            selectionChanged = false,
        )
    }

    fun refreshServers(rawJson: String, servers: List<ManagedServer>): ManagedProfileUpdate {
        require(servers.isNotEmpty()) { "Подписка не содержит серверов." }
        val root = JsonConfig.parse(rawJson) as? JsonObject
            ?: throw IllegalArgumentException("Профиль должен быть JSON-объектом.")
        val outbounds = root["outbounds"] as? JsonArray
            ?: throw IllegalArgumentException("В профиле отсутствует outbounds.")
        val selector = outbounds.mapNotNull { it as? JsonObject }.firstOrNull {
            it.string("type") == "selector" && it.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG
        } ?: throw IllegalArgumentException("Профиль не содержит managed-группу zapret-proxy.")
        val oldMembers = selector.stringArray("outbounds").toSet()
        val oldSelected = selector.string("default")
        val fresh = ManagedProfileFactory.taggedServers(servers)
        val freshTags = fresh.map(ManagedProfileFactory.TaggedServer::tag)
        val oldSelectedOutbound = oldSelected?.let { tag ->
            outbounds.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { outbound -> outbound.string("tag") == tag } }
        }
        // Тот же сервер под новым тегом — это не смена выбора: VPN не нужно предупреждать.
        val carried = oldSelected?.let { carrySelection(it, oldSelectedOutbound, servers, fresh) }
        val selected = carried ?: freshTags.first()
        val updatedSelector = selector.withValues(
            mapOf(
                "outbounds" to JsonArray(freshTags.map(::JsonPrimitive)),
                "default" to JsonPrimitive(selected),
            ),
        )

        val rebuilt = mutableListOf<JsonElement>()
        var inserted = false
        outbounds.forEach { element ->
            val outbound = element as? JsonObject
            val tag = outbound?.string("tag")
            when {
                outbound?.string("type") == "selector" &&
                    outbound.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG -> {
                    fresh.forEach { rebuilt += it.outbound }
                    rebuilt += updatedSelector
                    inserted = true
                }
                tag != null && tag in oldMembers -> Unit
                else -> rebuilt += element
            }
        }
        check(inserted) { "Не удалось обновить managed selector." }
        return ManagedProfileUpdate(
            json = JsonConfig.format(root.withValues(mapOf("outbounds" to JsonArray(rebuilt)))),
            selectedTag = selected,
            selectionChanged = oldSelected != null && carried == null,
        )
    }

    /**
     * Прежний выбор в обновлённом списке: тот же тег; иначе тот же сервер под тегом до 0.5.15
     * (слаг); иначе тот же адрес и протокол — подписка переименовала сервер. Ничего не нашлось — null.
     */
    private fun carrySelection(
        oldSelected: String,
        oldOutbound: JsonObject?,
        servers: List<ManagedServer>,
        fresh: List<ManagedProfileFactory.TaggedServer>,
    ): String? {
        fresh.firstOrNull { it.tag == oldSelected }?.let { return it.tag }
        val legacy = ManagedProfileFactory.legacyStableTags(servers).indexOf(oldSelected)
        if (legacy >= 0) return fresh[legacy].tag
        val address = oldOutbound?.serverAddress() ?: return null
        return fresh.singleOrNull { it.outbound.serverAddress() == address }?.tag
    }

    private fun JsonObject.serverAddress(): String? {
        val server = string("server")?.takeIf(String::isNotBlank) ?: return null
        return "${string("type")}|${server.lowercase()}|${string("server_port")}"
    }

    fun preserveSelectorDefaults(oldJson: String, newJson: String): ManagedProfileUpdate {
        val oldRoot = JsonConfig.parse(oldJson) as? JsonObject
            ?: throw IllegalArgumentException("Старый профиль должен быть JSON-объектом.")
        val newRoot = JsonConfig.parse(newJson) as? JsonObject
            ?: throw IllegalArgumentException("Новый профиль должен быть JSON-объектом.")
        val oldDefaults = oldRoot.outbounds().mapNotNull { outbound ->
            if (outbound.string("type") != "selector") return@mapNotNull null
            val tag = outbound.string("tag") ?: return@mapNotNull null
            val selected = outbound.string("default") ?: return@mapNotNull null
            tag to selected
        }.toMap()
        var firstSelected = ""
        var changed = false
        val matchedOldSelectors = mutableSetOf<String>()
        val updatedOutbounds = newRoot.outbounds().map { outbound ->
            if (outbound.string("type") != "selector") return@map outbound
            val choices = outbound.stringArray("outbounds")
            val current = outbound.string("default") ?: choices.firstOrNull().orEmpty()
            val selectorTag = outbound.string("tag")
            val preferred = selectorTag?.let(oldDefaults::get)
            if (preferred != null) matchedOldSelectors += checkNotNull(selectorTag)
            val selected = preferred?.takeIf(choices::contains) ?: current
            if (selected.isEmpty()) return@map outbound
            if (firstSelected.isEmpty()) firstSelected = selected
            if (preferred != null && preferred != selected) changed = true
            outbound.withValues(mapOf("default" to JsonPrimitive(selected)))
        }
        if (oldDefaults.keys.any { it !in matchedOldSelectors }) changed = true
        return ManagedProfileUpdate(
            json = JsonConfig.format(newRoot.withValues(mapOf("outbounds" to JsonArray(updatedOutbounds)))),
            selectedTag = firstSelected,
            selectionChanged = changed,
        )
    }

    fun isManaged(rawJson: String): Boolean = runCatching {
        val root = JsonConfig.parse(rawJson) as JsonObject
        root.outbounds().any {
            it.string("type") == "selector" && it.string("tag") == ConfigAnalyzer.MANAGED_SELECTOR_TAG
        }
    }.getOrDefault(false)

    private fun JsonObject.outbounds(): List<JsonObject> =
        (this["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }

    private fun JsonObject.withValues(values: Map<String, JsonElement>): JsonObject =
        JsonObject(toMutableMap().apply { putAll(values) })
}
