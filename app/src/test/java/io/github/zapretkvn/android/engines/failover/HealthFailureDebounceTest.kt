package io.github.zapretkvn.android.engines.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthFailureDebounceTest {
    @Test
    fun `fires only after the configured streak of failures`() {
        val debounce = HealthFailureDebounce(threshold = 3)
        assertFalse(debounce.onProbe(healthy = false))
        assertFalse(debounce.onProbe(healthy = false))
        assertTrue(debounce.onProbe(healthy = false))
    }

    @Test
    fun `a healthy probe clears the streak`() {
        val debounce = HealthFailureDebounce(threshold = 2)
        assertFalse(debounce.onProbe(healthy = false))
        assertFalse(debounce.onProbe(healthy = true))
        // Streak restarts from zero, so one more failure is not yet enough.
        assertFalse(debounce.onProbe(healthy = false))
        assertTrue(debounce.onProbe(healthy = false))
    }

    @Test
    fun `firing resets the streak so the next episode needs a full streak again`() {
        val debounce = HealthFailureDebounce(threshold = 2)
        assertFalse(debounce.onProbe(healthy = false))
        assertTrue(debounce.onProbe(healthy = false))
        assertFalse(debounce.onProbe(healthy = false))
        assertTrue(debounce.onProbe(healthy = false))
    }

    @Test
    fun `threshold of one fires on every failure`() {
        val debounce = HealthFailureDebounce(threshold = 1)
        assertTrue(debounce.onProbe(healthy = false))
        assertFalse(debounce.onProbe(healthy = true))
        assertTrue(debounce.onProbe(healthy = false))
    }

    @Test
    fun `reset clears an in-progress streak`() {
        val debounce = HealthFailureDebounce(threshold = 2)
        assertFalse(debounce.onProbe(healthy = false))
        debounce.reset()
        assertFalse(debounce.onProbe(healthy = false))
        assertTrue(debounce.onProbe(healthy = false))
    }

    @Test
    fun `threshold below one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { HealthFailureDebounce(threshold = 0) }
    }
}
