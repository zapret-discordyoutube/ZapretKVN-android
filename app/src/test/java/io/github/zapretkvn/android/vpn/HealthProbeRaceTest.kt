package io.github.zapretkvn.android.vpn

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HealthProbeRaceTest {
    @Test
    fun `first success cancels slower candidates`() = runBlocking<Unit> {
        val slowCancelled = AtomicBoolean(false)
        val startedAt = System.nanoTime()

        val outcome = HealthProbeRace.firstSuccess(listOf("slow", "fast")) { candidate ->
            when (candidate) {
                "fast" -> {
                    delay(10)
                    204
                }
                else -> try {
                    delay(30_000)
                    fail("Медленный кандидат не должен завершиться.")
                    0
                } catch (cancelled: CancellationException) {
                    slowCancelled.set(true)
                    throw cancelled
                }
            }
        }

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(HealthProbeRace.Outcome.Success("fast", 204), outcome)
        assertTrue("Гонка должна завершиться сразу после успеха.", elapsedMillis < 5_000)
        assertTrue("Проигравший кандидат должен быть отменён.", slowCancelled.get())
    }

    @Test
    fun `all failures preserve candidate order regardless of completion order`() = runBlocking {
        val outcome = HealthProbeRace.firstSuccess<String, Int>(listOf("first", "second")) { candidate ->
            if (candidate == "first") delay(50)
            throw IOException("fail_$candidate")
        }

        val failures = (outcome as HealthProbeRace.Outcome.AllFailed).failures
        assertEquals(listOf("first", "second"), failures.map { it.first })
        assertEquals(listOf("fail_first", "fail_second"), failures.map { it.second.message })
    }

    @Test
    fun `fatal error is rethrown immediately`() = runBlocking<Unit> {
        class FatalProbeError : IOException("fatal")

        try {
            HealthProbeRace.firstSuccess(
                candidates = listOf("fatal", "slow"),
                isFatal = { it is FatalProbeError },
            ) { candidate ->
                if (candidate == "fatal") throw FatalProbeError()
                delay(30_000)
                0
            }
            fail("Fatal-ошибка должна пробрасываться.")
        } catch (expected: FatalProbeError) {
            // ожидаемо
        }
    }

    @Test
    fun `single candidate success returns its value`() = runBlocking {
        val outcome = HealthProbeRace.firstSuccess(listOf("only")) { 42 }
        assertEquals(HealthProbeRace.Outcome.Success("only", 42), outcome)
    }
}
