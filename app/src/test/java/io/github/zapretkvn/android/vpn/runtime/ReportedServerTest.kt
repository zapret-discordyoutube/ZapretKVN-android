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
    fun severalServersWithoutAGroupSayNothing() {
        val json = """{"outbounds":[
            {"type":"vless","tag":"a","server":"a.example","server_port":443},
            {"type":"trojan","tag":"b","server":"b.example","server_port":443},
            {"type":"direct","tag":"direct"}]}"""
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
