package io.github.zapretkvn.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreConfigCheckTest {
    @Test
    fun repeatedConfigIsServedFromCacheAfterFirstSuccess() {
        val calls = mutableListOf<String>()
        val check = CoreConfigCheck({ calls += it })

        assertFalse(check.check("""{"a":1}"""))
        assertTrue(check.check("""{"a":1}"""))
        assertFalse(check.check("""{"a":2}"""))
        assertTrue(check.check("""{"a":2}"""))

        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), calls)
    }

    @Test
    fun rejectedConfigIsNeverCachedAndKeepsOriginalException() {
        var calls = 0
        val rejection = IllegalStateException("bad outbound")
        val check = CoreConfigCheck({
            calls += 1
            throw rejection
        })

        repeat(2) {
            try {
                check.check("{}")
                fail("expected rejection")
            } catch (thrown: IllegalStateException) {
                assertTrue(thrown === rejection)
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun configFixedAfterRejectionPassesAndIsThenCached() {
        var accept = false
        var calls = 0
        val check = CoreConfigCheck({
            calls += 1
            if (!accept) error("nope")
        })

        runCatching { check.check("{}") }
        accept = true
        assertFalse(check.check("{}"))
        assertTrue(check.check("{}"))
        assertEquals(2, calls)
    }

    @Test
    fun cacheIsBoundedAndEvictsLeastRecentlyUsed() {
        var calls = 0
        val check = CoreConfigCheck({ calls += 1 }, capacity = 2)

        check.check("a")
        check.check("b")
        assertTrue(check.check("a")) // a свежее b
        check.check("c") // вытесняет b
        assertEquals(3, calls)
        assertTrue(check.check("a"))
        assertFalse(check.check("b"))
        assertEquals(4, calls)
    }

    @Test
    fun clearForgetsPassedConfigs() {
        var calls = 0
        val check = CoreConfigCheck({ calls += 1 })

        check.check("x")
        check.clear()
        assertFalse(check.check("x"))
        assertEquals(2, calls)
    }

    @Test
    fun fingerprintIsStableSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CoreConfigCheck.fingerprint("abc"),
        )
        assertNotEquals(CoreConfigCheck.fingerprint("abc"), CoreConfigCheck.fingerprint("abd"))
    }
}
