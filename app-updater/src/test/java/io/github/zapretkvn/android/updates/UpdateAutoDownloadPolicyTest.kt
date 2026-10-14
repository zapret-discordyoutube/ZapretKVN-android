package io.github.zapretkvn.android.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAutoDownloadPolicyTest {
    private val limit = UpdateAutoDownloadPolicy.METERED_LIMIT_BYTES

    @Test
    fun `unmetered network always downloads`() {
        assertTrue(UpdateAutoDownloadPolicy.allows(metered = false, plannedBytes = 0))
        assertTrue(UpdateAutoDownloadPolicy.allows(metered = false, plannedBytes = 500L * 1024 * 1024))
    }

    @Test
    fun `metered network downloads only small updates`() {
        assertTrue(UpdateAutoDownloadPolicy.allows(metered = true, plannedBytes = 3_150_000))
        assertTrue(UpdateAutoDownloadPolicy.allows(metered = true, plannedBytes = limit))
        assertFalse(UpdateAutoDownloadPolicy.allows(metered = true, plannedBytes = limit + 1))
        assertFalse(UpdateAutoDownloadPolicy.allows(metered = true, plannedBytes = 117_842_983))
    }
}
