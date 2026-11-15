package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.slots.youtube.deadTrialPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Silence after a trial move: a slot that left the trial server by itself means a dead candidate (2026-11-15). */
class DeadTrialTest {
    @Test
    fun `slot left the trial server on its own so the candidate was dead`() {
        assertEquals("4virtual", deadTrialPlace("Clermany", "4virtual"))
    }

    @Test
    fun `slot still on the trial server is plain silence`() {
        assertNull(deadTrialPlace("Clermany", "Clermany"))
    }

    @Test
    fun `no trial or no session means nothing to judge`() {
        assertNull(deadTrialPlace(null, "4virtual"))
        assertNull(deadTrialPlace("Clermany", null))
    }
}
