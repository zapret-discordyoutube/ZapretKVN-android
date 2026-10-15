package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.vpn.ExternalIpStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrafficAccumulatorTest {
    @Test
    fun `ring keeps exactly last sixty session samples`() {
        val accumulator = SessionTrafficAccumulator()
        accumulator.start(7, "profile", 1_000)
        accumulator.setStatusStreamActive(7, true)

        repeat(65) { index ->
            accumulator.updateTraffic(7, index.toLong(), (index * 2L), index * 10L, index * 20L)
        }

        assertEquals(60, accumulator.value.samples.size)
        assertEquals(5L, accumulator.value.samples.first().uploadBytesPerSecond)
        assertEquals(64L, accumulator.value.samples.last().uploadBytesPerSecond)
        assertEquals(640L, accumulator.value.uploadTotalBytes)
        assertEquals(1_280L, accumulator.value.downloadTotalBytes)
    }

    @Test
    fun `hidden home rejects status updates without losing current session`() {
        val accumulator = SessionTrafficAccumulator(capacity = 3)
        accumulator.start(2, "profile", 42)
        accumulator.setStatusStreamActive(2, true)
        accumulator.updateTraffic(2, 10, 20, 30, 40)
        accumulator.setStatusStreamActive(2, false)

        assertNull(accumulator.updateTraffic(2, 100, 200, 300, 400))
        assertFalse(accumulator.value.statusStreamActive)
        assertEquals(30L, accumulator.value.uploadTotalBytes)
        assertEquals(1, accumulator.value.samples.size)
    }

    @Test
    fun `stale generation cannot overwrite identity or traffic`() {
        val accumulator = SessionTrafficAccumulator()
        accumulator.start(10, "new", 500)
        accumulator.setStatusStreamActive(10, true)

        assertNull(accumulator.updateExternalIp(9, "203.0.113.1"))
        assertNull(accumulator.updateTraffic(9, 1, 2, 3, 4))
        assertTrue(accumulator.value.samples.isEmpty())
        assertNull(accumulator.value.externalIp)
    }

    @Test
    fun `external IP is empty after start and only an explicit request fills it`() {
        val accumulator = SessionTrafficAccumulator()
        accumulator.start(5, "profile", 100)
        assertNull(accumulator.value.externalIp)
        assertEquals(ExternalIpStatus.NotRequested, accumulator.value.externalIpStatus)

        assertEquals(ExternalIpStatus.Loading, accumulator.beginExternalIp(5)?.externalIpStatus)
        accumulator.updateExternalIp(5, "203.0.113.1")
        assertEquals("203.0.113.1", accumulator.value.externalIp)
        assertEquals(ExternalIpStatus.NotRequested, accumulator.value.externalIpStatus)

        // Неудачное обновление не стирает известный адрес.
        accumulator.beginExternalIp(5)
        accumulator.updateExternalIp(5, null)
        assertEquals("203.0.113.1", accumulator.value.externalIp)
        assertEquals(ExternalIpStatus.Failed, accumulator.value.externalIpStatus)
    }

    @Test
    fun `server switch and reconnect forget the cached external IP`() {
        val accumulator = SessionTrafficAccumulator()
        accumulator.start(5, "profile", 100)
        accumulator.updateExternalIp(5, "203.0.113.1")

        accumulator.clearConnectionIdentity(5)
        assertNull(accumulator.value.externalIp)
        assertEquals(ExternalIpStatus.NotRequested, accumulator.value.externalIpStatus)

        accumulator.updateExternalIp(5, "203.0.113.2")
        accumulator.start(6, "profile", 200)
        assertNull(accumulator.value.externalIp)
        assertNull(accumulator.beginExternalIp(5))
    }
}
