package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.network.probes.VpnDnsHealthException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomaticDnsFallbackPolicyTest {
    @Test
    fun `automatic uses profile then Android then secure DNS`() {
        assertEquals(
            listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure),
            AutomaticDnsFallbackPolicy.candidates(DnsMode.Automatic, hasProfileDns = true),
        )
        assertEquals(
            listOf(DnsMode.Android, DnsMode.Secure),
            AutomaticDnsFallbackPolicy.candidates(DnsMode.Automatic, hasProfileDns = false),
        )
    }

    @Test
    fun `explicit modes never receive hidden fallback`() {
        listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure).forEach { mode ->
            assertEquals(
                listOf(mode),
                AutomaticDnsFallbackPolicy.candidates(mode, hasProfileDns = true),
            )
        }
    }

    @Test
    fun `strict private dns narrows automatic to android candidate`() {
        assertEquals(
            listOf(DnsMode.Android),
            AutomaticDnsFallbackPolicy.candidates(
                DnsMode.Automatic,
                hasProfileDns = true,
                strictPrivateDns = true,
            ),
        )
    }

    @Test
    fun `strict private dns does not change explicit modes`() {
        listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure).forEach { mode ->
            assertEquals(
                listOf(mode),
                AutomaticDnsFallbackPolicy.candidates(
                    mode,
                    hasProfileDns = true,
                    strictPrivateDns = true,
                ),
            )
        }
    }

    @Test
    fun `runner advances only after DNS failure and remains bounded`() = runBlocking {
        val attempts = mutableListOf<DnsMode>()
        val transitions = mutableListOf<Pair<DnsMode, DnsMode>>()
        val result = AutomaticDnsFallbackPolicy.run(
            candidates = listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure),
            onFallback = { from, to, _ -> transitions += from to to },
            attempt = { mode ->
                attempts += mode
                if (mode != DnsMode.Secure) throw VpnDnsHealthException("dns failed")
                "connected"
            },
        )

        assertEquals("connected", result)
        assertEquals(listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure), attempts)
        assertEquals(
            listOf(DnsMode.FromJson to DnsMode.Android, DnsMode.Android to DnsMode.Secure),
            transitions,
        )
    }

    @Test
    fun `runner does not hide non DNS failures`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AutomaticDnsFallbackPolicy.run(
                    candidates = listOf(DnsMode.FromJson, DnsMode.Android, DnsMode.Secure),
                    onFallback = { _, _, _ -> error("unexpected fallback") },
                    attempt = { throw IllegalStateException("https failed") },
                )
            }
        }
        assertEquals("https failed", error.message)
    }
}
