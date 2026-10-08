package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapConfigTest {
    @Test
    fun `selector resolves to authenticated active server without exposing credentials`() {
        val target = BootstrapConfig.target(
            """
                {
                  "outbounds":[
                    {"type":"vless","tag":"a","server":"vpn.example","server_port":443,"uuid":"secret","tls":{"enabled":true,"server_name":"vpn.example"}},
                    {"type":"selector","tag":"zapret-proxy","outbounds":["a"],"default":"a"}
                  ],
                  "route":{"final":"zapret-proxy"}
                }
            """.trimIndent(),
        )!!

        assertEquals("a", target.outboundTag)
        assertEquals("vpn.example", target.hostname)
        assertEquals(443, target.port)
        assertTrue(target.tcpPreflightSupported)
        assertTrue(target.staleAddressAllowed)
        assertFalse(target.toString().contains("secret"))
    }

    @Test
    fun `plaintext server cannot use stale addresses and literal skips DNS but keeps socket preflight`() {
        val plaintext = BootstrapConfig.target(
            """{"outbounds":[{"type":"socks","tag":"p","server":"proxy.example","server_port":1080}],"route":{"final":"p"}}""",
        )!!
        val literal = BootstrapConfig.target(
            """{"outbounds":[{"type":"vless","tag":"p","server":"203.0.113.7","server_port":443}],"route":{"final":"p"}}""",
        )

        assertFalse(plaintext.staleAddressAllowed)
        assertFalse(literal!!.requiresDns)
        assertTrue(literal.tcpPreflightSupported)
    }

    @Test
    fun `native wireguard endpoint is selected from a full tunnel route`() {
        val raw = """
            {
              "endpoints":[{"type":"wireguard","tag":"wg","address":["10.0.0.2/32"],"private_key":"redacted","peers":[{"address":"wg.example","port":51820,"public_key":"redacted","allowed_ips":["0.0.0.0/0"]}]}],
              "outbounds":[{"type":"direct","tag":"direct"}],
              "route":{"rules":[{"ip_cidr":["0.0.0.0/0"],"action":"route","outbound":"wg"}],"final":"direct"}
            }
        """.trimIndent()

        assertEquals("wg", BootstrapConfig.selectedProxyTag(raw))
        val target = BootstrapConfig.target(raw)!!
        assertEquals("wireguard", target.outboundType)
        assertEquals("wg.example", target.hostname)
        assertEquals(51820, target.port)
        assertFalse(target.tcpPreflightSupported)
        assertFalse(target.staleAddressAllowed)
        assertEquals(ProxyIpFamily.Ipv4Only, BootstrapConfig.selectedProxyIpFamily(raw))
    }

    @Test
    fun `wireguard family follows its local tunnel addresses without rewriting profile`() {
        val ipv4 = wireGuardProfile("""["10.0.0.2/32"]""")
        val ipv6 = wireGuardProfile("""["fd00::2/128"]""")
        val dual = wireGuardProfile("""["10.0.0.2/32","fd00::2/128"]""")

        assertEquals(ProxyIpFamily.Ipv4Only, BootstrapConfig.selectedProxyIpFamily(ipv4))
        assertEquals(ProxyIpFamily.Ipv6Only, BootstrapConfig.selectedProxyIpFamily(ipv6))
        assertEquals(ProxyIpFamily.DualStack, BootstrapConfig.selectedProxyIpFamily(dual))
        assertEquals(
            ProxyIpFamily.Unspecified,
            BootstrapConfig.selectedProxyIpFamily(
                """{"outbounds":[{"type":"vless","tag":"p","server":"vpn.example","server_port":443}],"route":{"final":"p"}}""",
            ),
        )
    }

    @Test
    fun `server hostnames cover every group member and skip literals`() {
        val names = BootstrapConfig.serverHostnames(
            """
            {
              "outbounds":[
                {"type":"selector","tag":"proxy","outbounds":["a","b","c"],"default":"a"},
                {"type":"trojan","tag":"a","server":"One.Example.com.","server_port":443},
                {"type":"vless","tag":"b","server":"two.example.net","server_port":443},
                {"type":"hysteria2","tag":"c","server":"203.0.113.7","server_port":443},
                {"type":"direct","tag":"direct"}
              ],
              "endpoints":[
                {"type":"wireguard","tag":"wg","peers":[{"address":"wg.example.org","port":51820},{"address":"2001:db8::1","port":51820}]}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(setOf("one.example.com", "two.example.net", "wg.example.org"), names)
        assertEquals(emptySet<String>(), BootstrapConfig.serverHostnames("not json"))
    }

    private fun wireGuardProfile(addresses: String): String = """
        {
          "endpoints":[{
            "type":"wireguard",
            "tag":"wg",
            "address":$addresses,
            "peers":[{
              "address":"192.0.2.1",
              "port":51820,
              "allowed_ips":["0.0.0.0/0","::/0"]
            }]
          }],
          "outbounds":[{"type":"direct","tag":"direct"}],
          "route":{"rules":[{"ip_cidr":["0.0.0.0/0"],"action":"route","outbound":"wg"}],"final":"direct"}
        }
    """.trimIndent()
}
