package io.github.zapretkvn.networkbootstrap

import io.github.zapretkvn.networkbootstrap.DecoyDnsCheck.Verdict
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class DecoyDnsCheckTest {
    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
    private val trusted = TrustedDnsOutcome.Addresses(listOf(ip("104.21.1.1"), ip("172.67.1.1")))

    @Test
    fun `provider denying the decoy name is reported`() {
        val nx = Result.failure<List<InetAddress>>(BootstrapFailureException(BootstrapFailureCode.DnsNameNotFound))
        assertEquals(Verdict.Denied, DecoyDnsCheck.classify(trusted, nx))
        assertEquals(Verdict.Denied, DecoyDnsCheck.classify(trusted, Result.success(emptyList())))
    }

    @Test
    fun `stub addresses are bogus`() {
        for (stub in listOf("10.10.34.34", "127.0.0.1", "0.0.0.0", "192.168.1.1")) {
            assertEquals(stub, Verdict.Bogus, DecoyDnsCheck.classify(trusted, Result.success(listOf(ip(stub)))))
        }
    }

    @Test
    fun `overlap is clean and disjoint public answer only differs`() {
        assertEquals(Verdict.Clean, DecoyDnsCheck.classify(trusted, Result.success(listOf(ip("172.67.1.1")))))
        assertEquals(Verdict.Differs, DecoyDnsCheck.classify(trusted, Result.success(listOf(ip("8.8.4.4")))))
    }

    @Test
    fun `without trusted answer nothing is claimed`() {
        val nx = Result.failure<List<InetAddress>>(BootstrapFailureException(BootstrapFailureCode.DnsNameNotFound))
        assertEquals(Verdict.Unknown, DecoyDnsCheck.classify(TrustedDnsOutcome.Unavailable, nx))
        val timeout = Result.failure<List<InetAddress>>(BootstrapFailureException(BootstrapFailureCode.DnsTimeout))
        assertEquals(Verdict.Unknown, DecoyDnsCheck.classify(trusted, timeout))
    }
}
