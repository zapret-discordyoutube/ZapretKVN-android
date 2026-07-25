package io.github.zapretkvn.android.routing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class GlobalRoutingPolicy(
    val preset: RoutingPreset,
    val rules: List<ManagedRoutingRule>,
)

private val Context.routingPolicyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "routing_policy",
)

class RoutingPolicyStore(context: Context) {
    private val dataStore = context.applicationContext.routingPolicyDataStore

    val policy: Flow<GlobalRoutingPolicy?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[POLICY_JSON]?.let(RoutingPolicyCodec::decode)
        }
        .distinctUntilChanged()

    suspend fun set(policy: GlobalRoutingPolicy) {
        dataStore.edit { preferences ->
            preferences[POLICY_JSON] = RoutingPolicyCodec.encode(policy)
        }
    }

    suspend fun getOrInitialize(defaultPolicy: GlobalRoutingPolicy): GlobalRoutingPolicy {
        var resolved = defaultPolicy
        dataStore.edit { preferences ->
            val stored = preferences[POLICY_JSON]
            if (stored == null) {
                preferences[POLICY_JSON] = RoutingPolicyCodec.encode(defaultPolicy)
            } else {
                resolved = RoutingPolicyCodec.decode(stored)
            }
        }
        return resolved
    }

    private companion object {
        val POLICY_JSON = stringPreferencesKey("policy_json")
    }
}

internal object RoutingPolicyCodec {
    fun encode(policy: GlobalRoutingPolicy): String = JsonConfig.format(
        buildJsonObject {
            put("version", 1)
            put("preset", policy.preset.name)
            put(
                "rules",
                JsonArray(policy.rules.map { rule ->
                    buildJsonObject {
                        put("match_type", rule.matchType.name)
                        put("values", JsonArray(rule.values.map(::JsonPrimitive)))
                        put("action", rule.action.name)
                        rule.outboundTag?.let { put("outbound_tag", it) }
                    }
                }),
            )
        },
    )

    fun decode(raw: String): GlobalRoutingPolicy {
        val root = JsonConfig.parse(raw) as? JsonObject
            ?: error("Некорректная общая политика маршрутизации.")
        require((root["version"] as? JsonPrimitive)?.content == "1") {
            "Неподдерживаемая версия общей политики маршрутизации."
        }
        val preset = root.string("preset")
            ?.let { stored -> RoutingPreset.entries.firstOrNull { it.name == stored } }
            ?: error("Некорректный режим общей маршрутизации.")
        val rules = (root["rules"] as? JsonArray)
            ?.map { element ->
                val rule = element as? JsonObject
                    ?: error("Некорректное общее правило маршрутизации.")
                val matchType = rule.string("match_type")
                    ?.let { stored -> RoutingMatchType.entries.firstOrNull { it.name == stored } }
                    ?: error("Некорректный тип общего правила маршрутизации.")
                val action = rule.string("action")
                    ?.let { stored -> RoutingRuleAction.entries.firstOrNull { it.name == stored } }
                    ?: error("Некорректное действие общего правила маршрутизации.")
                val values = (rule["values"] as? JsonArray)
                    ?.map { value ->
                        (value as? JsonPrimitive)?.content
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: error("Некорректное значение общего правила маршрутизации.")
                    }
                    ?.takeIf(List<String>::isNotEmpty)
                    ?: error("Общее правило маршрутизации не содержит значений.")
                ManagedRoutingRule(
                    matchType = matchType,
                    values = values,
                    action = action,
                    outboundTag = rule.string("outbound_tag")?.trim()?.takeIf(String::isNotEmpty),
                )
            }
            ?: error("Общая политика маршрутизации не содержит списка правил.")
        return GlobalRoutingPolicy(preset, rules)
    }
}
