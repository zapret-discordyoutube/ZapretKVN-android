package io.github.zapretkvn.android.platform

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnSystemPolicyTest {
    @Test
    fun `inactive system policy does not block manual VPN`() {
        assertNull(VpnSystemPolicy(true, alwaysOn = false, lockdown = false).blockingMessage)
        assertNull(VpnSystemPolicy(false, alwaysOn = false, lockdown = false).blockingMessage)
    }

    @Test
    fun `always-on and lockdown explain unsupported Android mode`() {
        assertTrue(
            VpnSystemPolicy(true, alwaysOn = true, lockdown = false)
                .blockingMessage.orEmpty().contains("Always-on"),
        )
        assertTrue(
            VpnSystemPolicy(true, alwaysOn = true, lockdown = true)
                .blockingMessage.orEmpty().contains("Lockdown"),
        )
    }
}
