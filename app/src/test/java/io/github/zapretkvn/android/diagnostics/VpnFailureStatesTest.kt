package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.networkbootstrap.CodedFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnFailureStatesTest {
    private class Coded(
        override val failureCode: String,
        message: String,
    ) : IOException(message), CodedFailure {
        override val userMessage = message
        override val technicalDetail = "verdict=reset cloudflare:reset@5"
    }

    @Test
    fun `typed code wins over a matching runtime text rule`() {
        // "Connection reset" matches TARGET_CONNECTION_CLOSED in runtime-errors.json.
        val state = VpnFailureStates.from(Coded("VPN-200", "HTTPS через VPN: cloudflare:Connection reset"))
        assertEquals("VPN-200", state.code)
        assertEquals("verdict=reset cloudflare:reset@5", state.technicalDetail)
    }

    @Test
    fun `typed code is found deep in the cause chain`() {
        val state = VpnFailureStates.from(
            IllegalStateException("outer", Coded("DNS-101", "Системный DNS не ответил")),
        )
        assertEquals("DNS-101", state.code)
    }

    @Test
    fun `untyped core failures still use the runtime text rules`() {
        val state = VpnFailureStates.from(IllegalStateException("read: connection reset by peer"))
        assertEquals("TARGET_CONNECTION_CLOSED", state.code)
    }
}
