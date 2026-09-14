package io.github.zapretkvn.android.engines.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundFailoverCoordinatorTest {
    private fun target(id: String, valid: Boolean = true, maintenance: Boolean = false) =
        FailoverTarget(id, valid = valid, maintenance = maintenance)

    @Test
    fun `selects one next eligible target and never retries the failed target`() {
        var now = 1_000L
        val coordinator = OutboundFailoverCoordinator({ now }, cooldownMillis = 5_000)
        val targets = listOf(
            target("failed"),
            target("maintenance", maintenance = true),
            target("invalid", valid = false),
            target("replacement"),
            target("second"),
        )

        val selected = coordinator.chooseReplacement(
            "failed",
            recoverable = true,
            orderedTargets = targets,
        ) as FailoverOutcome.Candidate
        assertEquals("replacement", selected.target.id)
        coordinator.failReplacement()
        assertEquals(
            FailoverOutcome.FailureAlreadyHandled,
            coordinator.chooseReplacement("failed", recoverable = true, orderedTargets = targets),
        )
        now += 6_000
        assertEquals(
            "same episode must not consume a second target",
            FailoverOutcome.FailureAlreadyHandled,
            coordinator.chooseReplacement("failed", recoverable = true, orderedTargets = targets),
        )
    }

    @Test
    fun `non-recoverable failure is a typed outcome and consumes nothing`() {
        val coordinator = OutboundFailoverCoordinator({ 1_000L })
        assertEquals(
            FailoverOutcome.FailureNotRecoverable,
            coordinator.chooseReplacement(
                "failed",
                recoverable = false,
                orderedTargets = listOf(target("failed"), target("other")),
            ),
        )
        assertFalse(coordinator.automaticAttempted())
    }

    @Test
    fun `reset clears failed-target cooldown so a new lifecycle reconsiders every target`() {
        var now = 1_000L
        val coordinator = OutboundFailoverCoordinator({ now }, cooldownMillis = 300_000)
        val targets = listOf(target("first"), target("second"))

        // Episode 1: "first" fails, we switch to "second" and it stays healthy.
        val toSecond = coordinator.chooseReplacement(
            "first",
            recoverable = true,
            orderedTargets = targets,
        ) as FailoverOutcome.Candidate
        assertEquals("second", toSecond.target.id)
        coordinator.commitReplacement()
        now += 10_000 // clear the post-commit stale-log fence, stay inside the cooldown

        // Without a reset "first" is still cooling down, so "second" failing would
        // wrongly report NoCompatibleTarget even though "first" is available.
        assertEquals(
            FailoverOutcome.NoCompatibleTarget,
            coordinator.chooseReplacement("second", recoverable = true, orderedTargets = targets),
        )

        // A brand-new connection lifecycle must forget prior cooldowns.
        coordinator.reset()
        val toFirst = coordinator.chooseReplacement(
            "second",
            recoverable = true,
            orderedTargets = targets,
        ) as FailoverOutcome.Candidate
        assertEquals("first", toFirst.target.id)
    }

    @Test
    fun `missing replacement is a typed terminal outcome`() {
        val coordinator = OutboundFailoverCoordinator({ 1_000L })

        assertEquals(
            FailoverOutcome.NoCompatibleTarget,
            coordinator.chooseReplacement(
                "only",
                recoverable = true,
                orderedTargets = listOf(target("only")),
            ),
        )
        assertTrue(coordinator.automaticAttempted())
        assertFalse(coordinator.replacementInFlight())
        assertEquals(1L, coordinator.failureEpisodeId)
    }

    @Test
    fun `stale-log fence ignores a failure that lands right after a commit`() {
        var now = 1_000L
        val coordinator = OutboundFailoverCoordinator({ now })
        val targets = listOf(target("a"), target("b"))

        coordinator.chooseReplacement("a", recoverable = true, orderedTargets = targets)
        coordinator.commitReplacement()
        // Within the 2s fence the just-committed target's late log is ignored.
        assertEquals(
            FailoverOutcome.StaleFailureIgnored,
            coordinator.chooseReplacement("b", recoverable = true, orderedTargets = targets),
        )
    }
}
