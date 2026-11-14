package io.github.zapretkvn.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.nekohasekai.libbox.Libbox
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeConfigNativeInstrumentedTest {
    @Test
    fun transformedRuntimeConfigPassesPinnedLibbox() {
        val raw = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a"],"default":"server-a"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
        DnsMode.entries.forEach { mode ->
            listOf(null, "io.github.zapretkvn.android").forEach { updaterPackage ->
                listOf(emptySet(), setOf("com.example.blocked")).forEach { blockedPackages ->
                    val runtime = RuntimeConfigBuilder.build(
                        raw,
                        options = RuntimeConfigOptions(
                            dnsMode = mode,
                            healthCheckPackageName = "io.github.zapretkvn.android",
                            updaterPackageName = updaterPackage,
                            blockedPackages = blockedPackages,
                        ),
                    ) as RuntimeConfigResult.Ready
                    Libbox.checkConfig(runtime.json)
                }
            }
        }
    }

    /** The slot selectors, slot route rules, slot DNS servers (parallel race through the slot selector) and DNS rules with `disable_cache` must be accepted by the pinned core. */
    @Test
    fun slotConfigPassesPinnedLibbox() {
        val raw = """
            {
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true}],
              "outbounds":[
                {"type":"direct","tag":"server-a"},
                {"type":"direct","tag":"server-b"},
                {"type":"direct","tag":"server-c"},
                {"type":"selector","tag":"zapret-proxy","outbounds":["server-a","server-b","server-c"],"default":"server-a"},
                {"type":"direct","tag":"direct"}
              ],
              "route":{"auto_detect_interface":true,"final":"zapret-proxy"}
            }
        """.trimIndent()
        val slots = listOf(
            io.github.zapretkvn.android.config.SlotRouteSpec(
                slotId = "youtube",
                members = listOf("server-b", "server-c"),
                defaultMember = "server-b",
                packages = listOf("app.morphe.android.youtube"),
                domainSuffixes = listOf("googlevideo.com", "youtube.com"),
            ),
            io.github.zapretkvn.android.config.SlotRouteSpec(
                slotId = "telegram",
                members = listOf("server-a", "server-c"),
                defaultMember = "server-c",
                packages = listOf("org.telegram.messenger"),
                domainSuffixes = listOf("t.me"),
                cidrs = listOf("149.154.160.0/20"),
            ),
        )
        listOf(DnsMode.Automatic, DnsMode.Secure, DnsMode.Android).forEach { mode ->
            listOf(true, false).forEach { ipv4Only ->
                val runtime = RuntimeConfigBuilder.build(
                    raw,
                    options = RuntimeConfigOptions(
                        dnsMode = mode,
                        proxyIpv4Only = ipv4Only,
                        healthCheckPackageName = "io.github.zapretkvn.android",
                        slotRoutes = slots,
                    ),
                ) as RuntimeConfigResult.Ready
                Libbox.checkConfig(runtime.json)
            }
        }
    }
}
