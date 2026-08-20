package io.github.zapretkvn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreVersionTest {
    @Test
    fun pinnedCoreIdentityIsComplete() {
        assertEquals("v1.13.18-extended-2.6.5", BuildConfig.CORE_TAG)
        assertEquals(40, BuildConfig.CORE_COMMIT.length)
        assertTrue(BuildConfig.CORE_COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertEquals(64, BuildConfig.CORE_PATCH_SHA256.length)
        assertTrue(BuildConfig.CORE_PATCH_SHA256.matches(Regex("[0-9a-f]{64}")))
    }
}
