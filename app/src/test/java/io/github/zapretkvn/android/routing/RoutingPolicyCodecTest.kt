package io.github.zapretkvn.android.routing

import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.config.string
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutingPolicyCodecTest {
    private val installed = InstalledRuleSets(
        version = 1,
        paths = mapOf(
            "zapret-ru-domains" to "/data/local/tmp/zapret-ru-domains.srs",
            "zapret-ru-ip" to "/data/local/tmp/zapret-ru-ip.srs",
        ),
    )

    @Test
    fun `global policy round trips without profile data`() {
        val policy = GlobalRoutingPolicy(
            preset = RoutingPreset.OnlySelectedSites,
            rules = listOf(
                ManagedRoutingRule(
                    matchType = RoutingMatchType.DomainSuffix,
                    values = listOf("example.org", "example.net"),
                    action = RoutingRuleAction.Proxy,
                ),
                ManagedRoutingRule(
                    matchType = RoutingMatchType.IpCidr,
                    values = listOf("192.0.2.0/24"),
                    action = RoutingRuleAction.Block,
                ),
            ),
        )

        assertEquals(policy, RoutingPolicyCodec.decode(RoutingPolicyCodec.encode(policy)))
    }

    @Test(expected = IllegalStateException::class)
    fun `policy rejects empty rule values`() {
        RoutingPolicyCodec.decode(
            """
            {
              "version": 1,
              "preset": "AllThroughVpn",
              "rules": [
                {
                  "match_type": "Domain",
                  "values": [],
                  "action": "Proxy"
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `same policy resolves the selected proxy independently for every profile`() {
        val first = profile()
        val second = first.replace("zapret-proxy", "second-profile-proxy")
        val policy = GlobalRoutingPolicy(
            preset = RoutingPreset.AllThroughVpn,
            rules = listOf(
                ManagedRoutingRule(
                    matchType = RoutingMatchType.Domain,
                    values = listOf("example.org"),
                    action = RoutingRuleAction.Proxy,
                ),
            ),
        )

        val firstEffective = apply(first, policy)
        val secondEffective = apply(second, policy)

        assertEquals("zapret-proxy", route(firstEffective).string("final"))
        assertEquals("second-profile-proxy", route(secondEffective).string("final"))
        assertEquals("zapret-proxy", managedRule(firstEffective).string("outbound"))
        assertEquals("second-profile-proxy", managedRule(secondEffective).string("outbound"))
        assertFalse(first.contains("example.org"))
        assertFalse(second.contains("example.org"))
    }

    private fun apply(raw: String, policy: GlobalRoutingPolicy): String =
        RoutingConfigEditor.apply(raw, policy.preset, policy.rules, installed).json

    private fun profile(): String = ManagedProfileFactory.single(
        ManagedServer(
            displayName = "Server",
            identityKey = "server|one",
            outbound = JsonObject(mapOf("type" to JsonPrimitive("direct"))),
        ),
    )

    private fun route(raw: String): JsonObject =
        (JsonConfig.parse(raw) as JsonObject)["route"] as JsonObject

    private fun managedRule(raw: String): JsonObject =
        (route(raw)["rules"] as JsonArray)
            .map { it as JsonObject }
            .first { rule ->
                (rule["rule_set"] as? JsonArray)
                    ?.any {
                        it is JsonPrimitive && it.content.startsWith("zapret-user-domain-")
                    } == true
            }
}
