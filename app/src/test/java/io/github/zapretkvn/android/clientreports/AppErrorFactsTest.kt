package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.diagnostics.AppCrashFrame
import io.github.zapretkvn.android.diagnostics.AppCrashRecord
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppErrorFactsTest {
    @Test
    fun aCrashGoesAsItsTypeAndFramesWithoutTheMessage() {
        val record = AppCrashRecord(
            occurredAtEpochMillis = 5, threadName = "main", exceptionType = "IllegalStateException",
            message = "secret path /data/user/0", causes = emptyList(),
            stack = listOf(AppCrashFrame("VpnRuntime", "start", 680), AppCrashFrame("Handler", "dispatch", -1)),
        )
        val (at, error) = AppErrorFacts.crash(record)!!
        assertEquals(5L, at)
        assertEquals("crash:IllegalStateException", error.code)
        assertEquals(listOf("VpnRuntime.start:680", "Handler.dispatch:0"), error.frames)
        assertNull(AppErrorFacts.crash(null))
    }

    @Test
    fun aSubscriptionFailureIsNamedByStatusOrTransport() {
        assertEquals("sub:http_5xx", AppErrorFacts.subscription(502, null, ours = true, route = "direct")!!.code)
        assertEquals("sub:http_4xx", AppErrorFacts.subscription(404, null, ours = false, route = "tunnel")!!.code)
        assertEquals("sub:timeout", AppErrorFacts.subscription(null, IOException("x", SocketTimeoutException()), true, "direct")!!.code)
        assertEquals("sub:dns", AppErrorFacts.subscription(null, UnknownHostException("h"), true, "direct")!!.code)
        assertEquals("sub:tls", AppErrorFacts.subscription(null, SSLHandshakeException("h"), true, "direct")!!.code)
        assertEquals("direct", AppErrorFacts.subscription(null, IOException(), true, "weird")!!.subscription!!.route)
    }

    @Test
    fun startPermissionAndUpdateCodes() {
        assertEquals("start:VPN-120", AppErrorFacts.start("VPN-120")!!.code)
        assertEquals("port:bind", AppErrorFacts.start("LOCAL_BIND_COLLISION")!!.code)
        assertEquals("perm:vpn_denied", AppErrorFacts.permission("vpn_denied")!!.code)
        assertEquals("update:check_net", AppErrorFacts.update("check_net")!!.code)
    }
}
