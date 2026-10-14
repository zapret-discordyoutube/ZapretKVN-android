package io.github.zapretkvn.android.network.probes.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRouteHintTest {
    private val youtube = ServiceCatalog.definition(ServiceId.YouTube)!!

    private fun profile(rules: String, final: String = "zapret-proxy", extra: String = "") = """
        {"outbounds":[
          {"type":"vless","tag":"node","server":"s.example","server_port":443},
          {"type":"selector","tag":"zapret-proxy","outbounds":["node"],"default":"node"},
          {"type":"direct","tag":"direct"}],
         "route":{$extra "rules":[$rules],"final":"$final"}}
    """.trimIndent()

    @Test
    fun finalOutboundDecidesWhenNoRuleMatches() {
        assertEquals(RouteHint.Proxy, ServiceRouteHint.of(profile(""), youtube))
        assertEquals(RouteHint.Direct, ServiceRouteHint.of(profile("", final = "direct"), youtube))
    }

    @Test
    fun exactDomainAndSuffixRulesWinOverFinal() {
        val suffix = """{"domain_suffix":["youtube.com","googlevideo.com"],"action":"route","outbound":"direct"}"""
        assertEquals(RouteHint.Direct, ServiceRouteHint.of(profile(suffix), youtube))
        val other = """{"domain_suffix":["example.org"],"action":"route","outbound":"direct"}"""
        assertEquals(RouteHint.Proxy, ServiceRouteHint.of(profile(other), youtube))
    }

    @Test
    fun partialCoverageOfTheServiceHostsIsUnknown() {
        val onlyYoutube = """{"domain":["www.youtube.com"],"action":"route","outbound":"direct"}"""
        assertEquals(RouteHint.Unknown, ServiceRouteHint.of(profile(onlyYoutube), youtube))
    }

    @Test
    fun ruleSetFromFileBeforeTheMatchMakesItUnknown() {
        val srs = """{"rule_set":["ru-domains","ru-ip"],"action":"route","outbound":"direct"}"""
        val tail = """{"domain_suffix":["youtube.com"],"action":"route","outbound":"direct"}"""
        val extra = """"rule_set":[{"tag":"ru-domains","type":"local","path":"/x.srs"}],"""
        assertEquals(RouteHint.Unknown, ServiceRouteHint.of(profile("$srs,$tail", extra = extra), youtube))
    }

    @Test
    fun serviceRulesAndPrivateRulesAreSkipped() {
        val rules = """
            {"action":"sniff"},
            {"protocol":"dns","action":"hijack-dns"},
            {"ip_is_private":true,"action":"route","outbound":"direct"}
        """.trimIndent()
        assertEquals(RouteHint.Proxy, ServiceRouteHint.of(profile(rules), youtube))
    }

    @Test
    fun inlineRuleSetWithDomainsIsUnderstood() {
        val extra = """"rule_set":[{"tag":"mine","type":"inline","rules":[{"domain_suffix":["youtube.com","googlevideo.com"]}]}],"""
        val rule = """{"rule_set":["mine"],"action":"route","outbound":"direct"}"""
        assertEquals(RouteHint.Direct, ServiceRouteHint.of(profile(rule, extra = extra), youtube))
    }

    @Test
    fun brokenOrMissingProfileIsUnknown() {
        assertEquals(RouteHint.Unknown, ServiceRouteHint.of(null, youtube))
        assertEquals(RouteHint.Unknown, ServiceRouteHint.of("not json", youtube))
        assertEquals(RouteHint.Unknown, ServiceRouteHint.of("{}", youtube))
    }
}
