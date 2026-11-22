package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.ParsedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which server a connection report is about (docs/CLIENT_REPORTS.md), field check 2026-11-21. */
class ReportedServerTest {
    private fun server(json: String) = FailoverPlanner.reportedServer(ParsedConfig.parse(json), emptyMap(), null)

    @Test
    fun aGroupWithAPlanReportsItsCurrentServer() {
        val json = """{"outbounds":[
            {"type":"selector","tag":"proxy","outbounds":["a","b"],"default":"b"},
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443},
            {"type":"direct","tag":"direct"}]}"""
        assertEquals("b" to "vless", server(json))
    }

    @Test
    fun aProfileWithOneServerAndNoGroupReportsThatServer() {
        val json = """{"outbounds":[
            {"type":"vless","tag":"only","server":"a.example","server_port":443},
            {"type":"direct","tag":"direct"},{"type":"block","tag":"block"},{"type":"dns","tag":"dns-out"}]}"""
        assertEquals("only" to "vless", server(json))
    }

    @Test
    fun severalServersWithoutAGroupNameTheDefaultExit() {
        // sing-box sends default traffic to route.final, or to the first outbound when it is not set.
        val outbounds = """[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"trojan","tag":"b","server":"b.example","server_port":443},
            {"type":"direct","tag":"direct"}]"""
        assertEquals("a" to "vless", server("""{"outbounds":$outbounds}"""))
        assertEquals("b" to "trojan", server("""{"outbounds":$outbounds,"route":{"final":"b"}}"""))
    }

    @Test
    fun aSelectorWithoutDefaultIsOnItsFirstMember() {
        val json = """{"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"selector","tag":"proxy","outbounds":["b","a"]},
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443}],
            "route":{"final":"proxy"}}"""
        assertEquals("b" to "vless", server(json))
    }

    @Test
    fun nestedGroupsAreFollowedDownToAServer() {
        // The usual panel subscription: proxy → auto (urltest) → servers.
        val json = """{"outbounds":[
            {"type":"selector","tag":"proxy","outbounds":["auto","a","b"],"default":"auto"},
            {"type":"urltest","tag":"auto","outbounds":["b","a"]},
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"vless","tag":"b","server":"b.example","server_port":443},
            {"type":"direct","tag":"direct"}]}"""
        assertEquals("b" to "vless", server(json))
        val picked = FailoverPlanner.reportedServer(ParsedConfig.parse(json), mapOf("proxy" to "a"), null)
        assertEquals("a" to "vless", picked)
    }

    @Test
    fun aWireGuardEndpointIsAServer() {
        val json = """{"outbounds":[{"type":"direct","tag":"direct"}],
            "endpoints":[{"type":"wireguard","tag":"wg","peers":[{"address":"w.example","port":51820}]}],
            "route":{"final":"wg"}}"""
        assertEquals("wg" to "wireguard", server(json))
        val alone = """{"outbounds":[{"type":"direct","tag":"direct"}],
            "endpoints":[{"type":"wireguard","tag":"wg","peers":[{"address":"w.example","port":51820}]}],
            "route":{"final":"direct","rules":[{"action":"route","outbound":"wg","ip_cidr":["0.0.0.0/0"]}]}}"""
        assertEquals("wg" to "wireguard", server(alone))
    }

    @Test
    fun aLoopOfGroupsSaysNothing() {
        val json = """{"outbounds":[
            {"type":"selector","tag":"x","outbounds":["y"]},
            {"type":"selector","tag":"y","outbounds":["x"]}]}"""
        assertNull(server(json))
    }

    @Test
    fun aGroupOnAHysteriaLineWithoutFailoverStillNamesIt() {
        // No valid native URI: no failover plan, yet this is the server the start tried.
        val json = """{"outbounds":[
            {"type":"selector","tag":"proxy","outbounds":["h","a"],"default":"h"},
            {"type":"hysteria2","tag":"h","server":"h.example","server_port":443},
            {"type":"vless","tag":"a","server":"a.example","server_port":443}]}"""
        assertEquals("h" to "hysteria2", server(json))
    }

    @Test
    fun aGroupOnDirectSaysNothing() {
        val json = """{"outbounds":[
            {"type":"selector","tag":"proxy","outbounds":["direct","a"],"default":"direct"},
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"direct","tag":"direct"}]}"""
        assertNull(server(json))
    }
}
