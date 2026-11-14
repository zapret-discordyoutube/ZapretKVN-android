package io.github.zapretkvn.android.vpn.runtime

/** What a test through one server alone (the core's relay HTTPS, no traffic moved) says about it. */
internal enum class RelayVerdict {
    /** The server answered. */
    Alive,

    /** The core's test timed out: nothing came back through the server. */
    NoResponse,

    /** The test did not finish or the core could not run it: it says nothing, the pick goes on as before. */
    Unknown,
}

/**
 * Check first, then switch (owner decision 2026-11-14): before a manual pick moves traffic to a server, the app
 * tests that server alone. A dead server then never freezes the user's connection for the ~20 s the old
 * switch-then-verify order needed (field case: 4virtual, UDP does not arrive).
 *
 * A single timeout must not lock out a server that works but is slow to start: a repeated pick of the same server
 * within [OVERRIDE_MILLIS] after it was refused skips the test and goes through the full transaction, which has the
 * long verification budget. Pure state, owned by the runtime actor.
 */
internal class PreSelectionGate(private val clock: () -> Long) {
    private val refusedAt = mutableMapOf<String, Long>()

    /** Test the server first? False for a server refused a moment ago: the user insists, so the old path runs. */
    fun shouldCheck(outboundTag: String): Boolean {
        val at = refusedAt[outboundTag] ?: return true
        return clock() - at >= OVERRIDE_MILLIS
    }

    /** Remember the verdict; true when the pick must not move any traffic. */
    fun refuses(outboundTag: String, verdict: RelayVerdict): Boolean {
        if (verdict == RelayVerdict.NoResponse) {
            refusedAt[outboundTag] = clock()
            return true
        }
        refusedAt.remove(outboundTag)
        return false
    }

    companion object {
        const val OVERRIDE_MILLIS = 2 * 60_000L

        /** Longest the test may take before the pick goes on without a verdict. */
        const val CHECK_TIMEOUT_MILLIS = 8_000L
    }
}
