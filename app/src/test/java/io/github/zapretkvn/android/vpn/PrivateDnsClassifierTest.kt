package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.network.PrivateDnsClassifier
import io.github.zapretkvn.android.network.PrivateDnsMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateDnsClassifierTest {
    @Test
    fun `strict wins even while validation is inactive`() {
        assertEquals(PrivateDnsMode.Strict, PrivateDnsClassifier.classify(28, false, "dns.example"))
    }

    @Test
    fun `automatic and off are distinguished without changing Android`() {
        assertEquals(PrivateDnsMode.Automatic, PrivateDnsClassifier.classify(36, true, null))
        assertEquals(PrivateDnsMode.Off, PrivateDnsClassifier.classify(36, false, null))
        assertEquals(PrivateDnsMode.Off, PrivateDnsClassifier.classify(26, true, "ignored.example"))
    }
}
