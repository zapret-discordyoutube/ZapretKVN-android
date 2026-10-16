package io.github.zapretkvn.android.network.probes

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictPrivateDnsProbeTest {
    /** Виртуальные часы: пауза и сама попытка двигают время, а не ждут его. */
    private class Clock(var now: Long = 0)

    @Test
    fun `late first answer is waited for instead of failing the connection`() = runBlocking {
        val clock = Clock()
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = StrictPrivateDnsProbe.resolve(
            budgetMillis = 12_000,
            clock = { clock.now },
            random = Random(7),
            pause = { pauses += it; clock.now += it },
        ) {
            calls += 1
            clock.now += 3_000 // каждая попытка — полный тайм-аут резолва
            if (calls < 3) throw IOException("DNS-101") else listOf("answer")
        }
        assertEquals(listOf("answer"), result)
        assertEquals(3, calls)
        assertEquals(2, pauses.size)
        assertTrue(pauses.all { it in StrictPrivateDnsProbe.MIN_PAUSE_MILLIS..StrictPrivateDnsProbe.MAX_PAUSE_MILLIS })
    }

    @Test
    fun `pauses are jittered not a fixed rhythm`() = runBlocking {
        val clock = Clock()
        val pauses = mutableListOf<Long>()
        try {
            StrictPrivateDnsProbe.resolve(
                budgetMillis = 60_000,
                clock = { clock.now },
                random = Random(42),
                pause = { pauses += it; clock.now += it },
            ) { clock.now += 100; throw IOException("down") }
            fail("dead DNS must fail")
        } catch (_: IOException) {
        }
        assertTrue(pauses.size > 5)
        assertTrue("pauses must differ", pauses.toSet().size > 1)
    }

    @Test
    fun `dead DNS fails within the budget with the last error`() = runBlocking {
        val clock = Clock()
        val last = IOException("last")
        var calls = 0
        try {
            StrictPrivateDnsProbe.resolve(
                budgetMillis = 12_000,
                clock = { clock.now },
                random = Random(1),
                pause = { clock.now += it },
            ) {
                calls += 1
                clock.now += 3_000
                throw if (calls >= 3) last else IOException("early")
            }
            fail("dead DNS must fail")
        } catch (error: IOException) {
            assertSame(last, error)
        }
        assertTrue("no attempt may start after the budget", clock.now <= 12_000 + 3_000)
    }

    @Test
    fun `first success needs no retry`() = runBlocking {
        var attempts = 0
        val value = StrictPrivateDnsProbe.resolve(budgetMillis = 12_000, onAttempt = { attempts = it }) { 1 }
        assertEquals(1, value)
        assertEquals(1, attempts)
    }
}
