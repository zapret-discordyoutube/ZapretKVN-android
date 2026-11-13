package io.github.zapretkvn.android.config

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcedProxyDomainsTest {
    @Test
    fun `the rule routes the listed suffixes to the given proxy and nothing else`() {
        val rule = ForcedProxyDomains.rule("zapret-proxy")
        assertEquals("route", (rule["action"] as JsonPrimitive).content)
        assertEquals("zapret-proxy", (rule["outbound"] as JsonPrimitive).content)
        assertEquals(
            ForcedProxyDomains.suffixes,
            (rule["domain_suffix"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(setOf("domain_suffix", "action", "outbound"), rule.keys)
    }

    @Test
    fun `YouTube cache hosts are covered with or without a trailing dot and in any case`() {
        assertTrue(ForcedProxyDomains.covers("rr2---sn-8ph2xajvh-3a4l.googlevideo.com"))
        assertTrue(ForcedProxyDomains.covers("rr1.sn-5hne6nsy.googlevideo.com."))
        assertTrue(ForcedProxyDomains.covers("GoogleVideo.com"))
        assertTrue(ForcedProxyDomains.covers("www.youtube.com"))
        assertTrue(ForcedProxyDomains.covers("youtubei.googleapis.com"))
    }

    @Test
    fun `lookalike and unrelated names are not covered`() {
        assertFalse(ForcedProxyDomains.covers("notgooglevideo.com"))
        assertFalse(ForcedProxyDomains.covers("googlevideo.com.example.ru"))
        assertFalse(ForcedProxyDomains.covers("www.googleapis.com"))
        assertFalse(ForcedProxyDomains.covers("yandex.ru"))
    }

    @Test
    fun `our own rule is recognised and a user rule with other names or actions is not`() {
        assertTrue(ForcedProxyDomains.isForcedRule(ForcedProxyDomains.rule("any")))
        val other = buildJsonObject {
            put("domain_suffix", JsonArray(listOf(JsonPrimitive("googlevideo.com"))))
            put("action", "route")
            put("outbound", "direct")
        }
        assertFalse("a user rule with a shorter list is theirs", ForcedProxyDomains.isForcedRule(other))
        val reject = JsonObject(ForcedProxyDomains.rule("any") + ("action" to JsonPrimitive("reject")))
        assertFalse(ForcedProxyDomains.isForcedRule(reject))
    }

    @Test
    fun `the Kotlin list is the shared list that Windows ships too`() {
        // android/shared/always-proxy-domains.json is the canonical file; windows/data/routing keeps a byte-identical copy
        // (scripts/check-shared-routing-list.sh compares them at release time).
        val file = listOf(File("../shared/always-proxy-domains.json"), File("shared/always-proxy-domains.json"))
            .firstOrNull(File::isFile)
        assertTrue("shared list file not found", file != null)
        val doc = Json.parseToJsonElement(file!!.readText(Charsets.UTF_8)) as JsonObject
        assertEquals(1, (doc["schema_version"] as JsonPrimitive).content.toInt())
        val shared = (doc["suffixes"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals("edit shared/always-proxy-domains.json, ForcedProxyDomains.suffixes and the Windows copy together", shared, ForcedProxyDomains.suffixes)
        assertEquals("no duplicates in the shared list", shared.size, shared.toSet().size)
    }
}
