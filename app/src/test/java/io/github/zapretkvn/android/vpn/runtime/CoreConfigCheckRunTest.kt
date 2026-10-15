package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.CoreConfigCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConfigCheckRunTest {
    private fun launch(check: CoreConfigCheck, json: String, clock: () -> Long = { 100L }) =
        CoreConfigCheckRun.launch(
            check = check,
            runtimeJson = json,
            elapsedRealtime = clock,
            epochMillis = { 1_700_000_000_000L },
            dispatcher = Dispatchers.Default,
        )

    @Test
    fun acceptedConfigReportsStartAndNotCachedThenCachedOnRepeat() = runBlocking {
        val check = CoreConfigCheck({ })
        val ticks = generateSequence(10L) { it + 25L }.iterator()

        val first = launch(check, "{}", { ticks.next() }).await()
        assertNull(first.failure)
        assertFalse(first.cached)
        assertEquals(10L, first.startedAtElapsedMillis)
        assertEquals(25L, first.durationMillis)
        assertEquals(1_700_000_000_000L, first.startedAtEpochMillis)

        val second = launch(check, "{}").await()
        assertNull(second.failure)
        assertTrue(second.cached)
    }

    @Test
    fun rejectionIsReturnedNotThrownAndSameInstance() = runBlocking {
        val rejection = IllegalArgumentException("unknown outbound")
        val check = CoreConfigCheck({ throw rejection })

        val run = launch(check, "{}").await()

        assertTrue(run.failure === rejection)
        assertFalse(run.cached)
    }

    @Test
    fun checkRunsOffTheCallerThreadSoStartCanProceedInParallel() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val check = CoreConfigCheck({
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        })

        val deferred = launch(check, "{}")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse("caller must not be blocked by the check", deferred.isCompleted)
        release.countDown()
        assertNull(deferred.await().failure)
    }
}
