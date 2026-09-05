package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.network.BootstrapCacheEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCachePolicyTest {
    @Test
    fun `LKG freshness is 24 hours and emergency lifetime is seven days`() {
        val now = 10L * 24 * 60 * 60 * 1_000
        fun entry(age: Long) = BootstrapCacheEntry("p", "vpn.example", listOf("203.0.113.1"), now - age, now)

        assertTrue(entry(BootstrapCacheEntry.FRESH_MILLIS).isFreshAt(now))
        assertFalse(entry(BootstrapCacheEntry.FRESH_MILLIS + 1).isFreshAt(now))
        assertTrue(entry(BootstrapCacheEntry.EMERGENCY_MILLIS).isUsableAt(now))
        assertFalse(entry(BootstrapCacheEntry.EMERGENCY_MILLIS + 1).isUsableAt(now))
    }
}
