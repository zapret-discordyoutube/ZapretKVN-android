package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.config.JsonConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignServersTest {
    private val activeId = "a".repeat(32)
    private val otherId = "b".repeat(32)
    private val thirdId = "c".repeat(32)

    private fun hysteria(tag: String, host: String, port: Int = 443, extra: String = "") =
        """{"type":"hysteria2","tag":"$tag","server":"$host","server_port":$port,"password":"x","tls":{"enabled":true,"server_name":"$host"}$extra}"""

    private fun profile(id: String, name: String, vararg servers: String, selector: Boolean = true): ProfileServers {
        val tags = servers.map { Regex(""""tag":"([^"]+)"""").find(it)!!.groupValues[1] }
        val json = """
            {"outbounds":[${servers.joinToString(",")},
              ${if (selector) """{"type":"selector","tag":"zapret-proxy","outbounds":[${tags.joinToString(",") { "\"$it\"" }}],"default":"${tags.first()}"},""" else ""}
              {"type":"direct","tag":"direct"}],
             "route":{"final":"zapret-proxy"}}
        """.trimIndent()
        return ProfileServers(id, name, JsonConfig.parse(json) as JsonObject)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `a stored reference splits at the profile id and keeps pipes in the tag`() {
        val ref = ServerRef(otherId, "Paris | fast")
        assertEquals(ref, ServerRef.parse(ref.encode()))
        assertNull("an older unqualified pin", ServerRef.parse("Paris"))
        assertNull(ServerRef.parse("short|Paris"))
        assertNull("no tag", ServerRef.parse("$otherId|"))
    }

    @Test
    fun `servers of other profiles join with a tag that cannot collide`() {
        val active = profile(activeId, "Main", hysteria("Paris", "p.example"), hysteria("Berlin", "b.example"))
        val other = profile(otherId, "Work", hysteria("Paris", "work-paris.example"), hysteria("Oslo", "o.example"))
        val joined = ForeignServers.collect(active, listOf(active, other))
        assertEquals(listOf("Paris@bbbbbbbb", "Oslo@bbbbbbbb"), joined.map { it.runtimeTag })
        assertEquals("Paris@bbbbbbbb", joined.first().outbound.str("tag"))
        assertEquals(ServerRef(otherId, "Paris"), joined.first().ref)
        assertEquals("Work", joined.first().profileName)
        assertEquals("work-paris.example", joined.first().outbound.str("server"))
    }

    @Test
    fun `a server the active profile already has joins once, as the active one`() {
        val active = profile(activeId, "Main", hysteria("Paris", "same.example"), hysteria("Berlin", "b.example"))
        val other = profile(otherId, "Work", hysteria("P", "same.example"), hysteria("Oslo", "o.example"))
        val again = profile(thirdId, "Third", hysteria("Oslo again", "o.example"))
        val joined = ForeignServers.collect(active, listOf(other, again))
        assertEquals(listOf("Oslo@bbbbbbbb"), joined.map { it.runtimeTag })
    }

    @Test
    fun `chains, unsafe transports, non-managed profiles and non-servers are left out`() {
        val chain = hysteria("Chained", "c.example", extra = ""","detour":"somewhere"""")
        val insecure = """{"type":"hysteria2","tag":"Bad","server":"i.example","server_port":443,"password":"x","tls":{"enabled":true,"insecure":true}}"""
        val plainVless = """{"type":"vless","tag":"Plain","server":"203.0.113.9","server_port":80,"uuid":"00000000-0000-4000-8000-000000000000"}"""
        val good = hysteria("Good", "g.example")
        val other = profile(otherId, "Work", chain, insecure, plainVless, good)
        val raw = profile(thirdId, "Raw", hysteria("Raw", "r.example"), selector = false)
        val active = profile(activeId, "Main", hysteria("Main", "m.example"))
        val joined = ForeignServers.collect(active, listOf(other, raw))
        assertEquals(listOf("Good@bbbbbbbb"), joined.map { it.runtimeTag })
    }

    @Test
    fun `the number of foreign servers is capped`() {
        val many = (1..100).map { hysteria("S$it", "s$it.example") }.toTypedArray()
        val other = profile(otherId, "Big", *many)
        val active = profile(activeId, "Main", hysteria("Main", "m.example"))
        assertEquals(ForeignServers.MAX_SERVERS, ForeignServers.collect(active, listOf(other)).size)
    }

    @Test
    fun `pins are translated to runtime tags and dropped when their server is gone`() {
        val active = profile(activeId, "Main", hysteria("Paris", "p.example"))
        val other = profile(otherId, "Work", hysteria("Oslo", "o.example"))
        val foreign = ForeignServers.collect(active, listOf(other))
        val pins = mapOf(
            "youtube" to ServerRef(otherId, "Oslo").encode(),
            "ai" to ServerRef(activeId, "Paris").encode(),
            "telegram" to ServerRef(thirdId, "Deleted profile").encode(),
            "old" to "Legacy tag",
        )
        assertEquals(
            mapOf("youtube" to "Oslo@bbbbbbbb", "ai" to "Paris", "old" to "Legacy tag"),
            ForeignServers.runtimePins(pins, activeId, foreign),
        )
        assertEquals(ServerRef(otherId, "Oslo"), ForeignServers.refOf("Oslo@bbbbbbbb", activeId, foreign))
        assertEquals(ServerRef(activeId, "Paris"), ForeignServers.refOf("Paris", activeId, foreign))
    }
}
